ALTER TABLE central_scope_export
  ADD COLUMN business_no VARCHAR(25) CHARACTER SET ascii COLLATE ascii_bin NULL COMMENT '中央导出可读号，历史主键及行快照仍保留',
  ADD UNIQUE KEY uk_scope_export_business_no (business_no);
