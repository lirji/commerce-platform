ALTER TABLE benefit_coupon_definition
 ADD COLUMN issuance_mode VARCHAR(16) NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC公开领取、SOURCE_ONLY受控来源发放',
 ADD CONSTRAINT ck_coupon_issuance CHECK(issuance_mode IN ('PUBLIC','SOURCE_ONLY'));
ALTER TABLE benefit_coupon
 DROP INDEX uk_member_coupon,
 ADD COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'CLAIM' COMMENT 'CLAIM公开领取、POINTS积分兑换',
 ADD COLUMN source_id VARCHAR(64) NULL COMMENT '受控发放唯一业务来源，公开领取为空',
 ADD COLUMN claim_member VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN source_type='CLAIM' THEN member_id ELSE NULL END) STORED COMMENT '只约束公开领取每会员一定义一次',
 ADD UNIQUE KEY uk_member_coupon_claim(tenant_id,claim_member,definition_id,definition_version),
 ADD UNIQUE KEY uk_coupon_source(tenant_id,source_type,source_id),
 ADD CONSTRAINT ck_coupon_source CHECK((source_type='CLAIM' AND source_id IS NULL) OR (source_type='POINTS' AND source_id IS NOT NULL));
ALTER TABLE benefit_grant
 DROP CHECK ck_entitlement_source,
 MODIFY COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'ORDER' COMMENT 'ORDER订单、JOURNEY旅程、LEVEL等级、POINTS积分',
 ADD CONSTRAINT ck_entitlement_source CHECK(source_type IN ('ORDER','JOURNEY','LEVEL','POINTS') AND (source_type<>'ORDER' OR order_id IS NOT NULL));
CREATE TABLE member_point_exchange (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
 redemption_id VARCHAR(64) NOT NULL COMMENT '兑换业务唯一来源',
 member_id VARCHAR(64) NOT NULL COMMENT '积分所有者',
 points BIGINT NOT NULL COMMENT '扣除整数积分',
 PRIMARY KEY(tenant_id,redemption_id),
 FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id),
 CHECK(points>0 AND points<=1000000000)
) COMMENT='积分域兑换扣费事实，与权益受理同事务';
CREATE TABLE benefit_point_offer (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
 offer_id VARCHAR(64) NOT NULL COMMENT '兑换项目唯一标识',
 store_id VARCHAR(64) NOT NULL COMMENT '适用门店',
 content_json JSON NOT NULL COMMENT '不可变兑换内容及规则',
 status VARCHAR(16) NOT NULL COMMENT 'ACTIVE可兑换、INACTIVE停用',
 valid_from TIMESTAMP(3) NOT NULL COMMENT '发行窗口起点含',
 valid_to TIMESTAMP(3) NOT NULL COMMENT '发行窗口终点不含',
 quota INT NOT NULL COMMENT '兑换总次数限额',
 issued INT NOT NULL DEFAULT 0 COMMENT '累计成功兑换次数',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '状态乐观锁版本',
 PRIMARY KEY(tenant_id,offer_id),
 INDEX ix_point_offer_store(tenant_id,store_id,offer_id),
 CHECK(status IN ('ACTIVE','INACTIVE') AND valid_to>valid_from AND quota BETWEEN 1 AND 1000000 AND issued BETWEEN 0 AND quota AND version>=0)
) COMMENT='可运营积分兑换目录，停用不撤销既有兑换';
CREATE TABLE benefit_point_offer_member (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
 offer_id VARCHAR(64) NOT NULL COMMENT '兑换项目',
 member_id VARCHAR(64) NOT NULL COMMENT '会员',
 redeemed INT NOT NULL COMMENT '成功兑换累计次数',
 PRIMARY KEY(tenant_id,offer_id,member_id),
 FOREIGN KEY(tenant_id,offer_id) REFERENCES benefit_point_offer(tenant_id,offer_id),
 CHECK(redeemed BETWEEN 1 AND 1000)
) COMMENT='兑换会员限额当前计数';
CREATE TABLE benefit_point_redemption (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
 redemption_id VARCHAR(64) NOT NULL COMMENT '兑换回执标识',
 offer_id VARCHAR(64) NOT NULL COMMENT '来源兑换项目',
 member_id VARCHAR(64) NOT NULL COMMENT '兑换会员',
 points BIGINT NOT NULL COMMENT '已扣积分',
 kind VARCHAR(16) NOT NULL COMMENT 'COUPON券或ENTITLEMENT权益',
 asset_id VARCHAR(64) NOT NULL COMMENT '真实券钱包标识或权益授予标识',
 created_at TIMESTAMP(3) NOT NULL COMMENT 'UTC受理时间',
 PRIMARY KEY(tenant_id,redemption_id),
 INDEX ix_point_redemption_member(tenant_id,member_id,redemption_id),
 FOREIGN KEY(tenant_id,offer_id) REFERENCES benefit_point_offer(tenant_id,offer_id),
 CHECK(points>0 AND kind IN ('COUPON','ENTITLEMENT'))
) COMMENT='成功扣分与资产受理回执，权益到账由原消费者推进';
