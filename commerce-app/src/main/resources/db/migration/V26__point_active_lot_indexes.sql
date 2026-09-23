-- V25已在隔离库应用，追加可用批次索引，不改历史迁移。
ALTER TABLE member_point_lot
 ADD COLUMN active_balance BOOLEAN GENERATED ALWAYS AS (remaining>0 OR held>0) STORED COMMENT '有未归档或冻结余额的批次，排除纯历史扫描',
 ADD INDEX idx_point_active_member(tenant_id,member_id,active_balance,expires_at,lot_id),
 ADD INDEX idx_point_active_expiry(active_balance,expires_at,tenant_id,lot_id);
