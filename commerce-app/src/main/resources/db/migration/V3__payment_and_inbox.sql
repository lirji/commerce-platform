CREATE TABLE payment_attempt (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 payment_id VARCHAR(64) NOT NULL COMMENT '平台支付尝试标识',
 order_id VARCHAR(64) NOT NULL COMMENT '对应订单',
 amount DECIMAL(14,2) NOT NULL COMMENT '订单权威应付人民币金额',
 currency VARCHAR(3) NOT NULL COMMENT '币种固定CNY',
 provider VARCHAR(32) NOT NULL COMMENT '渠道标识SANDBOX明确表示隔离沙箱',
 status VARCHAR(16) NOT NULL COMMENT 'UNKNOWN未知OPEN待付PAID已付CLOSED关闭',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
 check_attempts INT NOT NULL DEFAULT 0 COMMENT '自动核对次数上限五次后转人工',
 next_check_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下次自动核对时间',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间UTC',
 PRIMARY KEY(tenant_id,payment_id),UNIQUE KEY uk_payment_order(tenant_id,order_id),
 KEY ix_payment_check(status,next_check_at,tenant_id),
 CONSTRAINT ck_payment_amount CHECK(amount>0 AND currency='CNY'),
 CONSTRAINT ck_payment_status CHECK(status IN ('UNKNOWN','OPEN','PAID','CLOSED')),
 CONSTRAINT ck_payment_version CHECK(version>=0 AND check_attempts>=0)
) COMMENT='支付域唯一订单资金意图';
CREATE TABLE payment_sandbox_ledger (
 tenant_id VARCHAR(64) NOT NULL COMMENT '隔离租户',
 payment_id VARCHAR(64) NOT NULL COMMENT '幂等渠道请求号',
 order_id VARCHAR(64) NOT NULL COMMENT '订单关联',
 amount DECIMAL(14,2) NOT NULL COMMENT '渠道收到的应付金额',
 currency VARCHAR(3) NOT NULL COMMENT '币种CNY',
 status VARCHAR(16) NOT NULL COMMENT '渠道独立资金状态',
 transaction_id VARCHAR(64) NULL COMMENT '沙箱收款流水号不是银行流水',
 PRIMARY KEY(tenant_id,payment_id),
 CONSTRAINT ck_sandbox_amount CHECK(amount>0 AND currency='CNY'),
 CONSTRAINT ck_sandbox_status CHECK(status IN ('UNKNOWN','OPEN','PAID','CLOSED')),
 CONSTRAINT ck_sandbox_paid CHECK(status<>'PAID' OR transaction_id IS NOT NULL)
) COMMENT='仅本地联调替身的持久化支付渠道账本';
CREATE TABLE platform_inbox (
 consumer_id VARCHAR(64) NOT NULL COMMENT '稳定消费者标识',
 event_id VARCHAR(64) NOT NULL COMMENT '去重事件标识',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '消费成功事务时间UTC',
 PRIMARY KEY(consumer_id,event_id),KEY ix_inbox_tenant(tenant_id,processed_at)
) COMMENT='同库消费者去重与业务效果同事务记录';
