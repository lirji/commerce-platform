ALTER TABLE audit_archive_online_purge_receipt
  ADD COLUMN recovery_version VARCHAR(255) NULL COMMENT '备份域之外已发布的清理恢复凭证对象版本',
  ADD COLUMN recovery_sha256 CHAR(64) CHARACTER SET ascii NULL COMMENT '清理恢复凭证字节的 SHA-256 摘要',
  ADD COLUMN recovery_bytes INT UNSIGNED NULL COMMENT '清理恢复凭证字节数，用于有界回读',
  ADD KEY ix_audit_purge_pending_export(recovery_version,applied_at,batch_id),
  ADD CONSTRAINT ck_audit_purge_recovery_ref CHECK (
    (recovery_version IS NULL AND recovery_sha256 IS NULL AND recovery_bytes IS NULL)
    OR (recovery_version IS NOT NULL AND recovery_sha256 IS NOT NULL AND recovery_bytes IS NOT NULL AND recovery_bytes>0)
  );
