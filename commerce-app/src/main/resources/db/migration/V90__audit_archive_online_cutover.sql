ALTER TABLE audit_archive_batch
  DROP CHECK ck_audit_archive_state,
  ADD CONSTRAINT ck_audit_archive_state CHECK (state IN ('CLAIMED','UPLOADED','VERIFIED','ONLINE_PURGED'));

CREATE TABLE audit_archive_cutover_grant (
  batch_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '独立批准移出在线库的准确批次',
  approval_reference VARCHAR(96) NOT NULL COMMENT '清理审批记录，其中包含运行版本和已完成恢复证明',
  approved_by VARCHAR(64) NOT NULL COMMENT '签收清理的责任人',
  effective_at DATETIME(3) NOT NULL COMMENT '授权最早生效时间UTC',
  expires_at DATETIME(3) NOT NULL COMMENT '本次清理授权截止时间UTC',
  revoked_at DATETIME(3) NULL COMMENT '签收授权撤销时间',
  PRIMARY KEY(batch_id),
  CONSTRAINT fk_audit_cutover_grant FOREIGN KEY(batch_id) REFERENCES audit_archive_batch(batch_id),
  CONSTRAINT ck_audit_cutover_grant_time CHECK (expires_at>effective_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='独立于恢复工单的批次在线清理签收';

CREATE TABLE audit_archive_online_purge_receipt (
  batch_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '已实际移出在线库的批次',
  object_version VARCHAR(255) NOT NULL COMMENT '此次移除之前已校验的具体档案版本',
  sha256 CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '已校验对象的SHA256摘要',
  approval_reference VARCHAR(96) NOT NULL COMMENT '批准此次在线切换的签收引用',
  attempt_count INT NOT NULL COMMENT '同一事务移除的原尝试数',
  effect_count INT NOT NULL COMMENT '同一事务移除的在线效果数',
  outcome_count INT NOT NULL COMMENT '同一事务移除的在线结果数',
  applied_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '清理在本库生效的时间',
  PRIMARY KEY(batch_id),
  CONSTRAINT fk_audit_online_receipt_batch FOREIGN KEY(batch_id) REFERENCES audit_archive_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='在线副本移除回执，档案与恢复授权继续保留';

CREATE TABLE audit_archive_effect_locator (
  effect_id BIGINT NOT NULL COMMENT '原效果流水，供已有重放证据关联',
  batch_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '原记录所在已验证批次',
  attempt_id BIGINT NOT NULL COMMENT '已归档原尝试流水',
  action_code VARCHAR(96) NULL COMMENT '原业务提交动作，用于恢复在线重放摘要',
  PRIMARY KEY(effect_id),
  KEY ix_audit_archived_effect_batch(batch_id,attempt_id),
  CONSTRAINT fk_audit_archived_effect_batch FOREIGN KEY(batch_id) REFERENCES audit_archive_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='原效果移出在线库后的最小定位记录';

CREATE TABLE audit_archive_late_evidence (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '晚到证据的追加流水',
  batch_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '原证据所在保全批次',
  attempt_id BIGINT NOT NULL COMMENT '原始已归档尝试',
  evidence_key CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '同尝试和事实内容的稳定事件摘要',
  kind VARCHAR(16) NOT NULL COMMENT '业务效果 EFFECT 或入口结果 OUTCOME',
  payload JSON NOT NULL COMMENT '经事务及字段白名单构造的晚到证据',
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '晚到证据在本库确认的时点',
  PRIMARY KEY(id),
  UNIQUE KEY uk_audit_late_event(attempt_id,evidence_key),
  KEY ix_audit_late_batch(batch_id,attempt_id,id),
  CONSTRAINT fk_audit_late_batch FOREIGN KEY(batch_id) REFERENCES audit_archive_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='已归档后晚到的追加证据，原对象字节不改动';
