ALTER TABLE platform_audit
  ADD COLUMN audit_action_code VARCHAR(96) NULL COMMENT '领域实际提交的动作编码，早期记录沿用尝试动作';

ALTER TABLE audit_operation_attempt
  ADD KEY ix_audit_attempt_tenant_resource (scope_type,actor_tenant_id,resource_id,occurred_at,id);
