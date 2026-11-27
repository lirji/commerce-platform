package com.lrj.commerce.runtime.audit.archive;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 清单和恢复区只访问选定批次，业务审计行依然只读。 */
@Mapper
public interface AuditArchiveMapper {
    record Scope(String type, String tenant) {}
    record Member(long attemptId, String attempt, String effect, String outcome) {}
    record Batch(String batchId, String scopeType, String tenantId, String state, Instant olderThan, String objectKey,
            String objectVersion, String sha256, int rowCount, int payloadBytes) {}
    record RestoreGrant(String ticketId, String batchId, String requestedBy, String approvedBy, Instant expiresAt) {}
    record Restored(long attemptId, String payload, String lateEvidence) {}

    record ScanCursor(Instant cutoff, Instant afterOccurredAt, long afterAttemptId) {}

    record ScanPosition(long attemptId, Instant occurredAt, boolean eligible) {}

    void openScan(@Param("scope") Scope scope, @Param("cutoff") Instant cutoff);

    ScanCursor lockScan(@Param("scope") Scope scope);

    List<ScanPosition> scanWindow(@Param("scope") Scope scope, @Param("cursor") ScanCursor cursor, @Param("limit") int limit);

    int advanceScan(@Param("scope") Scope scope, @Param("cursor") ScanCursor cursor);

    List<Member> candidates(@Param("scope") Scope scope, @Param("ids") List<Long> ids);

    Batch pending(@Param("scope") Scope scope);

    Batch batch(@Param("id") String id);

    List<AuditArchiveRetentionReview.Policy> policies(@Param("scope") Scope scope);

    boolean activeHold(@Param("id") String id);

    List<Member> snapshot(@Param("id") String id);

    int insertBatch(Batch batch);

    int insertMembers(@Param("id") String id, @Param("rows") List<Member> rows);

    int sealUpload(@Param("id") String id, @Param("version") String version);

    int verified(@Param("id") String id, @Param("version") String version);

    RestoreGrant activeRestore(@Param("ticket") String ticket, @Param("actorId") String actorId,
            @Param("tenantId") String tenantId, @Param("scopeType") String scopeType, @Param("lock") boolean lock);

    int restore(@Param("grant") RestoreGrant grant, @Param("rows") List<Restored> rows);

    List<Restored> restoredPage(@Param("ticket") String ticket, @Param("actorId") String actorId,
            @Param("tenantId") String tenantId, @Param("scopeType") String scopeType, @Param("after") long after, @Param("limit") int limit);
}
