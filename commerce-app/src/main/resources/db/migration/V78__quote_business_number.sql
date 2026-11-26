ALTER TABLE trade_quote
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '商城报价展示号，原主键及订单关联继续保留',
  ADD UNIQUE KEY uk_trade_quote_business_no (business_no);
