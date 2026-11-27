package com.lrj.commerce.runtime.audit.archive;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Batch;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Member;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Scope;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import tools.jackson.databind.JsonNode;

/** 生命周期清理的只读预检，使用源策略版本和最近确认的事实时间。 */
public class AuditArchiveRetentionReview {
    private final AuditArchiveMapper mapper;
    private final AuditArchiveVerification archive;
    private final Scope scope;
    private final Clock clock;

    public AuditArchiveRetentionReview(AuditArchiveMapper mapper, AuditArchiveVerification archive, Scope scope, Clock clock) {
        this.mapper = mapper;
        this.archive = archive;
        this.scope = scope;
        this.clock = clock;
    }

    /** 两次复核清单归属、批准策略及冻结，让慢速对象存储不会掩盖复核期间的变化。 */
    public Review assess(String batchId) {
        if (batchId == null || !batchId.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}")) {
            throw new DomainException(DomainException.Code.INVALID_INPUT, "归档清单编号无效");
        }
        Batch batch = mapper.batch(batchId);
        if (batch == null || !scope.type().equals(batch.scopeType()) || !scope.tenant().equals(batch.tenantId())) {
            throw new DomainException(DomainException.Code.NOT_FOUND, "当前配置范围内没有此归档清单");
        }
        if (!"VERIFIED".equals(batch.state())) return Review.blocked(batchId, Reason.NOT_VERIFIED);
        if (mapper.activeHold(batchId)) return Review.blocked(batchId, Reason.HELD);
        List<Policy> policies = mapper.policies(scope);
        var signed = policies.stream().collect(Collectors.toUnmodifiableMap(Policy::key, Function.identity()));
        if (signed.isEmpty()) return Review.blocked(batchId, Reason.NO_POLICY);
        List<Member> verified = archive.confirmedSnapshot(batch);
        if (mapper.activeHold(batchId)) return Review.blocked(batchId, Reason.HELD);
        if (!policies.equals(mapper.policies(scope))) return Review.blocked(batchId, Reason.POLICY_CHANGED);
        return periods(batchId, verified, signed);
    }

    private Review periods(String batchId, List<Member> members, Map<Key, Policy> policies) {
        List<Instant> online = new ArrayList<>();
        List<Instant> archival = new ArrayList<>();
        for (var row : members) {
            var original = JsonCodec.read(row.attempt(), JsonNode.class);
            var policy = policies.get(new Key(original.path("policy").asString(), original.path("policyVersion").asInt()));
            if (policy == null) return Review.blocked(batchId, Reason.NO_POLICY);
            Instant latest = occurrence(row);
            if (latest == null) return Review.blocked(batchId, Reason.INCOMPLETE_FACT);
            online.add(latest.plus(policy.onlineDays(), ChronoUnit.DAYS));
            archival.add(latest.plus(policy.totalDays(), ChronoUnit.DAYS));
        }
        Instant onlineAfter = online.stream().max(Instant::compareTo).orElseThrow();
        Instant archiveAfter = archival.stream().max(Instant::compareTo).orElseThrow();
        Instant now = clock.instant();
        return new Review(batchId, now.isBefore(onlineAfter) ? Reason.STILL_ONLINE : Reason.VERIFIED_FOR_REVIEW,
                onlineAfter, archiveAfter);
    }

    private static Instant occurrence(Member row) {
        var outcome = row.outcome() == null ? null : JsonCodec.read(row.outcome(), JsonNode.class);
        if (outcome != null && "UNKNOWN".equals(outcome.path("result").asString()) && row.effect() == null) return null;
        Instant attempt = eventTime(row.attempt(), "occurredAt");
        Instant successful = eventTime(row.effect(), "recordedAt");
        Instant terminal = eventTime(row.outcome(), "recordedAt");
        if (attempt == null || (row.effect() != null && successful == null) || (row.outcome() != null && terminal == null)) return null;
        return java.util.stream.Stream.of(attempt, successful, terminal).filter(Objects::nonNull)
                .max(Instant::compareTo).orElseThrow();
    }

    private static Instant eventTime(String json, String field) {
        if (json == null) return null;
        var value = JsonCodec.read(json, JsonNode.class).path(field);
        return value.isString() ? Instant.parse(value.asString()) : null;
    }

    /** 预检通过仅供下一步复核，结果会随冻结、策略签收和当前对象版本变化。 */
    public enum Reason { NOT_VERIFIED, HELD, NO_POLICY, POLICY_CHANGED, INCOMPLETE_FACT, STILL_ONLINE, VERIFIED_FOR_REVIEW }

    public record Key(String code, int version) {}

    public record Policy(String policyCode, int policyVersion, int onlineDays, int totalDays, String approvalReference) {
        Key key() { return new Key(policyCode, policyVersion); }
    }

    public record Review(String batchId, Reason reason, Instant onlineAfter, Instant archiveAfter) {
        static Review blocked(String id, Reason reason) { return new Review(id, reason, null, null); }
    }
}
