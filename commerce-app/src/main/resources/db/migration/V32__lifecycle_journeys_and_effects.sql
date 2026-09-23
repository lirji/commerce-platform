ALTER TABLE journey_definition DROP CHECK ck_journey_definition,
 ADD CONSTRAINT ck_journey_definition CHECK(version>0 AND valid_to>valid_from AND status IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','PAUSED') AND trigger_type IN ('MANUAL','ORDER_PAID','MEMBER_REGISTERED','LEVEL_CHANGED','SEGMENT_ENTERED','BIRTHDAY','DORMANT','REPURCHASE','CART_ABANDONED'));
ALTER TABLE benefit_coupon DROP CHECK ck_coupon_source,
 MODIFY COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'CLAIM' COMMENT 'CLAIM公开、POINTS积分、TARGETED批次、JOURNEY旅程',
 ADD CONSTRAINT ck_coupon_source CHECK((source_type='CLAIM' AND source_id IS NULL) OR (source_type IN ('POINTS','TARGETED','JOURNEY') AND source_id IS NOT NULL));
ALTER TABLE journey_effect DROP CHECK ck_journey_effect_kind,
 MODIFY COLUMN kind VARCHAR(32) NOT NULL COMMENT '入组、抑制、通知、结束、BENEFIT_GRANTED权益、COUPON_GRANTED券',
 ADD CONSTRAINT ck_journey_effect_kind CHECK(kind IN ('ENROLLED','ENTRY_SUPPRESSED','NOTIFIED','NOTIFY_SUPPRESSED','COMPLETED','BENEFIT_GRANTED','COUPON_GRANTED')),
 ADD KEY ix_journey_effect_cohort(tenant_id,journey_id,journey_version,kind,member_id,created_at);
CREATE TABLE journey_lifecycle_scan (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 journey_id VARCHAR(64) NOT NULL COMMENT '旅程标识',
 journey_version BIGINT NOT NULL COMMENT '固定旅程版本',
 status VARCHAR(16) NOT NULL DEFAULT 'IDLE' COMMENT 'IDLE待下一轮、RUNNING扫描、ISOLATED隔离',
 member_cursor VARCHAR(64) NOT NULL DEFAULT '' COMMENT '本轮已提交会员游标',
 created_before TIMESTAMP(3) NOT NULL COMMENT '本轮会员创建截止UTC',
 next_due TIMESTAMP(3) NOT NULL COMMENT '下次可扫描UTC',
 scanned BIGINT NOT NULL DEFAULT 0 COMMENT '累计扫描会员次数',
 enrolled BIGINT NOT NULL DEFAULT 0 COMMENT '累计成功入组次数',
 attempts INT NOT NULL DEFAULT 0 COMMENT '连续失败次数',
 error_code VARCHAR(32) NULL COMMENT '脱敏错误代码',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '并发检查版本',
 PRIMARY KEY(tenant_id,journey_id,journey_version),
 KEY ix_lifecycle_due(tenant_id,status,next_due,journey_id),
 CONSTRAINT ck_lifecycle_scan CHECK(status IN ('IDLE','RUNNING','ISOLATED') AND attempts BETWEEN 0 AND 5 AND scanned>=enrolled AND enrolled>=0)
) COMMENT='生命周期扫描持久检查点与有界失败恢复';
ALTER TABLE member_behavior_event ADD KEY ix_behavior_store_cart(tenant_id,member_id,store_id,kind,occurred_at);
ALTER TABLE order_record ADD KEY ix_order_member_store_time(tenant_id,member_id,store_id,created_at,status);
ALTER TABLE marketing_effect_order
 ADD COLUMN member_id VARCHAR(64) NULL COMMENT '可信订单会员，旧投影待重建',
 ADD COLUMN coupon_id VARCHAR(64) NULL COMMENT '实际使用券标识，无券为空',
 ADD COLUMN coupon_discount DECIMAL(18,2) NOT NULL DEFAULT 0 COMMENT '成交券自身优惠，不含活动积分',
 ADD KEY ix_effect_member(tenant_id,store_id,member_id,ordered_at),
 ADD KEY ix_effect_coupon(tenant_id,coupon_id,paid),
 ADD CONSTRAINT ck_effect_coupon_discount CHECK(coupon_discount>=0);

ALTER TABLE journey_instance ADD COLUMN lifecycle_anchor TIMESTAMP(3) NULL COMMENT '入组加购锚点，后续购买核对不随新加购移动';
ALTER TABLE automation_coupon_batch ADD COLUMN created_at TIMESTAMP(3) NULL COMMENT '创建UTC时间，升级前未知不猜测', ADD KEY ix_batch_created(tenant_id,store_id,created_at,batch_id);
