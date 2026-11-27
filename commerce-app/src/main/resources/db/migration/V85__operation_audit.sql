CREATE TABLE audit_operation_attempt (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '本系统尝试流水',
  source_system VARCHAR(24) NOT NULL COMMENT '可信来源系统编码',
  request_id CHAR(36) CHARACTER SET ascii NOT NULL COMMENT '服务端生成的单次请求标识',
  trace_id VARCHAR(64) CHARACTER SET ascii NULL COMMENT '可信请求追踪标识',
  scope_type VARCHAR(16) NOT NULL COMMENT '授权范围类型',
  actor_tenant_id VARCHAR(64) NOT NULL COMMENT '可信操作者所属租户',
  actor_id VARCHAR(64) NOT NULL COMMENT '可信操作者标识',
  action_code VARCHAR(96) NOT NULL COMMENT '已登记的稳定动作码',
  resource_id VARCHAR(96) NULL COMMENT '已校验且允许记录的资源引用',
  occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '尝试开始时间，UTC',
  policy_code VARCHAR(24) NOT NULL COMMENT '记录创建时的保留策略',
  policy_version SMALLINT NOT NULL COMMENT '创建时的保留策略版本',
  schema_version SMALLINT NOT NULL COMMENT '证据结构版本',
  PRIMARY KEY (id),
  UNIQUE KEY uk_audit_attempt_request (source_system,request_id),
  KEY ix_audit_attempt_time (scope_type,occurred_at,id),
  KEY ix_audit_attempt_resource (scope_type,resource_id,occurred_at,id),
  KEY ix_audit_attempt_actor (scope_type,actor_id,occurred_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='操作审计请求尝试';

CREATE TABLE audit_attempt_outcome (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '追加结果流水',
  attempt_id BIGINT NOT NULL COMMENT '来源尝试流水',
  event_ref VARCHAR(128) CHARACTER SET ascii NOT NULL COMMENT '稳定结果事件引用，用于重试去重',
  result_code VARCHAR(24) NOT NULL COMMENT '稳定结果码',
  error_code VARCHAR(64) NULL COMMENT '既有业务错误码，无异常原文',
  effect_id BIGINT NULL COMMENT '重试关联的原平台提交证据',
  recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '结果追加时间，UTC',
  PRIMARY KEY (id),
  UNIQUE KEY uk_audit_outcome_event (event_ref),
  KEY ix_audit_outcome_attempt (attempt_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='操作审计异步或未提交结果';

ALTER TABLE platform_audit
  ADD COLUMN audit_attempt_id BIGINT NULL COMMENT '产生本效果的审计尝试，历史命令为空',
  ADD COLUMN audit_resource_id VARCHAR(96) NULL COMMENT '已授权的业务对象引用',
  ADD COLUMN audit_before_version BIGINT NULL COMMENT '提交前业务版本',
  ADD COLUMN audit_after_version BIGINT NULL COMMENT '提交后业务版本',
  ADD COLUMN audit_reason VARCHAR(256) NULL COMMENT '脱敏后的业务变更原因',
  ADD COLUMN audit_changes_json JSON NULL COMMENT '按动作白名单保留的实际差异',
  ADD UNIQUE KEY uk_platform_audit_attempt (audit_attempt_id),
  ADD KEY ix_platform_audit_command (tenant_id,actor_id,operation,command_key,id);
