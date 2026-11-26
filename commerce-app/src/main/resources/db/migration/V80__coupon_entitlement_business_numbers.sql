ALTER TABLE benefit_coupon
  ADD COLUMN created_at TIMESTAMP(3) NULL COMMENT '实际领券时间，历史未知时间保留为空',
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '会员券运营展示号',
  ADD UNIQUE KEY uk_benefit_coupon_business_no (business_no);

ALTER TABLE benefit_grant
  ADD COLUMN created_at TIMESTAMP(3) NULL COMMENT '实际授予创建时刻，历史未知时间保留为空',
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '权益授予展示号',
  ADD UNIQUE KEY uk_benefit_grant_business_no (business_no);

ALTER TABLE benefit_ledger
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '权益账本运营流水号',
  ADD UNIQUE KEY uk_benefit_ledger_business_no (business_no);
