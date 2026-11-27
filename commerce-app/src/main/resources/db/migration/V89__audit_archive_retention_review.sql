CREATE TABLE audit_archive_retention_policy (
  scope_type VARCHAR(16) NOT NULL COMMENT '适用的原审计授权范围类型',
  tenant_id VARCHAR(64) NOT NULL COMMENT '签收保留策略的可信租户',
  policy_code VARCHAR(24) NOT NULL COMMENT '原始尝试固化的保留策略类别',
  policy_version SMALLINT NOT NULL COMMENT '只适用于同版本，调整历史需另行签收',
  online_days SMALLINT UNSIGNED NOT NULL COMMENT '已批准的最短在线保留天数',
  total_days SMALLINT UNSIGNED NOT NULL COMMENT '包含在线时间的总保留天数',
  approval_reference VARCHAR(96) NOT NULL COMMENT '业务与治理已签收记录的外部审计引用',
  approved_at DATETIME(3) NOT NULL COMMENT '保留周期签收时间UTC',
  revoked_at DATETIME(3) NULL COMMENT '取消本版自动清理资格的时点',
  PRIMARY KEY(scope_type,tenant_id,policy_code,policy_version),
  CONSTRAINT ck_audit_archive_policy_period CHECK (online_days>0 AND total_days>=online_days)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='归档清理预检只接受经过签收的保留期版本';

CREATE TABLE audit_archive_hold (
  hold_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '保全登记流水',
  scope_type VARCHAR(16) NOT NULL COMMENT '被保护数据的授权范围类型',
  tenant_id VARCHAR(64) NOT NULL COMMENT '被冻结数据的可信租户',
  resource_id VARCHAR(96) NULL COMMENT '稳定原业务标识，空值保护整个范围',
  case_reference VARCHAR(96) NOT NULL COMMENT '法务或安全取证案件记录的编号',
  created_by VARCHAR(64) NOT NULL COMMENT '保全授权记录的人员',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '保全生效时间UTC',
  released_by VARCHAR(64) NULL COMMENT '解除保全的人员',
  released_at DATETIME(3) NULL COMMENT '保持为空表示冻结仍生效',
  PRIMARY KEY(hold_id),
  KEY ix_audit_archive_active_hold(scope_type,tenant_id,released_at,resource_id),
  CONSTRAINT ck_audit_archive_hold_release CHECK ((released_by IS NULL AND released_at IS NULL)
    OR (released_by IS NOT NULL AND released_at IS NOT NULL AND released_at>=created_at))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='跨档案批次生效的保全状态，生命周期鉴权由签收流程登记';
