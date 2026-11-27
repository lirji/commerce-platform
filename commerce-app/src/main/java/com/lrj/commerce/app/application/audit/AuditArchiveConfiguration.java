package com.lrj.commerce.app.application.audit;

import com.lrj.commerce.runtime.audit.OperationAudit;
import com.lrj.commerce.runtime.audit.archive.ArchiveOnlineCutoverMapper;
import com.lrj.commerce.runtime.audit.archive.ArchivePurgeRecoveryMapper;
import com.lrj.commerce.runtime.audit.archive.AuditArchivePurgeRecovery;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveOnlineCutover;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveRetentionReview;
import com.xxl.job.core.context.XxlJobHelper;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveVerification;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import com.xxl.job.core.handler.annotation.XxlJob;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/** 独立验证调度、对象请求预算及凭据；业务启动默认不启用此链路。 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "commerce.audit-archive.enabled", havingValue = "true")
public class AuditArchiveConfiguration {
    @Bean
    S3AuditArchiveObjects archiveObjects(@Value("${commerce.audit-archive.endpoint}") URI endpoint,
            @Value("${commerce.audit-archive.region}") String region,
            @Value("${commerce.audit-archive.bucket}") String bucket,
            @Value("${commerce.audit-archive.write-key}") String writeKey,
            @Value("${commerce.audit-archive.write-secret}") String writeSecret,
            @Value("${commerce.audit-archive.restore-key}") String restoreKey,
            @Value("${commerce.audit-archive.restore-secret}") String restoreSecret) {
        if (writeKey.isBlank() || restoreKey.isBlank() || writeKey.equals(restoreKey) || writeSecret.isBlank()
                || restoreSecret.isBlank() || writeSecret.equals(restoreSecret) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null
                || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("审计档案需要固定桶及不同用途的两套凭据");
        }
        return new S3AuditArchiveObjects(s3(endpoint, region, writeKey, writeSecret),
                s3(endpoint, region, restoreKey, restoreSecret), bucket);
    }

    private static S3Client s3(URI endpoint, String region, String key, String secret) {
        return S3Client.builder().endpointOverride(endpoint).region(Region.of(region)).forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(key, secret)))
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .overrideConfiguration(config -> config.apiCallTimeout(Duration.ofSeconds(8))
                        .apiCallAttemptTimeout(Duration.ofSeconds(2))).build();
    }

    @Bean
    AuditArchiveVerification auditArchiveVerification(AuditArchiveMapper archive, S3AuditArchiveObjects objects,
            OperationAudit audit, Clock clock, PlatformTransactionManager tx,
            @Value("${commerce.audit-archive.scope}") String scope,
            @Value("${commerce.audit-archive.tenant}") String tenant,
            @Value("${commerce.audit-archive.minimum-age}") Duration age) {
        return new AuditArchiveVerification(archive, objects, audit, new AuditArchiveMapper.Scope(scope, tenant), age, clock, tx);
    }

    @Bean
    @ConditionalOnProperty(name = "commerce.audit-archive.scheduler-enabled", havingValue = "true")
    XxlJobSpringExecutor auditArchiveExecutor(@Value("${commerce.audit-archive.scheduler-admin}") String admin,
            @Value("${commerce.audit-archive.scheduler-token}") String token,
            @Value("${commerce.audit-archive.scheduler-port}") int port) {
        if (admin.isBlank() || token.isBlank() || port < 1024 || port > 65535) {
            throw new IllegalArgumentException("归档执行器需要独立调度地址、凭据和端口");
        }
        var executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(admin);
        executor.setAccessToken(token);
        executor.setAppname("commerce-audit-archive");
        executor.setPort(port);
        return executor;
    }

    @Bean
    AuditArchiveJob auditArchiveJob(AuditArchiveVerification verification, AuditArchiveMapper mapper,
            ArchiveOnlineCutoverMapper cutoverMapper, ArchivePurgeRecoveryMapper recoveryMapper, S3AuditArchiveObjects objects,
            PlatformTransactionManager manager, Clock clock,
            @Value("${commerce.audit-archive.online-cutover-enabled}") boolean cutoverEnabled,
            @Value("${commerce.audit-archive.recovery-replay-enabled}") boolean replayEnabled,
            @Value("${commerce.audit-archive.scope}") String scope,
            @Value("${commerce.audit-archive.tenant}") String tenant) {
        if (cutoverEnabled && replayEnabled) {
            throw new IllegalArgumentException("归档原库切换与隔离恢复补放必须使用独立应用配置");
        }
        var authorized = new AuditArchiveMapper.Scope(scope, tenant);
        var review = new AuditArchiveRetentionReview(mapper, verification, authorized, clock);
        var recovery = new AuditArchivePurgeRecovery(authorized, mapper, cutoverMapper, recoveryMapper, verification, objects, manager);
        var cutover = cutoverEnabled ? new AuditArchiveOnlineCutover(review, mapper, cutoverMapper, recovery, manager) : null;
        return new AuditArchiveJob(verification, review, cutover, recovery, replayEnabled);
    }

    /** 由独立执行器处理；每轮最多一个批次，不从作业参数接受租户或截止时间。 */
    public record AuditArchiveJob(AuditArchiveVerification verification, AuditArchiveRetentionReview retention,
            AuditArchiveOnlineCutover cutover, AuditArchivePurgeRecovery recovery, boolean replayEnabled) {
        @XxlJob("commerceAuditArchiveVerify")
        public void verify() {
            verification.tick();
        }

        /** 在签收配置的范围内复核一个指定批次，无删除副作用。 */
        @XxlJob("commerceAuditArchiveRetentionReview")
        public void checkRetention() {
            var result = retention.assess(XxlJobHelper.getJobParam());
            XxlJobHelper.log("批次 {} 复核结果 {}，最早在线清理 {}，最早档案到期 {}",
                    result.batchId(), result.reason(), result.onlineAfter(), result.archiveAfter());
        }

        /** 唯一允许移除在线源记录的任务，仍须独立开关和该批次签收。 */
        @XxlJob("commerceAuditArchiveOnlineCutover")
        public void cutoverOnline() {
            if (cutover == null) throw new IllegalStateException("在线审计档案切换未启用");
            String batchId = XxlJobHelper.getJobParam();
            var outcome = cutover.move(batchId);
            XxlJobHelper.log("在线切换结果 {}", outcome);
            if (outcome == AuditArchiveOnlineCutover.Outcome.MOVED || outcome == AuditArchiveOnlineCutover.Outcome.ALREADY_MOVED) {
                XxlJobHelper.log("已核验外部恢复凭证 {}", JsonCodec.write(recovery.publish(batchId)));
            }
        }

        /** 一次扫描一条未发回执，补上进程在数据库提交后中断的窗口。 */
        @XxlJob("commerceAuditArchivePublishRecovery")
        public void publishRecovery() {
            var reference = recovery.publishNext();
            if (reference != null) XxlJobHelper.log("已核验外部恢复凭证 {}", JsonCodec.write(reference));
        }

        /** 独立恢复配置查阅外部清理凭证，处理游标直到备份范围完整。 */
        @XxlJob("commerceAuditArchiveRecoveryInventory")
        public void recoveryInventory() {
            if (!replayEnabled) throw new IllegalStateException("清理凭证清点未启用");
            var cursor = XxlJobHelper.getJobParam();
            var page = recovery.inventory(cursor == null || cursor.isBlank() ? null : cursor);
            XxlJobHelper.log("外部恢复凭证清点 {}", JsonCodec.write(page));
            if (!page.unresolved().isEmpty()) XxlJobHelper.handleFail("仍有清理准备记录等待核对");
        }

        /** 限于隔离恢复。凭证引用由已核验的恢复清单给出，业务入口无法调用。 */
        @XxlJob("commerceAuditArchiveReplayRecovery")
        public void replayRecovery() {
            if (!replayEnabled) throw new IllegalStateException("清理凭证补放未启用");
            var reference = JsonCodec.read(XxlJobHelper.getJobParam(), AuditArchivePurgeRecovery.Reference.class);
            XxlJobHelper.log("清理凭证补放结果 {}", recovery.replay(reference));
        }
    }
}
