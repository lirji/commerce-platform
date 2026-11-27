package com.lrj.commerce.app.application.audit;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.audit.archive.ArchiveObjectStore;
import java.io.IOException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** 归档对象固定键及版本，条件写避免发布进程重试时覆盖先前对象。 */
public class S3AuditArchiveObjects implements ArchiveObjectStore, AutoCloseable {
    private final S3Client archive;
    private final S3Client restore;
    private final String bucket;

    public S3AuditArchiveObjects(S3Client archive, S3Client restore, String bucket) {
        this.archive = archive;
        this.restore = restore;
        this.bucket = bucket;
    }

    @Override
    public String putOnce(String key, byte[] bytes, String sha256) {
        try {
            if (archive.getBucketVersioning(request -> request.bucket(bucket)).status() != BucketVersioningStatus.ENABLED) {
                throw unavailable("档案桶版本控制未启用");
            }
            try {
                return archive.putObject(request -> request.bucket(bucket).key(key).ifNoneMatch("*")
                        .contentType("application/gzip").metadata(java.util.Map.of("sha256", sha256)),
                        RequestBody.fromBytes(bytes)).versionId();
            } catch (S3Exception conflict) {
                if (conflict.statusCode() != 412) throw conflict;
                // 先前上传可能已经成功但未收到回包；必须再按此版本完整回读。
                var existing = archive.headObject(request -> request.bucket(bucket).key(key));
                if (!sha256.equals(existing.metadata().get("sha256"))) throw unavailable("已存在档案摘要与批次不符");
                return existing.versionId();
            }
        } catch (SdkException unavailable) {
            throw unavailable("暂时无法写入审计档案");
        }
    }

    @Override
    public byte[] readForVerification(String key, String version, int exactBytes) {
        return read(archive, key, version, exactBytes);
    }

    @Override
    public byte[] readForRestore(String key, String version, int exactBytes) {
        return read(restore, key, version, exactBytes);
    }

    private byte[] read(S3Client client, String key, String version, int exactBytes) {
        try (var input = client.getObject(request -> request.bucket(bucket).key(key).versionId(version))) {
            if (!version.equals(input.response().versionId()) || input.response().contentLength() != exactBytes) {
                throw unavailable("档案对象版本或长度不匹配");
            }
            return input.readNBytes(exactBytes + 1);
        } catch (SdkException | IOException unavailable) {
            throw unavailable("暂时无法回读指定版本的审计档案");
        }
    }

    @Override
    public void close() {
        archive.close();
        restore.close();
    }

    private static DomainException unavailable(String message) {
        return new DomainException(DomainException.Code.UNAVAILABLE, message);
    }
}
