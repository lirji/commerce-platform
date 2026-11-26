ALTER TABLE catalog_operation_job
  ADD COLUMN created_at TIMESTAMP(3) NULL COMMENT '实际创建时间，旧任务缺失时保留为空',
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '经营批次可读号，旧任务仍按原编号查询',
  ADD UNIQUE KEY uk_catalog_job_business_no (business_no);
