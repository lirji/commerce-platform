package com.lrj.commerce.runtime.audit.archive;

import com.lrj.commerce.runtime.audit.archive.ArchiveOnlineCutoverMapper.Receipt;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper.Scope;
import java.util.Objects;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 持有独立签收的单批在线切换，恢复原档案所需的版本和定位保留。 */
public class AuditArchiveOnlineCutover {
    private final AuditArchiveRetentionReview review;
    private final AuditArchiveMapper archive;
    private final ArchiveOnlineCutoverMapper cutover;
    private final TransactionTemplate transaction;
    private final AuditArchivePurgeRecovery recovery;

    public AuditArchiveOnlineCutover(AuditArchiveRetentionReview review, AuditArchiveMapper archive,
            ArchiveOnlineCutoverMapper cutover, AuditArchivePurgeRecovery recovery, PlatformTransactionManager manager) {
        this.review = review;
        this.archive = archive;
        this.cutover = cutover;
        this.recovery = recovery;
        this.transaction = new TransactionTemplate(manager);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(5);
    }

    /** 先回读对象，随后进入只执行本库检查和修改的短事务。 */
    public Outcome move(String batchId) {
        var proof = review.proof(batchId);
        if ("ONLINE_PURGED".equals(proof.batch().state())) return Outcome.ALREADY_MOVED;
        if (proof.review().reason() != AuditArchiveRetentionReview.Reason.VERIFIED_FOR_REVIEW) return Outcome.INELIGIBLE;
        var scope = new Scope(proof.batch().scopeType(), proof.batch().tenantId());
        if (cutover.lockGrant(batchId) == null) return Outcome.NOT_APPROVED;
        if (cutover.hasActiveCommand(batchId)) return Outcome.COMMAND_ACTIVE;
        // 外部准备标记先于数据库删除。若事务结果因灾难不可知，隔离恢复可以发现待核验的批次。
        recovery.prepare(proof.batch());
        return transaction.execute(status -> {
            var current = cutover.lockBatch(batchId);
            if (current != null && "ONLINE_PURGED".equals(current.state())) return Outcome.ALREADY_MOVED;
            if (!proof.batch().equals(current)) return Outcome.CHANGED;
            var grant = cutover.lockGrant(batchId);
            if (grant == null) return Outcome.NOT_APPROVED;
            // 范围索引上的当前读保护新冻结及解除，直到同一事务提交。
            cutover.lockHolds(scope);
            if (!proof.policies().equals(cutover.lockPolicies(scope))) return Outcome.CHANGED;
            if (cutover.lockAttempts(batchId).size() != current.rowCount() || archive.activeHold(batchId)
                    || !proof.members().equals(archive.snapshot(batchId))) return Outcome.CHANGED;
            if (cutover.hasActiveCommand(batchId)) return Outcome.COMMAND_ACTIVE;
            int effects = cutover.storeLocators(batchId);
            int outcomes = cutover.removeOutcomes(batchId);
            if (cutover.removeEffects(batchId) != effects || cutover.removeAttempts(batchId) != current.rowCount()
                    || outcomes != proof.members().stream().map(AuditArchiveMapper.Member::outcome).filter(Objects::nonNull).count()) {
                throw new IllegalStateException("审计在线切换数据条数不符，已回滚本批");
            }
            var receipt = new Receipt(batchId, current.objectVersion(), current.sha256(), grant.approvalReference(),
                    current.rowCount(), effects, outcomes);
            if (cutover.storeReceipt(receipt) != 1 || cutover.markPurged(batchId) != 1) {
                throw new IllegalStateException("审计在线切换回执未可靠提交");
            }
            return Outcome.MOVED;
        });
    }

    public enum Outcome { MOVED, ALREADY_MOVED, NOT_APPROVED, INELIGIBLE, CHANGED, COMMAND_ACTIVE }
}
