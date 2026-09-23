ALTER TABLE payment_attempt ADD COLUMN refund_reserved DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '累计退款意图预留金额未知结果也计入以防超退',
 ADD CONSTRAINT ck_payment_refund_capacity CHECK(refund_reserved>=0 AND refund_reserved<=amount);
ALTER TABLE aftersales_case ADD KEY ix_aftersale_order(tenant_id,order_id,status);
