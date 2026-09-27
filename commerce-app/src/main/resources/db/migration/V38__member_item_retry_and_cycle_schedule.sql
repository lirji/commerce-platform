-- 仅扩展：积分过期与周期考核的逐项重试状态，以及周期考核的可索引到期时间（证据见phase4 item-retry-isolation与cycles-query-analysis）。

-- 逐项重试状态与调度分离：业务到期仍由批次expires_at、会员cycle_due_at表达；本表只在某项失败期间存在，成功即删除。
-- 瞬时失败不计入attempts；quarantined_at非空表示已停止自动处理，只能由运维恢复命令放回；失败证据不含载荷或异常文本。
CREATE TABLE member_work_retry (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 lane VARCHAR(16) NOT NULL COMMENT '后台车道：points积分过期或cycles周期考核',
 item_id VARCHAR(64) NOT NULL COMMENT '积分批次或会员标识',
 attempts INT NOT NULL DEFAULT 0 COMMENT '非瞬时失败次数，达到5次停止自动处理',
 transient_attempts INT NOT NULL DEFAULT 0 COMMENT '依赖不可用、锁冲突、超时等瞬时失败次数',
 retry_at DATETIME(3) NOT NULL COMMENT 'UTC下次可尝试时间',
 failure_class VARCHAR(32) NOT NULL COMMENT '最近一次失败分类',
 last_error VARCHAR(160) NOT NULL COMMENT '最近失败分类与异常类型',
 first_failed_at DATETIME(3) NOT NULL COMMENT 'UTC首次失败时间',
 last_failed_at DATETIME(3) NOT NULL COMMENT 'UTC最近失败时间',
 quarantined_at DATETIME(3) NULL COMMENT 'UTC停止自动处理时间，非空即隔离',
 manual_recoveries INT NOT NULL DEFAULT 0 COMMENT '运维恢复次数，恢复不清除失败证据',
 PRIMARY KEY(tenant_id,lane,item_id),
 KEY ix_work_retry_quarantine(lane,quarantined_at),
 CONSTRAINT ck_work_retry_lane CHECK(lane IN ('points','cycles')),
 CONSTRAINT ck_work_retry_counts CHECK(attempts>=0 AND transient_attempts>=0 AND manual_recoveries>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='会员域后台逐项重试与隔离状态';

-- 周期考核到期：旧发现逻辑每次扫描有策略租户的全部会员（10万会员约160毫秒/次）。
-- 考核成功后写入下一个周期边界与下一策略生效时间中较早者；未考核会员取固定纪元值，任何策略生效即到期；注销会员取9999-12-31，永不到期。
-- 列不可为空：租户发现按(租户,到期)索引只读每个有策略租户的第一条索引项，NULL会排在最前而使该探测退化为扫描。
-- 既有会员全部取纪元值，迁移后各被重新考核一次（考核幂等，只有变化才写快照与事件）。
ALTER TABLE member_record
 ADD COLUMN cycle_due_at DATETIME(3) NOT NULL DEFAULT '1970-01-01 00:00:00.000' COMMENT 'UTC周期考核下次到期时间，9999-12-31表示不再考核',
 ADD KEY ix_member_cycle_due(tenant_id,cycle_due_at);

-- 策略发布后按会员主键分批把到期时间拉到生效时间（rollout），不在发布事务里一次更新整租户会员；既有策略已由上面的纪元值覆盖。
ALTER TABLE member_cycle_policy
 ADD COLUMN rolled_out BOOLEAN NOT NULL DEFAULT FALSE COMMENT '全部会员到期时间已不晚于本策略生效时间',
 ADD COLUMN rollout_cursor VARCHAR(64) NULL COMMENT '分批推进到的最后会员标识',
 ADD KEY ix_cycle_rollout(rolled_out,tenant_id);
UPDATE member_cycle_policy SET rolled_out=TRUE;
