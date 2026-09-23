CREATE TABLE benefit_definition (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 benefit_id VARCHAR(64) NOT NULL COMMENT '内部CREDIT权益定义标识',
 version BIGINT NOT NULL COMMENT '不可变内容版本',
 store_id VARCHAR(64) NOT NULL COMMENT '所属店铺',
 name VARCHAR(128) NOT NULL COMMENT '权益名称',
 units INT NOT NULL COMMENT '每份授予整数单位非人民币余额',
 quota INT NOT NULL COMMENT '总授予份数上限',
 reserved INT NOT NULL DEFAULT 0 COMMENT '待付款预留份数',
 issued INT NOT NULL DEFAULT 0 COMMENT '已付款受理发放份数退款不返额度',
 valid_from TIMESTAMP(3) NOT NULL COMMENT '可预留发放起点UTC',
 valid_to TIMESTAMP(3) NOT NULL COMMENT '可预留发放截止UTC不含端点',
 validity_days INT NOT NULL COMMENT '付款确认后可消费天数',
 PRIMARY KEY(tenant_id,benefit_id,version),KEY ix_benefit_definition_store(tenant_id,store_id,benefit_id),
 CONSTRAINT ck_benefit_definition CHECK(version>0 AND units>0 AND units<=10000 AND quota>0 AND quota<=1000000 AND reserved>=0 AND issued>=0 AND reserved+issued<=quota AND valid_to>valid_from AND validity_days>0 AND validity_days<=365)
) COMMENT='内部权益定义与发放额度权威';
CREATE TABLE benefit_grant (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 grant_id VARCHAR(64) NOT NULL COMMENT '稳定权益履约标识',
 order_id VARCHAR(64) NOT NULL COMMENT '来源订单唯一授予意图',
 member_id VARCHAR(64) NOT NULL COMMENT '受益会员',
 benefit_id VARCHAR(64) NOT NULL COMMENT '定义标识',
 benefit_version BIGINT NOT NULL COMMENT '定义固定版本',
 name VARCHAR(128) NOT NULL COMMENT '名称快照',
 status VARCHAR(32) NOT NULL COMMENT 'RESERVED REQUESTED AVAILABLE CONSUMED CANCELLED REVOKED COMPENSATION_REQUIRED COMPENSATED',
 units INT NOT NULL COMMENT '原始授予单位',
 remaining_units INT NOT NULL COMMENT '当前可消费单位不可负数',
 debt_units INT NOT NULL COMMENT '退款后已消费待补偿单位',
 expires_at TIMESTAMP(3) NULL COMMENT '付款确认后权益消费截止UTC',
 version BIGINT NOT NULL COMMENT '并发状态版本',
 PRIMARY KEY(tenant_id,grant_id),UNIQUE KEY uk_order_entitlement(tenant_id,order_id),KEY ix_entitlement_wallet(tenant_id,member_id,grant_id),
 CONSTRAINT ck_benefit_grant_units CHECK(units>0 AND units<=10000 AND remaining_units>=0 AND remaining_units<=units AND debt_units>=0 AND debt_units<=units AND version>=0),
 CONSTRAINT ck_benefit_grant_state CHECK(status IN ('RESERVED','REQUESTED','AVAILABLE','CONSUMED','CANCELLED','REVOKED','COMPENSATION_REQUIRED','COMPENSATED')),
 CONSTRAINT ck_benefit_available_expiry CHECK(status<>'AVAILABLE' OR expires_at IS NOT NULL)
) COMMENT='订单权益预留异步发放核销与退款补偿状态';
CREATE TABLE benefit_ledger (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 entry_id VARCHAR(64) NOT NULL COMMENT '不可变账本条目标识',
 grant_id VARCHAR(64) NOT NULL COMMENT '对应权益履约标识',
 action VARCHAR(32) NOT NULL COMMENT 'GRANT CONSUME REVOKE COMPENSATION_REQUIRED RECOVERED WRITTEN_OFF',
 units INT NOT NULL COMMENT '本次动作涉及非负单位',
 balance INT NOT NULL COMMENT '动作提交后的剩余单位',
 reference VARCHAR(128) NOT NULL COMMENT '订单命令或人工处理凭据引用',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '记录时间UTC',
 PRIMARY KEY(tenant_id,entry_id),KEY ix_benefit_ledger(tenant_id,grant_id,entry_id),
 CONSTRAINT ck_benefit_ledger_units CHECK(units>=0 AND balance>=0),
 CONSTRAINT ck_benefit_ledger_action CHECK(action IN ('GRANT','CONSUME','REVOKE','COMPENSATION_REQUIRED','RECOVERED','WRITTEN_OFF'))
) COMMENT='内部权益授予消费冲正的不可变账本';
