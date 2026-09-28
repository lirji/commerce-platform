-- 全局租户发现先按状态/资格时间跳过未来WAIT；领取仍使用租户内索引和短事务行锁。
-- V44已执行，新增索引以独立扩展迁移发布，旧版本SQL仍可运行。
ALTER TABLE journey_instance
 ADD KEY idx_journey_global_due(status,due_at,tenant_id,instance_id),
 ADD KEY idx_journey_global_deadline(status,deadline,tenant_id,instance_id);
