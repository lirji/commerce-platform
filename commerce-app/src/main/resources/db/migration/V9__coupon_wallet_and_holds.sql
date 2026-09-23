CREATE TABLE benefit_coupon_definition (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 definition_id VARCHAR(64) NOT NULL COMMENT '券定义稳定标识',
 version BIGINT NOT NULL COMMENT '不可变版本',
 store_id VARCHAR(64) NOT NULL COMMENT '适用店铺',
 name VARCHAR(128) NOT NULL COMMENT '展示名称',
 minimum_spend DECIMAL(14,2) NOT NULL COMMENT '原价总额门槛人民币',
 discount_amount DECIMAL(14,2) NOT NULL COMMENT '最大人民币优惠',
 valid_from TIMESTAMP(3) NOT NULL COMMENT 'UTC有效期含起点',
 valid_to TIMESTAMP(3) NOT NULL COMMENT 'UTC有效期不含终点',
 quota INT NOT NULL COMMENT '发行总张数',
 issued INT NOT NULL DEFAULT 0 COMMENT '累计已发行张数退款不回退',
 stackable BOOLEAN NOT NULL COMMENT '是否允许与单项活动叠加',
 PRIMARY KEY(tenant_id,definition_id,version),KEY ix_coupon_definition_store(tenant_id,store_id,definition_id),
 CONSTRAINT ck_coupon_definition CHECK(version>0 AND minimum_spend>=0 AND discount_amount>0 AND valid_to>valid_from AND quota>0 AND quota<=1000000 AND issued>=0 AND issued<=quota)
) COMMENT='不可变优惠券定义和发行额度';
CREATE TABLE benefit_coupon (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 coupon_id VARCHAR(64) NOT NULL COMMENT '会员钱包券标识',
 member_id VARCHAR(64) NOT NULL COMMENT '持有会员',
 definition_id VARCHAR(64) NOT NULL COMMENT '引用券定义',
 definition_version BIGINT NOT NULL COMMENT '定义固定版本',
 status VARCHAR(16) NOT NULL COMMENT 'AVAILABLE可用HELD占用USED核销EXPIRED过期',
 order_id VARCHAR(64) NULL COMMENT '当前占用或核销订单',
 valid_to TIMESTAMP(3) NOT NULL COMMENT '定义有效期快照用于原子返还过期判定',
 PRIMARY KEY(tenant_id,coupon_id),UNIQUE KEY uk_member_coupon(tenant_id,member_id,definition_id,definition_version),KEY ix_wallet(tenant_id,member_id,coupon_id),
 CONSTRAINT ck_coupon_state CHECK(status IN ('AVAILABLE','HELD','USED','EXPIRED')),
 CONSTRAINT ck_coupon_order CHECK(status NOT IN ('HELD','USED') OR order_id IS NOT NULL)
) COMMENT='会员优惠券钱包权威状态';
CREATE TABLE benefit_coupon_hold (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 order_id VARCHAR(64) NOT NULL COMMENT '占用订单每单最多一券',
 coupon_id VARCHAR(64) NOT NULL COMMENT '占用券',
 discount DECIMAL(14,2) NOT NULL COMMENT '订单实际券优惠封顶金额',
 status VARCHAR(16) NOT NULL COMMENT 'HELD预占USED核销RELEASED取消释放REFUNDED全退返还',
 PRIMARY KEY(tenant_id,order_id),
 CONSTRAINT ck_coupon_hold CHECK(discount>0 AND status IN ('HELD','USED','RELEASED','REFUNDED'))
) COMMENT='按原订单去重的券使用与返还台账';
