CREATE TABLE marketing_budget (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 budget_id VARCHAR(64) NOT NULL COMMENT '稳定分页预算标识',
 campaign_id VARCHAR(64) NOT NULL COMMENT '活动标识',
 version BIGINT NOT NULL COMMENT '不可变活动版本',
 cap DECIMAL(14,2) NULL COMMENT '人民币上限空值表示未设预算',
 held DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '待支付预占人民币额度',
 spent DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '已支付累计营销消耗退款不释放',
 PRIMARY KEY(tenant_id,campaign_id,version),UNIQUE KEY uk_budget_cursor(tenant_id,budget_id),
 CONSTRAINT ck_marketing_budget CHECK(held>=0 AND spent>=0 AND (cap IS NULL OR (cap>0 AND held+spent<=cap)))
) COMMENT='活动版本的营销预算权威余额';
CREATE TABLE marketing_budget_hold (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 order_id VARCHAR(64) NOT NULL COMMENT '唯一活动占用订单',
 campaign_id VARCHAR(64) NOT NULL COMMENT '原活动标识',
 version BIGINT NOT NULL COMMENT '原活动版本',
 discount DECIMAL(14,2) NOT NULL COMMENT '实际活动优惠',
 platform_funding DECIMAL(14,2) NOT NULL COMMENT '平台承担人民币优惠',
 merchant_funding DECIMAL(14,2) NOT NULL COMMENT '商家承担人民币优惠',
 status VARCHAR(16) NOT NULL COMMENT 'HELD占用SPENT消耗RELEASED取消释放',
 PRIMARY KEY(tenant_id,order_id),
 CONSTRAINT ck_budget_hold CHECK(discount>0 AND platform_funding>=0 AND merchant_funding>=0 AND platform_funding+merchant_funding=discount AND status IN ('HELD','SPENT','RELEASED'))
) COMMENT='订单活动优惠预占及资方核算快照';
-- 已有活动仍可报价，历史版本未声明预算则明确无上限。
INSERT INTO marketing_budget(tenant_id,budget_id,campaign_id,version)
SELECT tenant_id,UUID(),campaign_id,version FROM marketing_campaign;
ALTER TABLE benefit_coupon_definition ADD COLUMN platform_funding_bps INT NOT NULL DEFAULT 0 COMMENT '券优惠平台承担比例万分比其余商家承担',
 ADD CONSTRAINT ck_coupon_funding CHECK(platform_funding_bps>=0 AND platform_funding_bps<=10000);
