ALTER TABLE store_record
  DROP CHECK store_record_chk_1,
  ADD COLUMN opening_subject_type VARCHAR(16) NULL COMMENT '开店资料申报的主体类型，历史直建门店为空',
  ADD COLUMN opening_category VARCHAR(128) NULL COMMENT '开店申报的主营类目名称',
  ADD COLUMN opening_review_ref VARCHAR(64) NULL COMMENT '外部或内部审核记录编号，仅保存引用',
  ADD COLUMN opened_by VARCHAR(64) NULL COMMENT '确认开店的可信运营主体标识',
  ADD COLUMN opened_at DATETIME(3) NULL COMMENT 'UTC确认开店时间',
  ADD CONSTRAINT ck_store_record_status CHECK (status IN ('PENDING_OPEN','ACTIVE','FROZEN')),
  ADD CONSTRAINT ck_store_opening CHECK (
    (opening_subject_type IS NULL AND opening_category IS NULL AND opening_review_ref IS NULL
      AND opened_by IS NULL AND opened_at IS NULL AND status <> 'PENDING_OPEN')
    OR
    (opening_subject_type IS NOT NULL AND opening_subject_type IN ('PERSONAL','INDIVIDUAL','ENTERPRISE')
      AND opening_category IS NOT NULL
      AND ((status = 'PENDING_OPEN' AND opening_review_ref IS NULL AND opened_by IS NULL AND opened_at IS NULL)
        OR (status <> 'PENDING_OPEN' AND opening_review_ref IS NOT NULL AND opened_by IS NOT NULL AND opened_at IS NOT NULL)))
  );
