package com.lrj.commerce.runtime.audit.archive;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.audit.archive.ArchiveExpiryMapper.Receipt;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Batch;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Scope;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveRetentionReview.Policy;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** 精确对象版本的到期处理，保留已提交的切换证据并阻止旧备份提供已销毁副本。 */
public final class AuditArchiveObjectExpiry {
    static final int MAX_LATE_ROWS = 2_000;
    private static final String ONLINE_PURGED = "ONLINE_PURGED";
    private static final String EXPIRED = "EXPIRED";
    private final Scope scope;
    private final AuditArchiveMapper archive;
    private final ArchiveOnlineCutoverMapper cutover;
    private final ArchivePurgeRecoveryMapper purge;
    private final ArchiveExpiryMapper expiry;
    private final AuditArchiveVerification verification;
    private final AuditArchiveRetentionReview retention;
    private final AuditArchivePurgeRecovery recovery;
    private final ArchiveVersionDisposal versions;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public AuditArchiveObjectExpiry(Scope scope, AuditArchiveMapper archive, ArchiveOnlineCutoverMapper cutover,
            ArchivePurgeRecoveryMapper purge, ArchiveExpiryMapper expiry, AuditArchiveVerification verification,
            AuditArchiveRetentionReview retention, AuditArchivePurgeRecovery recovery, ArchiveVersionDisposal versions,
            Clock clock, PlatformTransactionManager manager) {
        this.scope = scope;
        this.archive = archive;
        this.cutover = cutover;
        this.purge = purge;
        this.expiry = expiry;
        this.verification = verification;
        this.retention = retention;
        this.recovery = recovery;
        this.versions = versions;
        this.clock = clock;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(10);
    }

    /** 对象不存在只能作为已有持久意图的续办证据；初次准备仍须成功回读原始版本。 */
    public Result expire(String batchId) {
        AuditArchivePurgeRecovery.requireBatchId(batchId);
        Batch batch = archive.batch(batchId);
        if (batch == null || !scope.type().equals(batch.scopeType()) || !scope.tenant().equals(batch.tenantId())) throw mismatch();
        if (EXPIRED.equals(batch.state())) return finishExpired(batch, Outcome.ALREADY_EXPIRED);
        if (!ONLINE_PURGED.equals(batch.state()) || purge.reference(batchId) == null) return new Result(Outcome.NOT_READY);
        Receipt pending = expiry.receipt(batchId);
        if (pending == null) {
            var candidate = plan(batch);
            if (candidate == null) return new Result(Outcome.INELIGIBLE);
            var reserve = transaction.execute(status -> reserve(batch, candidate));
            if (reserve != Outcome.READY) return new Result(reserve);
            pending = expiry.receipt(batchId);
        }
        var intent = pending;
        recovery.prepareExpiry(batch, intent);
        Outcome result = transaction.execute(status -> dispose(batch, intent));
        if (result == Outcome.EXPIRED || result == Outcome.ALREADY_EXPIRED) {
            return finishExpired(archive.batch(batchId), result);
        }
        return new Result(result);
    }

    private Result finishExpired(Batch batch, Outcome outcome) {
        var receipt = requiredConfirmed(batch.batchId());
        recovery.publishExpiry(batch, receipt);
        var cleanup = transaction.execute(status -> recovery.cleanupExpired(batch.batchId(), receipt.latestLateId()));
        return new Result(outcome, cleanup.removedCopies(), cleanup.removedLateRows());
    }

    private Receipt plan(Batch batch) {
        var grant = expiry.lockGrant(batch.batchId());
        if (grant == null) return null;
        var policies = archive.policies(scope);
        var rows = verification.archivedMembers(batch);
        Instant after = retention.archivalPeriod(batch.batchId(), rows, policies);
        if (after == null) return null;
        var policyByKey = policies.stream().collect(Collectors.toUnmodifiableMap(Policy::key, Function.identity()));
        var resources = new TreeSet<String>();
        var days = new HashMap<Long, Integer>();
        for (var row : rows) {
            JsonNode attempt = JsonCodec.read(row.attempt(), JsonNode.class);
            addResource(resources, attempt);
            if (row.effect() != null) addResource(resources, JsonCodec.read(row.effect(), JsonNode.class));
            var policy = policyByKey.get(new AuditArchiveRetentionReview.Key(
                    attempt.path("policy").asString(), attempt.path("policyVersion").asInt()));
            days.put(row.attemptId(), policy.totalDays());
        }
        var late = expiry.latePage(batch.batchId(), MAX_LATE_ROWS + 1);
        if (late.size() > MAX_LATE_ROWS) return null;
        for (var row : late) {
            if (row.resourceId() != null) resources.add(row.resourceId());
            Integer retentionDays = days.get(row.attemptId());
            if (retentionDays == null) throw mismatch();
            Instant memberExpiry = row.recordedAt().plus(retentionDays, ChronoUnit.DAYS);
            if (memberExpiry.isAfter(after)) after = memberExpiry;
        }
        if (after.isAfter(clock.instant())) return null;
        return new Receipt(batch.batchId(), batch.objectVersion(), batch.sha256(), grant.approvalReference(),
                JsonCodec.hash(JsonCodec.write(policies)), JsonCodec.write(resources), after,
                late.isEmpty() ? 0 : late.getFirst().id(), fingerprints(late), null);
    }

    private Outcome reserve(Batch expected, Receipt candidate) {
        var actual = cutover.lockBatch(expected.batchId());
        if (!expected.equals(actual)) return Outcome.CHANGED;
        var old = expiry.receipt(expected.batchId());
        if (old != null) return Outcome.READY;
        var eligible = checkCurrent(candidate);
        if (eligible != Outcome.READY) return eligible;
        if (expiry.reserve(candidate) != 1) throw mismatch();
        return Outcome.READY;
    }

    private Outcome dispose(Batch expected, Receipt pending) {
        var locked = cutover.lockBatch(expected.batchId());
        if (locked != null && EXPIRED.equals(locked.state())) return Outcome.ALREADY_EXPIRED;
        if (!expected.equals(locked) || !pending.equals(expiry.receipt(expected.batchId()))) return Outcome.CHANGED;
        // 这里锁的是独立归档批次、冻结范围和审批，存储请求使用短超时。
        // 先核实是否已在前一次请求中删除，避免把丢失响应误记成继续保留。
        var eligible = checkCurrent(pending);
        if (eligible != Outcome.READY) return eligible;
        if (versions.exists(expected.objectKey(), pending.archiveVersion())) {
            versions.delete(expected.objectKey(), pending.archiveVersion());
            if (versions.exists(expected.objectKey(), pending.archiveVersion())) return Outcome.NOT_CONFIRMED;
        }
        if (expiry.confirm(expected.batchId()) != 1 || expiry.markExpired(expected.batchId()) != 1) throw mismatch();
        return Outcome.EXPIRED;
    }

    private Outcome checkCurrent(Receipt proof) {
        var grant = expiry.lockGrant(proof.batchId());
        if (grant == null || !grant.approvalReference().equals(proof.approvalReference())) return Outcome.NOT_APPROVED;
        cutover.lockHolds(scope);
        if (!expiry.lockMatchingHolds(scope, proof.resourceIds()).isEmpty()
                || !expiry.lockActiveRestoreGrants(proof.batchId()).isEmpty()) return Outcome.HELD;
        List<Policy> policies = cutover.lockPolicies(scope);
        if (!JsonCodec.hash(JsonCodec.write(policies)).equals(proof.policiesSha256()) || proof.eligibleAt().isAfter(clock.instant())) {
            return Outcome.CHANGED;
        }
        var late = expiry.latePage(proof.batchId(), MAX_LATE_ROWS + 1);
        if ((late.isEmpty() ? 0 : late.getFirst().id()) != proof.latestLateId()
                || !JsonCodec.read(fingerprints(late), JsonNode.class)
                        .equals(JsonCodec.read(proof.lateFingerprints(), JsonNode.class))) return Outcome.CHANGED;
        return Outcome.READY;
    }

    /** 外部凭证只保全摘要，恢复时可逐条核对仍存在的晚到证据。 */
    private static String fingerprints(List<ArchiveExpiryMapper.Late> rows) {
        var fingerprints = new TreeMap<String, String>();
        for (var row : rows) fingerprints.put(Long.toString(row.id()), fingerprint(row));
        return JsonCodec.write(fingerprints);
    }

    static String fingerprint(ArchiveExpiryMapper.Late row) {
        return JsonCodec.hash(JsonCodec.write(row));
    }

    private Receipt requiredConfirmed(String id) {
        var receipt = expiry.receipt(id);
        if (receipt == null || receipt.confirmedAt() == null) throw mismatch();
        return receipt;
    }

    private static void addResource(TreeSet<String> resources, JsonNode from) {
        var resource = from.path("resourceId");
        if (resource.isString()) resources.add(resource.asString());
    }

    private static DomainException mismatch() {
        return new DomainException(DomainException.Code.CONFLICT, "归档销毁凭证与本地证据不一致");
    }

    public record Result(Outcome outcome, int removedCopies, int removedLateRows) {
        private Result(Outcome outcome) {
            this(outcome, 0, 0);
        }
    }

    public enum Outcome { READY, EXPIRED, ALREADY_EXPIRED, NOT_APPROVED, NOT_READY, INELIGIBLE, HELD, CHANGED, NOT_CONFIRMED }
}
