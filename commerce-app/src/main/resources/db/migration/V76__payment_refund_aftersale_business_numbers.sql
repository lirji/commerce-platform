ALTER TABLE payment_attempt
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '商城支付的统一展示号，渠道支付请求原引用保留',
  ADD UNIQUE KEY uk_payment_business_no (business_no);

ALTER TABLE payment_refund
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '商城退款的统一展示号，渠道原退款请求号保持稳定',
  ADD UNIQUE KEY uk_refund_business_no (business_no);

ALTER TABLE aftersales_case
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '商城售后统一展示号，旧申请主键继续关联明细',
  ADD UNIQUE KEY uk_aftersale_business_no (business_no);
