package com.lrj.commerce.app.application.audit;

import com.lrj.commerce.runtime.audit.OperationAudit;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper;
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
    AuditArchiveJob auditArchiveJob(AuditArchiveVerification verification) {
        return new AuditArchiveJob(verification);
    }

    /** 由独立执行器处理；每轮最多一个批次，不从作业参数接受租户或截止时间。 */
    public record AuditArchiveJob(AuditArchiveVerification verification) {
        @XxlJob("commerceAuditArchiveVerify")
        public void verify() {
            verification.tick();
        }
    }
}
