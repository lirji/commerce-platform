ALTER TABLE audit_operation_attempt
  ADD COLUMN store_id VARCHAR(64) NULL COMMENT '已核验的目标门店，租户或平台范围为空',
  ADD KEY ix_audit_attempt_store_resource (scope_type,actor_tenant_id,store_id,resource_id,occurred_at,id);
