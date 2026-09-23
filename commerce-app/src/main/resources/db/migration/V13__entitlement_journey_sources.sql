ALTER TABLE benefit_grant
 ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'ORDER' COMMENT '权益来源ORDER或JOURNEY',
 ADD COLUMN source_id VARCHAR(64) NULL COMMENT '稳定来源幂等键旅程使用实例节点效果摘要',
 MODIFY COLUMN order_id VARCHAR(64) NULL COMMENT '来源真实订单手动旅程可为空';
UPDATE benefit_grant SET source_id=order_id WHERE source_id IS NULL;
ALTER TABLE benefit_grant
 MODIFY COLUMN source_id VARCHAR(64) NOT NULL COMMENT '稳定来源幂等键旅程使用实例节点效果摘要',
 DROP INDEX uk_order_entitlement,
 ADD UNIQUE KEY uk_entitlement_source(tenant_id,source_type,source_id),
 ADD KEY ix_entitlement_order(tenant_id,order_id,grant_id),
 ADD CONSTRAINT ck_entitlement_source CHECK(source_type IN ('ORDER','JOURNEY') AND (source_type<>'ORDER' OR order_id IS NOT NULL));
