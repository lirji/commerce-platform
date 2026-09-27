-- 下单先锁定券发行额度；支付确认在同一订单事务内兑现，避免支付后无额度。
ALTER TABLE benefit_coupon_definition
 ADD COLUMN reserved INT NOT NULL DEFAULT 0 COMMENT '营销订单已预留未发行张数',
 DROP CHECK ck_coupon_definition,
 ADD CONSTRAINT ck_coupon_definition CHECK(version>0 AND minimum_spend>=0 AND discount_amount>0 AND valid_to>valid_from AND quota>0 AND quota<=1000000 AND issued>=0 AND reserved>=0 AND issued+reserved<=quota);

ALTER TABLE benefit_coupon
 DROP CHECK ck_coupon_source,
 MODIFY COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'CLAIM' COMMENT 'CLAIM公开、POINTS积分、TARGETED批次、JOURNEY旅程、CAMPAIGN活动订单',
 ADD CONSTRAINT ck_coupon_source CHECK((source_type='CLAIM' AND source_id IS NULL) OR (source_type IN ('POINTS','TARGETED','JOURNEY','CAMPAIGN') AND source_id IS NOT NULL));

CREATE TABLE benefit_campaign_coupon_hold (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 order_id VARCHAR(64) NOT NULL COMMENT '真实订单标识与发券幂等来源',
 member_id VARCHAR(64) NOT NULL COMMENT '预留时固定受益会员',
 store_id VARCHAR(64) NOT NULL COMMENT '预留时固定店铺',
 definition_id VARCHAR(64) NOT NULL COMMENT '受控券定义标识',
 definition_version BIGINT NOT NULL COMMENT '不可变券定义版本',
 coupon_id VARCHAR(64) NOT NULL COMMENT '预先分配的券钱包标识',
 status VARCHAR(16) NOT NULL COMMENT 'HELD预留、ISSUED支付发放、RELEASED取消释放',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC预留时间',
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC最后状态提交时间',
 PRIMARY KEY(tenant_id,order_id),
 UNIQUE KEY uk_campaign_coupon_hold_coupon(tenant_id,coupon_id),
 KEY ix_campaign_coupon_hold_definition(tenant_id,definition_id,definition_version,status),
 CONSTRAINT ck_campaign_coupon_hold_version CHECK(definition_version>0),
 CONSTRAINT ck_campaign_coupon_hold_status CHECK(status IN ('HELD','ISSUED','RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='活动订单券额度预留及发放状态';

-- 旧 V41 行保持原字段；新记录才显式写类型，旧节点仍可写入 NULL 并按旧 CREDIT 语义读取。
ALTER TABLE marketing_execution
 ADD COLUMN benefit_type VARCHAR(16) NULL COMMENT 'CREDIT或COUPON；旧CREDIT行为空时由grant_id推断',
 ADD CONSTRAINT ck_marketing_execution_benefit_type CHECK(benefit_type IS NULL OR benefit_type IN ('CREDIT','COUPON'));
