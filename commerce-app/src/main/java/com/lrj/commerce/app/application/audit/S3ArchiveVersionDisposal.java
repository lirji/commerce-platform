package com.lrj.commerce.app.application.audit;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.audit.archive.ArchiveVersionDisposal;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 独立销毁身份仅访问原档案的精确版本，始终遵守对象存储的保全状态。 */
final class S3ArchiveVersionDisposal implements ArchiveVersionDisposal, AutoCloseable {
    private final S3Client client;
    private final String bucket;

    S3ArchiveVersionDisposal(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public boolean exists(String key, String version) {
        requireVersion(key, version);
        try {
            var response = client.headObject(builder -> builder.bucket(bucket).key(key).versionId(version));
            if (!version.equals(response.versionId())) throw unavailable();
            return true;
        } catch (S3Exception failure) {
            if (failure.statusCode() == 404) return false;
            throw unavailable();
        } catch (SdkException failure) {
            throw unavailable();
        }
    }

    @Override
    public void delete(String key, String version) {
        requireVersion(key, version);
        try {
            client.deleteObject(builder -> builder.bucket(bucket).key(key).versionId(version));
        } catch (SdkException failure) {
            throw unavailable();
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private static void requireVersion(String key, String version) {
        if (key == null || !key.matches("commerce/operation-audit/v1/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\.ndjson\\.gz")
                || version == null || version.isBlank() || "null".equals(version)) {
            throw new DomainException(DomainException.Code.INVALID_INPUT, "仅能处理归档原对象的明确版本");
        }
    }

    private static DomainException unavailable() {
        return new DomainException(DomainException.Code.UNAVAILABLE, "对象版本销毁暂无法确认");
    }
}
