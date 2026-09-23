CREATE TABLE fulfillment_record (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 order_id VARCHAR(64) NOT NULL COMMENT '订单与履约单一一对应',
 status VARCHAR(16) NOT NULL COMMENT 'READY待发SHIPPED已发DELIVERED送达CANCELLED取消',
 tracking_no VARCHAR(64) NULL COMMENT '不可覆盖的运单号',
 provider VARCHAR(32) NOT NULL COMMENT 'WMS提供方沙箱明确标识',
 blocked BOOLEAN NOT NULL DEFAULT FALSE COMMENT '活动售后阻止新发货',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
 PRIMARY KEY(tenant_id,order_id),
 CONSTRAINT ck_fulfillment_status CHECK(status IN ('READY','SHIPPED','DELIVERED','CANCELLED')),
 CONSTRAINT ck_fulfillment_tracking CHECK(status NOT IN ('SHIPPED','DELIVERED') OR tracking_no IS NOT NULL),
 CONSTRAINT ck_fulfillment_version CHECK(version>=0)
) COMMENT='履约单与退款发货互斥控制';
ALTER TABLE inventory_hold ADD COLUMN returned_quantity INT NOT NULL DEFAULT 0 COMMENT '已可信退回的确认件数',
 ADD CONSTRAINT ck_hold_returned CHECK(returned_quantity>=0 AND returned_quantity<=quantity);
CREATE TABLE inventory_return (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 case_id VARCHAR(64) NOT NULL COMMENT '售后收货幂等键',
 sku_id VARCHAR(64) NOT NULL COMMENT '退货商品',
 quantity INT NOT NULL COMMENT '已回补件数',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '回补时间UTC',
 PRIMARY KEY(tenant_id,case_id,sku_id),CONSTRAINT ck_return_quantity CHECK(quantity>0 AND quantity<=10000)
) COMMENT='库存退货回补台账';
CREATE TABLE aftersales_case (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 case_id VARCHAR(64) NOT NULL COMMENT '售后申请号',
 order_id VARCHAR(64) NOT NULL COMMENT '原订单',
 member_id VARCHAR(64) NOT NULL COMMENT '申请会员',
 status VARCHAR(16) NOT NULL COMMENT 'REQUESTED申请WAIT_RETURN待退REFUNDING退款中COMPLETED完成REJECTED驳回',
 return_required BOOLEAN NOT NULL COMMENT '申请时已发货需要收回实物',
 refund_amount DECIMAL(14,2) NOT NULL COMMENT '按原行分摊计算的本次人民币退款',
 refund_id VARCHAR(64) NULL COMMENT '唯一退款意图',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
 reason VARCHAR(512) NOT NULL COMMENT '会员申请原因',
 items_json LONGTEXT NOT NULL COMMENT '本次退货数量与原分摊退款快照',
 active_order_id VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status IN ('REQUESTED','WAIT_RETURN','REFUNDING') THEN order_id ELSE NULL END) STORED COMMENT '每个订单最多一个活动售后',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '申请UTC时间',
 PRIMARY KEY(tenant_id,case_id),UNIQUE KEY uk_active_aftersale(tenant_id,active_order_id),KEY ix_aftersale_member(tenant_id,member_id,case_id),
 CONSTRAINT ck_aftersale_status CHECK(status IN ('REQUESTED','WAIT_RETURN','REFUNDING','COMPLETED','REJECTED')),
 CONSTRAINT ck_aftersale_amount CHECK(refund_amount>=0 AND version>=0),CONSTRAINT ck_aftersale_items CHECK(JSON_VALID(items_json))
) COMMENT='售后申请生命周期与退款快照';
CREATE TABLE aftersales_line (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 case_id VARCHAR(64) NOT NULL COMMENT '售后申请',
 sku_id VARCHAR(64) NOT NULL COMMENT '原订单商品',
 quantity INT NOT NULL COMMENT '本次退货数量',
 refund_amount DECIMAL(14,2) NOT NULL COMMENT '本行原分摊对应退款',
 PRIMARY KEY(tenant_id,case_id,sku_id),
 CONSTRAINT ck_aftersale_line CHECK(quantity>0 AND quantity<=10000 AND refund_amount>=0)
) COMMENT='累计退货与资金分摊明细';
CREATE TABLE payment_refund (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 refund_id VARCHAR(64) NOT NULL COMMENT '稳定退款请求号',
 case_id VARCHAR(64) NOT NULL COMMENT '业务售后幂等键',
 order_id VARCHAR(64) NOT NULL COMMENT '原收款订单',
 amount DECIMAL(14,2) NOT NULL COMMENT '本次退款金额包含零元业务完成',
 currency VARCHAR(3) NOT NULL COMMENT '币种CNY',
 provider VARCHAR(32) NOT NULL COMMENT '退款提供方或NO_PAYMENT_REQUIRED',
 status VARCHAR(16) NOT NULL COMMENT 'UNKNOWN待核对SUCCEEDED已成功',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
 channel_transaction_id VARCHAR(64) NULL COMMENT '可信渠道退款流水',
 evidence_json LONGTEXT NULL COMMENT '服务端核对成功证据',
 check_attempts INT NOT NULL DEFAULT 0 COMMENT '后台核对次数最多五次',
 next_check_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '下一次核对时间UTC',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '请求创建时间UTC',
 PRIMARY KEY(tenant_id,refund_id),UNIQUE KEY uk_refund_case(tenant_id,case_id),UNIQUE KEY uk_refund_channel(tenant_id,provider,channel_transaction_id),
 KEY ix_refund_order(tenant_id,order_id),KEY ix_refund_due(status,next_check_at,tenant_id),
 CONSTRAINT ck_refund_amount CHECK(amount>=0 AND currency='CNY'),CONSTRAINT ck_refund_status CHECK(status IN ('UNKNOWN','SUCCEEDED')),
 CONSTRAINT ck_refund_evidence CHECK(evidence_json IS NULL OR JSON_VALID(evidence_json)),CONSTRAINT ck_refund_version CHECK(version>=0 AND check_attempts>=0)
) COMMENT='退款意图预留金额与可信结果';
CREATE TABLE payment_refund_sandbox (
 tenant_id VARCHAR(64) NOT NULL COMMENT '隔离租户',
 refund_id VARCHAR(64) NOT NULL COMMENT '退款请求幂等号',
 order_id VARCHAR(64) NOT NULL COMMENT '原支付订单',
 amount DECIMAL(14,2) NOT NULL COMMENT '退款金额',
 currency VARCHAR(3) NOT NULL COMMENT '退款币种',
 status VARCHAR(16) NOT NULL COMMENT 'UNKNOWN或SUCCEEDED的沙箱资金状态',
 transaction_id VARCHAR(64) NULL COMMENT '沙箱退款流水不是银行流水',
 PRIMARY KEY(tenant_id,refund_id),CONSTRAINT ck_refund_sandbox_amount CHECK(amount>0 AND currency='CNY'),
 CONSTRAINT ck_refund_sandbox_status CHECK(status IN ('UNKNOWN','SUCCEEDED')),
 CONSTRAINT ck_refund_sandbox_proof CHECK(status<>'SUCCEEDED' OR transaction_id IS NOT NULL)
) COMMENT='隔离退款渠道账本';
