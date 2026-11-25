-- 按本租户原业务编号查事件时，使用现有事件游标稳定分页。
ALTER TABLE platform_event ADD KEY ix_event_aggregate_list (tenant_id, aggregate_id, event_id);
