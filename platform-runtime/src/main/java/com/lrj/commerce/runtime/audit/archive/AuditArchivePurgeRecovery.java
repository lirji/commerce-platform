package com.lrj.commerce.runtime.audit.archive;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.audit.archive.ArchiveOnlineCutoverMapper.Receipt;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Batch;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Member;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Scope;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** 把已生效的清理证明保存到数据库备份之外，恢复时只处理证明中逐行校验通过的副本。 */
public final class AuditArchivePurgeRecovery {
    private static final int FORMAT = 1;
    private static final int MAX_ROWS = 200;
    private static final int INVENTORY_PAGE_SIZE = 20;
    private static final int MAX_BYTES = 128 * 1024;
    private static final String ONLINE_PURGED = "ONLINE_PURGED";
    private static final String JOURNAL_PREFIX = "commerce/operation-audit/v1/recovery/";
    private final Scope scope;
    private final AuditArchiveMapper archive;
    private final ArchiveOnlineCutoverMapper cutover;
    private final ArchivePurgeRecoveryMapper recovery;
    private final AuditArchiveVerification verification;
    private final ArchiveObjectStore objects;
    private final TransactionTemplate transaction;

    public AuditArchivePurgeRecovery(Scope scope, AuditArchiveMapper archive, ArchiveOnlineCutoverMapper cutover,
            ArchivePurgeRecoveryMapper recovery, AuditArchiveVerification verification, ArchiveObjectStore objects,
            PlatformTransactionManager manager) {
        this.scope = scope;
        this.archive = archive;
        this.cutover = cutover;
        this.recovery = recovery;
        this.verification = verification;
        this.objects = objects;
        transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
    }

    /** 每次发布一个回执，上传超时或应用重启后仍使用同一对象键和相同字节。 */
    public Reference publishNext() {
        String id = recovery.nextPending(scope);
        return id == null ? null : publish(id);
    }

    /** 准备记录在删除前独立保全；单批重跑复用同一内容及对象键。 */
    void prepare(Batch batch) {
        requireBatchId(batch.batchId());
        if (!sameScope(batch) || !validHash(batch.sha256()) || batch.objectVersion() == null) throw mismatch();
        var entry = new Prepared(FORMAT, batch.batchId(), batch.objectVersion(), batch.sha256());
        writeOnce(batch.batchId(), preparedPrefix() + batch.batchId() + ".json", JsonCodec.write(entry));
    }

    /** 逐页检查备份域以外的准备记录，未确认的批次须先核验再放行恢复服务。 */
    public Inventory inventory(String continuation) {
        if (continuation != null && continuation.length() > 4096) throw mismatch();
        var prepared = objects.inventory(preparedPrefix(), continuation, INVENTORY_PAGE_SIZE);
        var committed = new ArrayList<Reference>();
        var unresolved = new ArrayList<String>();
        for (String objectKey : prepared.keys()) {
            if (!objectKey.startsWith(preparedPrefix()) || !objectKey.endsWith(".json")) throw mismatch();
            String id = objectKey.substring(preparedPrefix().length(), objectKey.length() - 5);
            requireBatchId(id);
            String committedKey = key(id);
            var visible = objects.inventory(committedKey, null, 1);
            if (!visible.keys().contains(committedKey)) {
                unresolved.add(id);
            } else {
                var version = objects.currentVersion(committedKey);
                if (version == null) throw mismatch();
                var reference = new Reference(id, version.version(), version.byteCount(), version.sha256());
                requireReference(reference);
                committed.add(reference);
            }
        }
        return new Inventory(List.copyOf(committed), List.copyOf(unresolved), prepared.continuation());
    }

    /** 只导出已在同一数据库事务提交的回执及其原版本成员；凭证不复制审计原文。 */
    public Reference publish(String batchId) {
        requireBatchId(batchId);
        Batch batch = archive.batch(batchId);
        if (batch == null || !sameScope(batch) || !ONLINE_PURGED.equals(batch.state())) throw mismatch();
        var sealed = recovery.reference(batchId);
        if (sealed != null) return sealed;

        var receipt = recovery.receipt(batchId);
        var members = verification.archivedMembers(batch).stream().map(MemberProof::from)
                .sorted(Comparator.comparingLong(MemberProof::attemptId)).toList();
        var document = new Journal(FORMAT, batch, receipt, members);
        validate(document, batchId);
        prepare(batch);
        var reference = writeOnce(batchId, key(batchId), JsonCodec.write(document));
        recovery.sealReference(reference);
        if (!reference.equals(recovery.reference(batchId))) throw mismatch();
        return reference;
    }

    private Reference writeOnce(String batchId, String key, String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw mismatch();
        String sha256 = AuditArchiveVerification.sha256(bytes);
        var reference = new Reference(batchId, objects.putOnce(key, bytes, sha256), bytes.length, sha256);
        requireReference(reference);
        if (!Arrays.equals(bytes, objects.readForVerification(key, reference.version(), reference.byteCount()))) throw mismatch();
        return reference;
    }

    /** 只在隔离恢复库消费明确提供的版本。对象请求在数据库事务开始前完成。 */
    public ReplayResult replay(Reference reference) {
        requireReference(reference);
        var bytes = objects.readForRestore(key(reference.batchId()), reference.version(), reference.byteCount());
        if (bytes.length != reference.byteCount() || !AuditArchiveVerification.sha256(bytes).equals(reference.sha256())) throw mismatch();
        Journal document = JsonCodec.read(new String(bytes, StandardCharsets.UTF_8), Journal.class);
        validate(document, reference.batchId());
        return transaction.execute(status -> apply(document, reference));
    }

    private ReplayResult apply(Journal document, Reference reference) {
        var expected = document.batch();
        String id = expected.batchId();
        var ids = document.members().stream().map(MemberProof::attemptId).toList();
        var locked = cutover.lockBatch(id);
        cutover.lockHolds(scope);
        var existingIds = recovery.lockExistingAttempts(ids);
        if (locked == null) {
            if (archive.insertBatch(expected) != 1 || archive.insertMembers(id,
                    ids.stream().map(memberId -> new Member(memberId, null, null, null)).toList()) != ids.size()) throw mismatch();
        } else if (!compatible(locked, expected) || !ids.equals(recovery.memberIds(id))) {
            throw mismatch();
        }
        var committed = recovery.receipt(id);
        if (committed != null) {
            if (!document.receipt().equals(committed) || !existingIds.isEmpty() || recovery.hasOnlineChildren(id)
                    || !ONLINE_PURGED.equals(locked.state())) throw mismatch();
            // 备份可能已经包含在线切换事务，但早于外部凭证回写；允许补上相同的精确版本。
            if (recovery.reference(id) == null) recovery.sealReference(reference);
            if (!reference.equals(recovery.reference(id))) throw mismatch();
            return new ReplayResult(id, 0, true);
        }
        if (locked != null && ONLINE_PURGED.equals(locked.state())) throw mismatch();
        // 恢复库中更早的冻结或仍活跃命令也需要独立校正，防止补放时丢失本地证据。
        if (archive.activeHold(id) || cutover.hasActiveCommand(id)) throw mismatch();
        var rows = archive.snapshot(id);
        var byId = document.members().stream().collect(Collectors.toUnmodifiableMap(MemberProof::attemptId, Function.identity()));
        if (rows.size() != existingIds.size() || rows.stream().anyMatch(row -> !byId.get(row.attemptId()).matches(row))) throw mismatch();

        var effects = document.members().stream().filter(proof -> proof.effectId() != null).toList();
        if (!effects.isEmpty() && recovery.storeRecoveredLocators(id, effects) != effects.size()) throw mismatch();
        // 删除数量若超过实际比对出的子记录，意味着存在孤儿或后来出现的事实，整批回滚。
        if (cutover.removeOutcomes(id) != rows.stream().filter(row -> row.outcome() != null).count()
                || cutover.removeEffects(id) != rows.stream().filter(row -> row.effect() != null).count()
                || cutover.removeAttempts(id) != rows.size()) throw mismatch();
        if (cutover.storeReceipt(document.receipt()) != 1 || recovery.sealReference(reference) != 1
                || (locked != null && recovery.markRecovered(id, expected.objectVersion()) != 1)) throw mismatch();
        return new ReplayResult(id, rows.size(), false);
    }

    private boolean sameScope(Batch batch) {
        return scope.type().equals(batch.scopeType()) && scope.tenant().equals(batch.tenantId());
    }

    private static boolean compatible(Batch old, Batch current) {
        return old.batchId().equals(current.batchId()) && old.tenantId().equals(current.tenantId())
                && old.scopeType().equals(current.scopeType()) && old.olderThan().equals(current.olderThan())
                && old.objectKey().equals(current.objectKey()) && old.sha256().equals(current.sha256())
                && (old.objectVersion() == null || old.objectVersion().equals(current.objectVersion()))
                && old.rowCount() == current.rowCount() && old.payloadBytes() == current.payloadBytes();
    }

    private void validate(Journal journal, String id) {
        if (journal == null || journal.batch() == null || journal.receipt() == null || journal.members() == null) throw mismatch();
        var batch = journal.batch();
        var receipt = journal.receipt();
        var members = journal.members();
        if (journal.format() != FORMAT || !sameScope(batch) || !ONLINE_PURGED.equals(batch.state())
                || !id.equals(batch.batchId()) || !id.equals(receipt.batchId())
                || !("commerce/operation-audit/v1/" + id + ".ndjson.gz").equals(batch.objectKey())
                || !Objects.equals(batch.objectVersion(), receipt.objectVersion())
                || !Objects.equals(batch.sha256(), receipt.sha256()) || !validHash(batch.sha256())
                || members.isEmpty() || members.size() > MAX_ROWS || members.size() != batch.rowCount()
                || members.size() != receipt.attemptCount()
                || members.stream().map(MemberProof::attemptId).distinct().count() != members.size()
                || members.stream().anyMatch(member -> member.attemptId() <= 0 || !validHash(member.attemptSha256())
                        || member.effectId() != null && (member.effectId() <= 0 || !validHash(member.effectSha256())))
                || members.stream().filter(member -> member.effectId() != null).count() != receipt.effectCount()
                || members.stream().filter(member -> member.outcomeSha256() != null).count() != receipt.outcomeCount()) {
            throw mismatch();
        }
    }

    private String key(String batchId) {
        return scopePrefix() + "committed/" + batchId + ".json";
    }

    private String preparedPrefix() {
        return scopePrefix() + "prepared/";
    }

    private String scopePrefix() {
        return JOURNAL_PREFIX + JsonCodec.hash(scope.type() + ":" + scope.tenant()) + "/";
    }

    private static void requireReference(Reference reference) {
        requireBatchId(reference.batchId());
        if (reference.version() == null || reference.version().isBlank() || "null".equals(reference.version()) || reference.version().length() > 255
                || reference.byteCount() <= 0 || reference.byteCount() > MAX_BYTES || !validHash(reference.sha256())) {
            throw new DomainException(DomainException.Code.INVALID_INPUT, "清理恢复凭证引用无效");
        }
    }

    private static void requireBatchId(String id) {
        if (id == null || !id.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")) {
            throw new DomainException(DomainException.Code.INVALID_INPUT, "归档批次编号无效");
        }
    }

    private static boolean validHash(String hash) {
        return hash != null && hash.matches("[0-9a-f]{64}");
    }

    private static DomainException mismatch() {
        return new DomainException(DomainException.Code.CONFLICT, "清理恢复凭证与本地证据不一致");
    }

    /** 控制面固定采用对象精确版本及字节摘要定位，不从业务请求接受任意对象键。 */
    public record Reference(String batchId, String version, int byteCount, String sha256) {}

    /** 页结束与整次清点放行独立，翻完所有页并解决所有差异才满足恢复条件。 */
    public record Inventory(List<Reference> committed, List<String> unresolved, String continuation) {}

    public record ReplayResult(String batchId, int removedAttempts, boolean alreadyApplied) {}

    private record Prepared(int format, String batchId, String archiveVersion, String archiveSha256) {}

    record Journal(int format, Batch batch, Receipt receipt, List<MemberProof> members) {}

    record MemberProof(long attemptId, String attemptSha256, String effectSha256, String outcomeSha256,
            Long effectId, String actionCode) {
        static MemberProof from(Member row) {
            var effect = row.effect() == null ? null : JsonCodec.read(row.effect(), JsonNode.class);
            return new MemberProof(row.attemptId(), JsonCodec.hash(row.attempt()),
                    digest(row.effect()), digest(row.outcome()), effect == null ? null : effect.path("id").asLong(),
                    effect == null || effect.path("action").isNull() ? null : effect.path("action").asString());
        }

        /** 更早的备份可以缺少子证据；只要子证据仍在，就必须与已提交切换的原内容相同。 */
        boolean matches(Member row) {
            return attemptSha256.equals(JsonCodec.hash(row.attempt()))
                    && (row.effect() == null || Objects.equals(effectSha256, digest(row.effect())))
                    && (row.outcome() == null || Objects.equals(outcomeSha256, digest(row.outcome())));
        }

        private static String digest(String json) {
            return json == null ? null : JsonCodec.hash(json);
        }
    }
}
