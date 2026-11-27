ALTER TABLE audit_archive_batch
  DROP CHECK ck_audit_archive_state,
  ADD CONSTRAINT ck_audit_archive_state CHECK (state IN ('CLAIMED','UPLOADED','VERIFIED','ONLINE_PURGED','EXPIRED'));

CREATE TABLE audit_archive_expiry_grant (
  batch_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '批次独立销毁批准范围',
  approval_reference VARCHAR(96) NOT NULL COMMENT '销毁审批依据与验证记录编号',
  approved_by VARCHAR(64) NOT NULL COMMENT '独立销毁审批人',
  effective_at DATETIME(3) NOT NULL COMMENT '审批生效时间UTC',
  expires_at DATETIME(3) NOT NULL COMMENT '审批到期时间UTC',
  revoked_at DATETIME(3) NULL COMMENT '审批撤销时间UTC',
  PRIMARY KEY(batch_id),
  CONSTRAINT fk_audit_expiry_grant_batch FOREIGN KEY(batch_id) REFERENCES audit_archive_batch(batch_id),
  CONSTRAINT ck_audit_expiry_grant_time CHECK(expires_at>effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='与在线切换和恢复工单分离的对象到期销毁批准';

CREATE TABLE audit_archive_expiry_receipt (
  batch_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '到期销毁工单的稳定批次',
  archive_version VARCHAR(255) NOT NULL COMMENT '指定的原档案对象版本',
  archive_sha256 CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '原档案校验摘要',
  approval_reference VARCHAR(96) NOT NULL COMMENT '执行前核验通过的审批引用',
  policies_sha256 CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '授权保留策略集合的稳定摘要',
  resource_ids JSON NOT NULL COMMENT '原档案及晚到证据涉及的冻结资源引用',
  eligible_at DATETIME(3) NOT NULL COMMENT '按实际事实计算的最早销毁时间UTC',
  latest_late_id BIGINT NOT NULL COMMENT '进入销毁准备时最后关联追加流水',
  late_fingerprints JSON NOT NULL COMMENT '销毁确认前按晚到证据逐条固化的校验摘要',
  confirmed_at DATETIME(3) NULL COMMENT '已经复核指定版本不存在的时点',
  PRIMARY KEY(batch_id),
  CONSTRAINT fk_audit_expiry_receipt_batch FOREIGN KEY(batch_id) REFERENCES audit_archive_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='对象销毁意图与版本消失确认，允许外部回包丢失后续跑';


ALTER TABLE audit_archive_restored
  ADD KEY ix_audit_restored_batch (batch_id,ticket_id,attempt_id) COMMENT '按已过期批次有界清理临时恢复副本';
