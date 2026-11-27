CREATE TABLE audit_archive_batch (
  batch_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '稳定档案批次编号',
  scope_type VARCHAR(16) NOT NULL COMMENT '创建时审计授权范围类型',
  tenant_id VARCHAR(64) NOT NULL COMMENT '单批归属可信租户',
  state VARCHAR(16) NOT NULL COMMENT '验证批次状态 CLAIMED UPLOADED VERIFIED',
  older_than DATETIME(3) NOT NULL COMMENT '首轮领取时冻结的UTC截止时间',
  object_key VARCHAR(180) CHARACTER SET ascii NOT NULL COMMENT '受控桶中的固定对象键',
  object_version VARCHAR(255) NULL COMMENT '对象存储返回的不可变版本编号',
  sha256 CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '压缩对象字节SHA256',
  row_count INT NOT NULL COMMENT '冻结的尝试数目，每条含同源结果及效果',
  payload_bytes INT NOT NULL COMMENT '压缩后的实际字节数',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '批次创建时刻',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近一次状态提交时刻',
  PRIMARY KEY(batch_id),
  UNIQUE KEY uk_audit_archive_key(object_key),
  KEY ix_audit_archive_pending(scope_type,tenant_id,state,created_at,batch_id),
  CONSTRAINT ck_audit_archive_state CHECK (state IN ('CLAIMED','UPLOADED','VERIFIED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='独立批次清单，验证路径保留在线记录';

CREATE TABLE audit_archive_member (
  batch_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '冻结所属批次',
  attempt_id BIGINT NOT NULL COMMENT '原审计尝试流水，保留稳定身份与引用',
  PRIMARY KEY(batch_id,attempt_id),
  UNIQUE KEY uk_audit_archive_attempt(attempt_id),
  CONSTRAINT fk_audit_archive_batch FOREIGN KEY(batch_id) REFERENCES audit_archive_batch(batch_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='冻结的审计尝试集合';

CREATE TABLE audit_archive_restore_grant (
  ticket_id VARCHAR(64) NOT NULL COMMENT '已批准的恢复工单编号',
  batch_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '工单批准恢复的具体档案批次',
  requested_by VARCHAR(64) NOT NULL COMMENT '授权恢复操作人',
  approved_by VARCHAR(64) NOT NULL COMMENT '独立审批人',
  expires_at DATETIME(3) NOT NULL COMMENT '工单授权到期时间',
  revoked_at DATETIME(3) NULL COMMENT '工单被撤销的时间',
  PRIMARY KEY(ticket_id),
  KEY ix_audit_restore_grant_batch(batch_id),
  CONSTRAINT fk_audit_restore_grant_batch FOREIGN KEY(batch_id) REFERENCES audit_archive_batch(batch_id),
  CONSTRAINT ck_audit_restore_grant_separation CHECK(requested_by<>approved_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='恢复权限仅对明确操作人和批次有效';

CREATE TABLE audit_archive_restored (
  ticket_id VARCHAR(64) NOT NULL COMMENT '独立审批的恢复工单',
  batch_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '已验证的原对象批次',
  attempt_id BIGINT NOT NULL COMMENT '与原在线尝试同一编号',
  payload JSON NOT NULL COMMENT '仅供受控查询的已校验快照',
  expires_at DATETIME(3) NOT NULL COMMENT '恢复副本访问截止时间',
  PRIMARY KEY(ticket_id,attempt_id),
  KEY ix_audit_archive_restored_expiry(expires_at,ticket_id),
  CONSTRAINT fk_audit_archive_restored_grant FOREIGN KEY(ticket_id) REFERENCES audit_archive_restore_grant(ticket_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='与业务表分离的恢复查询区';

ALTER TABLE audit_operation_attempt
  ADD KEY ix_audit_archive_candidate(scope_type,actor_tenant_id,occurred_at,id);
