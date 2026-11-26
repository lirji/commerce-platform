ALTER TABLE order_record
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '用于查单和展示的商城本地订单号',
  ADD COLUMN business_date DATE AS (DATE(created_at + INTERVAL 8 HOUR)) VIRTUAL COMMENT '订单创建时刻对应的北京时间业务日',
  ADD COLUMN no_tail CHAR(4) CHARACTER SET ascii COLLATE ascii_bin AS (RIGHT(business_no,4)) VIRTUAL COMMENT '可读订单号的四位尾号',
  ADD UNIQUE KEY uk_order_business_no (business_no),
  ADD KEY ix_order_tenant_day_tail (tenant_id,business_date,no_tail,order_id);

-- 历史订单按原主键分批回填业务号，保持订单与资金、库存、地址快照的引用稳定。
