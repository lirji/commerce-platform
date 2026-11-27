package com.lrj.commerce.runtime.audit.archive;

import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Scope;
import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 独立维护对象到期批准、清理意图和确认事实。 */
@Mapper
public interface ArchiveExpiryMapper {
    record Grant(String approvalReference) {}

    record Receipt(String batchId, String archiveVersion, String archiveSha256, String approvalReference,
            String policiesSha256, String resourceIds, Instant eligibleAt, long latestLateId, String lateFingerprints, Instant confirmedAt) {}

    record Late(long id, long attemptId, Instant recordedAt, String resourceId, String evidenceKey, String payload) {}

    Grant lockGrant(@Param("id") String id);

    Receipt receipt(@Param("id") String id);

    int reserve(Receipt receipt);

    List<Long> lockMatchingHolds(@Param("scope") Scope scope, @Param("resources") String resources);

    List<String> lockActiveRestoreGrants(@Param("id") String id);

    List<Late> latePage(@Param("id") String id, @Param("limit") int limit);

    int removeRestoreCopies(@Param("id") String id);

    int removeLateEvidence(@Param("id") String id, @Param("lastId") long lastId);

    int confirm(@Param("id") String id);

    int restoreConfirmation(@Param("id") String id, @Param("confirmedAt") Instant confirmedAt);

    int markExpired(@Param("id") String id);
}
