ALTER TABLE marketing_budget
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '跨租户唯一的运营预算编号',
  ADD COLUMN business_created_at DATETIME(3) NULL COMMENT '预算建档的UTC时间，历史按所属活动版本核验',
  ADD UNIQUE KEY uk_marketing_budget_business_no (business_no);

ALTER TABLE marketing_segment_run
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '人群刷新运行的运营编号',
  ADD UNIQUE KEY uk_segment_run_business_no (business_no);

ALTER TABLE journey_instance
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '旅程入组实例的运营编号',
  ADD COLUMN business_created_at DATETIME(3) NULL COMMENT '首次入组UTC时间，历史按入组事实核验',
  ADD UNIQUE KEY uk_journey_instance_business_no (business_no);
