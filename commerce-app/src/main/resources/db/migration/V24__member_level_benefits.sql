ALTER TABLE benefit_grant
 DROP CHECK ck_entitlement_source,
 MODIFY COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'ORDER' COMMENT '权益来源ORDER订单、JOURNEY旅程、LEVEL周期等级',
 ADD CONSTRAINT ck_entitlement_source CHECK(source_type IN ('ORDER','JOURNEY','LEVEL') AND (source_type<>'ORDER' OR order_id IS NOT NULL));
CREATE TABLE benefit_level_bundle (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 binding_id VARCHAR(64) NOT NULL COMMENT '不可变礼包绑定标识',
 policy_version BIGINT NOT NULL COMMENT '会员域周期策略引用版本',
 member_level VARCHAR(64) NOT NULL COMMENT '周期等级代码',
 store_id VARCHAR(64) NOT NULL COMMENT '权益所属门店',
 valid_until DATETIME(3) NOT NULL COMMENT 'UTC发放截止时间，不影响已发权益的独立有效期',
 bundle_json JSON NOT NULL COMMENT '不可变权益引用礼包快照',
 PRIMARY KEY(tenant_id,binding_id),
 UNIQUE KEY uk_level_bundle(tenant_id,policy_version,member_level),
 CONSTRAINT ck_bundle_policy CHECK(policy_version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='会员周期等级权益礼包，等级降级不撤销已发权益';
