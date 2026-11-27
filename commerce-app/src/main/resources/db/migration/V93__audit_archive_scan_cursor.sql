CREATE TABLE audit_archive_scan_cursor (
  scope_type VARCHAR(16) NOT NULL COMMENT '归档验证按可信平台或租户范围推进',
  tenant_id VARCHAR(64) NOT NULL COMMENT '扫描游标归属的固定租户',
  cutoff_at DATETIME(3) NOT NULL COMMENT '本轮扫描冻结的最年轻可领取时间UTC',
  after_occurred_at DATETIME(3) NULL COMMENT '已经检查的时间位置，开始新扫描时为空',
  after_attempt_id BIGINT NOT NULL DEFAULT 0 COMMENT '同一时间内最后检查的稳定尝试流水',
  updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近一次推进或重置的时间UTC',
  PRIMARY KEY(scope_type,tenant_id),
  CONSTRAINT ck_audit_archive_scan_position CHECK (
    (after_occurred_at IS NULL AND after_attempt_id=0) OR (after_occurred_at IS NOT NULL AND after_attempt_id>0)
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='独立归档扫描的范围锁和有界推进游标';
