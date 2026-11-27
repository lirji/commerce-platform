package com.lrj.commerce.runtime.audit.archive;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.audit.OperationAudit;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Batch;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Member;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Restored;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Scope;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 验证模式按固定批次生成对象并回读，快照恢复写入有独立访问期限的查询区。 */
public class AuditArchiveVerification {
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_ROWS = 200;
    private static final int MAX_COMPRESSED = 8 * 1024 * 1024;
    private static final int MAX_EXPANDED = 32 * 1024 * 1024;
    private static final int PAGE_SIZE = 50;

    private final AuditArchiveMapper mapper;
    private final ArchiveObjectStore objects;
    private final OperationAudit audit;
    private final Scope scope;
    private final Duration minimumAge;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final Semaphore worker = new Semaphore(1);
    private final Semaphore restores = new Semaphore(2);

    public AuditArchiveVerification(AuditArchiveMapper mapper, ArchiveObjectStore objects, OperationAudit audit,
            Scope scope, Duration minimumAge, Clock clock, PlatformTransactionManager txManager) {
        if (!List.of("PLATFORM", "TENANT").contains(scope.type()) || scope.tenant().isBlank()
                || scope.tenant().length() > 64 || minimumAge.isNegative() || minimumAge.isZero()) {
            throw new IllegalArgumentException("档案验证必须限定可信租户、授权类型和正数在线时间");
        }
        this.mapper = mapper;
        this.objects = objects;
        this.audit = audit;
        this.scope = scope;
        this.minimumAge = minimumAge;
        this.clock = clock;
        this.transaction = new TransactionTemplate(txManager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
    }

    /** 每轮至多推进一个批次，存储故障可从原批次重试，不进入业务数据的删除路径。 */
    public String tick() {
        if (!worker.tryAcquire()) return null;
        try {
            Batch batch = reserve();
            if (batch == null) return null;
            byte[] expected = encode(batch.batchId(), mapper.snapshot(batch.batchId()));
            verifySizeAndHash(batch, expected);
            if (State.CLAIMED.name().equals(batch.state())) {
                String version = objects.putOnce(batch.objectKey(), expected, batch.sha256());
                if (version == null || version.isBlank() || version.equals("null") || version.length() > 255)
                    throw bad("档案桶未提供版本编号");
                mapper.sealUpload(batch.batchId(), version);
                batch = mapper.batch(batch.batchId());
            }
            byte[] actual = objects.readForVerification(batch.objectKey(), batch.objectVersion(), batch.payloadBytes());
            Document restored = decode(batch, actual);
            if (!Arrays.equals(expected, actual) || !restored.rows().equals(mapper.snapshot(batch.batchId()))) {
                throw bad("复读数据或在线审计范围已经变化");
            }
            mapper.verified(batch.batchId(), batch.objectVersion());
            if (!State.VERIFIED.name().equals(mapper.batch(batch.batchId()).state())) throw bad("档案批次状态冲突");
            return batch.batchId();
        } finally {
            worker.release();
        }
    }

    /** 恢复取证必须验证当前人员、工单时效与已验证的不可变对象，二次复核后才写恢复区。 */
    public int restore(Actor actor, String ticket, String traceId) {
        requireOperator(actor, ticket);
        var grant = grant(actor, ticket, false);
        return audit.archiveRestoreRead(actor, grant.ticketId(), traceId, true, () -> {
            if (!restores.tryAcquire()) throw bad("档案恢复繁忙");
            try {
                var batch = mapper.batch(grant.batchId());
                var content = decode(batch, objects.readForRestore(batch.objectKey(), batch.objectVersion(), batch.payloadBytes()));
                return transaction.execute(status -> {
                    if (!grant.equals(grant(actor, ticket, true))) throw bad("恢复授权已经变化");
                    var rows = content.rows().stream().map(row -> new Restored(row.attemptId(), JsonCodec.write(row), null)).toList();
                    mapper.restore(grant, rows);
                    return rows.size();
                });
            } finally {
                restores.release();
            }
        });
    }

    /** 只查当前有效工单的恢复区，返回顺序稳定的有界游标。 */
    public RestorePage restored(Actor actor, String ticket, long after, String traceId) {
        requireOperator(actor, ticket);
        if (after < 0) throw new DomainException(DomainException.Code.INVALID_INPUT, "恢复列表游标无效");
        var approved = grant(actor, ticket, false);
        return audit.archiveRestoreRead(actor, approved.ticketId(), traceId, false, () -> {
            var rows = mapper.restoredPage(ticket, actor.actorId(), actor.tenantId(), scopeFor(actor), after, PAGE_SIZE + 1);
            if (!approved.equals(grant(actor, ticket, false))) throw bad("恢复授权已经变化");
            var items = rows.stream().limit(PAGE_SIZE).toList();
            return new RestorePage(items, rows.size() > PAGE_SIZE ? Long.toString(items.getLast().attemptId()) : null);
        });
    }

    /** 清理预检重新读取精确对象版本，并与当下仍在线的快照比对。 */
    List<Member> confirmedSnapshot(Batch batch) {
        var rows = decode(batch, objects.readForVerification(batch.objectKey(), batch.objectVersion(), batch.payloadBytes())).rows();
        if (!rows.equals(mapper.snapshot(batch.batchId()))) throw bad("档案与当前在线数据不一致");
        return rows;
    }

    private Batch reserve() {
        try {
            return transaction.execute(status -> {
                var existing = mapper.pending(scope);
                if (existing != null) return existing;
                var cutoff = clock.instant().minus(minimumAge);
                var rows = mapper.candidates(scope, cutoff, MAX_ROWS);
                if (rows.isEmpty()) return null;
                String id = UUID.randomUUID().toString();
                byte[] bytes = encode(id, rows);
                var batch = new Batch(id, scope.type(), scope.tenant(), State.CLAIMED.name(), cutoff,
                        "commerce/operation-audit/v1/" + id + ".ndjson.gz", null, sha256(bytes), rows.size(), bytes.length);
                mapper.insertBatch(batch);
                if (mapper.insertMembers(id, rows) != rows.size()) throw bad("未能完整冻结批次成员");
                return batch;
            });
        } catch (DuplicateKeyException competingBatch) {
            return mapper.pending(scope);
        }
    }

    private AuditArchiveMapper.RestoreGrant grant(Actor actor, String ticket, boolean lock) {
        var grant = mapper.activeRestore(ticket, actor.actorId(), actor.tenantId(), scopeFor(actor), lock);
        if (grant == null) throw new DomainException(DomainException.Code.FORBIDDEN, "恢复工单无效、过期或已撤销");
        return grant;
    }

    private static String scopeFor(Actor actor) {
        return actor.role() == Actor.Role.PLATFORM_OPERATOR ? "PLATFORM" : "TENANT";
    }

    private static void requireOperator(Actor actor, String ticket) {
        if (actor.role() != Actor.Role.PLATFORM_OPERATOR && actor.role() != Actor.Role.ADMIN) {
            throw new DomainException(DomainException.Code.FORBIDDEN, "当前人员不能查询归档恢复区");
        }
        if (ticket == null || !ticket.matches("[A-Za-z0-9_.:-]{1,64}"))
            throw new DomainException(DomainException.Code.INVALID_INPUT, "恢复工单编号无效");
    }

    private static byte[] encode(String batchId, List<Member> rows) {
        var payload = new StringBuilder(JsonCodec.write(new Header(FORMAT_VERSION, batchId)));
        for (var row : rows) payload.append('\n').append(JsonCodec.write(row));
        payload.append('\n');
        var bytes = payload.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EXPANDED) throw bad("审计档案超出单批展开预算");
        try (var output = new ByteArrayOutputStream()) {
            try (var zip = new GZIPOutputStream(output)) {
                zip.write(bytes);
            }
            if (output.size() > MAX_COMPRESSED) throw bad("审计档案超出单批对象预算");
            return output.toByteArray();
        } catch (IOException invalid) {
            throw bad("无法生成压缩审计档案");
        }
    }

    private static Document decode(Batch batch, byte[] bytes) {
        verifySizeAndHash(batch, bytes);
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            var expanded = gzip.readNBytes(MAX_EXPANDED + 1);
            if (expanded.length > MAX_EXPANDED) throw bad("恢复内容超过展开预算");
            var lines = new String(expanded, StandardCharsets.UTF_8).lines().limit(MAX_ROWS + 2L).toList();
            if (lines.size() != batch.rowCount() + 1) throw bad("档案记录数不一致");
            var header = JsonCodec.read(lines.getFirst(), Header.class);
            var rows = lines.stream().skip(1).map(line -> JsonCodec.read(line, Member.class)).toList();
            if (header.schemaVersion() != FORMAT_VERSION || !batch.batchId().equals(header.batchId())
                    || rows.stream().map(Member::attemptId).distinct().count() != batch.rowCount()) {
                throw bad("对象格式、记录数或批次关联校验失败");
            }
            return new Document(rows);
        } catch (IOException invalid) {
            throw bad("无法解压审计档案");
        }
    }

    private static void verifySizeAndHash(Batch batch, byte[] bytes) {
        if (bytes.length > MAX_COMPRESSED || bytes.length != batch.payloadBytes() || !sha256(bytes).equals(batch.sha256())) {
            throw bad("审计档案对象字节数或摘要不匹配");
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static DomainException bad(String reason) {
        return new DomainException(DomainException.Code.UNAVAILABLE, reason);
    }

    private enum State { CLAIMED, UPLOADED, VERIFIED }

    public record Header(int schemaVersion, String batchId) {}

    private record Document(List<Member> rows) {}

    public record RestorePage(List<Restored> items, String nextCursor) {}
}
