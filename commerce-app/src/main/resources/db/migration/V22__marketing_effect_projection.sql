CREATE TABLE marketing_effect_order (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 order_id VARCHAR(64) NOT NULL COMMENT '订单事实幂等键',
 store_id VARCHAR(64) NOT NULL COMMENT '成交门店',
 series_id CHAR(64) NOT NULL COMMENT '店铺和活动版本聚合游标',
 campaign_id VARCHAR(64) NULL COMMENT '成交活动标识无活动为空',
 campaign_version BIGINT NULL COMMENT '成交活动固定版本',
 ordered_at TIMESTAMP(3) NOT NULL COMMENT '下单时间用于队列归属UTC',
 paid BOOLEAN NOT NULL COMMENT '是否形成成交或零元订单已确认',
 paid_amount DECIMAL(14,2) NOT NULL COMMENT '原订单实付金额成交前不计入营收',
 refunded DECIMAL(14,2) NOT NULL COMMENT '截至最后投影的成功退款累计',
 discount_amount DECIMAL(14,2) NOT NULL COMMENT '活动与券优惠成交快照',
 platform_funding DECIMAL(14,2) NOT NULL COMMENT '平台承担优惠成交快照不随退款返预算',
 merchant_funding DECIMAL(14,2) NOT NULL COMMENT '商家承担优惠成交快照不随退款返预算',
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后核对权威数据时间UTC',
 PRIMARY KEY(tenant_id,order_id),KEY ix_effect_cohort(tenant_id,store_id,ordered_at,series_id),
 CONSTRAINT ck_marketing_effect_amount CHECK(paid_amount>=0 AND refunded>=0 AND refunded<=paid_amount AND discount_amount>=0 AND platform_funding>=0 AND merchant_funding>=0 AND platform_funding+merchant_funding=discount_amount)
) COMMENT='可重建营销成交退款和优惠承担分析投影非资金权威账';
