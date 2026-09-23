CREATE TABLE member_behavior_profile (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
 member_id VARCHAR(64) NOT NULL COMMENT '会员',
 birthday CHAR(5) NULL COMMENT '生日月日MM-DD，不收集出生年份',
 journey_enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否接受站内营销旅程',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '偏好资料并发版本',
 PRIMARY KEY(tenant_id,member_id),
 FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id),
 CHECK(version>=0)
) COMMENT='会员生日与旅程偏好';
CREATE TABLE member_behavior_event (
 sequence_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '分页递增游标',
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
 member_id VARCHAR(64) NOT NULL COMMENT '认证绑定会员',
 event_id VARCHAR(64) NOT NULL COMMENT '调用来源去重标识',
 kind VARCHAR(24) NOT NULL COMMENT 'BROWSE浏览或ADD_TO_CART加购信号',
 store_id VARCHAR(64) NOT NULL COMMENT '校验过的同租户门店',
 sku_id VARCHAR(64) NOT NULL COMMENT '校验过的上架商品',
 occurred_at TIMESTAMP(3) NOT NULL COMMENT '服务端接收UTC时间',
 PRIMARY KEY(sequence_id),
 UNIQUE KEY uk_behavior_event(tenant_id,member_id,event_id),
 INDEX ix_behavior_history(tenant_id,member_id,sequence_id),
 FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id),
 CHECK(kind IN ('BROWSE','ADD_TO_CART'))
) COMMENT='认证商品交互信号，不作为购买或赠分凭据';
CREATE TABLE member_behavior_day (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
 member_id VARCHAR(64) NOT NULL COMMENT '会员',
 business_day DATE NOT NULL COMMENT 'UTC自然日',
 browse_count INT NOT NULL DEFAULT 0 COMMENT '当日浏览信号次数',
 cart_count INT NOT NULL DEFAULT 0 COMMENT '当日加购信号次数',
 last_cart_at TIMESTAMP(3) NULL COMMENT '当日最近加购服务器时间',
 PRIMARY KEY(tenant_id,member_id,business_day),
 FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id),
 CHECK(browse_count>=0 AND cart_count>=0 AND browse_count+cart_count<=200)
) COMMENT='会员行为日汇总与单会员写入额度';
CREATE TABLE member_behavior_order (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
 order_id VARCHAR(64) NOT NULL COMMENT '可信订单来源',
 member_id VARCHAR(64) NOT NULL COMMENT '会员',
 ordered_at TIMESTAMP(3) NOT NULL COMMENT '原始下单UTC时间',
 completed BOOLEAN NOT NULL COMMENT '是否有订单完成事实',
 net_spend DECIMAL(18,2) NOT NULL COMMENT '完成订单成功退款后的净现金消费',
 PRIMARY KEY(tenant_id,order_id),
 INDEX ix_behavior_order_window(tenant_id,member_id,completed,ordered_at),
 FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id),
 CHECK(net_spend>=0)
) COMMENT='会员行为成交投影，可从可信订单与会员成长来源补建';
