package com.lrj.commerce.runtime.audit.archive;

import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Batch;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Scope;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveRetentionReview.Policy;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 移除只匹配已验证的成员清单，锁与写入从统一事务调用。 */
@Mapper
public interface ArchiveOnlineCutoverMapper {
    record Grant(String approvalReference) {}

    record Receipt(String batchId, String objectVersion, String sha256, String approvalReference,
                   int attemptCount, int effectCount, int outcomeCount) {}

    Batch lockBatch(@Param("id") String id);

    Grant lockGrant(@Param("id") String id);

    List<Policy> lockPolicies(@Param("scope") Scope scope);

    List<Long> lockHolds(@Param("scope") Scope scope);

    List<Long> lockAttempts(@Param("id") String id);

    boolean hasActiveCommand(@Param("id") String id);

    int storeLocators(@Param("id") String id);

    int removeOutcomes(@Param("id") String id);

    int removeEffects(@Param("id") String id);

    int removeAttempts(@Param("id") String id);

    int storeReceipt(Receipt receipt);

    int markPurged(@Param("id") String id);
}
