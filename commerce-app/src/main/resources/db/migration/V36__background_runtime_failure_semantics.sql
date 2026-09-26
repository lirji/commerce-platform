-- 仅扩展：失败分类与两类重试计数、无消费者事件终态、平台运维角色、订单到期重试状态、支付与退款核对失败证据。
-- 失败证据只记录分类与异常类型，不含载荷、异常文本或凭据。

-- 事件：SKIPPED表示写入时已声明本进程无消费者，不是投递；transient_attempts不计入毒事件预算。
ALTER TABLE platform_event
 DROP CHECK platform_event_chk_1,
 ADD CONSTRAINT ck_event_status CHECK(status IN ('PENDING','DELIVERED','ISOLATED','SKIPPED')),
 ADD COLUMN skip_reason VARCHAR(32) NULL COMMENT '跳过原因：NO_REGISTERED_CONSUMER或OBSOLETE_EVENT_TYPE',
 ADD COLUMN failure_class VARCHAR(32) NULL COMMENT '最近一次失败分类',
 ADD COLUMN transient_attempts INT NOT NULL DEFAULT 0 COMMENT '依赖不可用、锁冲突、超时等瞬时失败次数，不计入attempts',
 ADD COLUMN first_failed_at DATETIME(3) NULL COMMENT 'UTC首次失败时间',
 ADD COLUMN last_failed_at DATETIME(3) NULL COMMENT 'UTC最近失败时间',
 ADD COLUMN manual_retries INT NOT NULL DEFAULT 0 COMMENT '管理员重放次数，重放不清除失败证据',
 ADD CONSTRAINT ck_event_retry_counts CHECK(transient_attempts>=0 AND manual_retries>=0);

-- B1：order.fulfilling.v1没有消费者、没有外发中继也没有契约要求消费者；历史行转为SKIPPED保留，不删除。
UPDATE platform_event SET status='SKIPPED',skip_reason='NO_REGISTERED_CONSUMER' WHERE status='PENDING' AND event_type='order.fulfilling.v1';

-- B2：平台运维只读取跨租户聚合运行指标，没有任何租户业务权限；租户管理员不隐含跨租户能力。
ALTER TABLE platform_credential
 MODIFY role VARCHAR(32) NOT NULL COMMENT 'ADMIN、MEMBER、OPERATOR或PLATFORM_OPERATOR',
 DROP CHECK ck_credential_role,
 ADD CONSTRAINT ck_credential_role CHECK(role IN ('ADMIN','MEMBER','OPERATOR','PLATFORM_OPERATOR'));

-- 订单到期：逐单事务，单个坏订单退避重试、5次非瞬时失败后停止自动取消，不再阻塞同租户其他订单。
ALTER TABLE order_record
 ADD COLUMN expiry_attempts INT NOT NULL DEFAULT 0 COMMENT '后台到期取消非瞬时失败次数，达到5次停止自动处理',
 ADD COLUMN expiry_transient_attempts INT NOT NULL DEFAULT 0 COMMENT '后台到期取消瞬时失败次数',
 ADD COLUMN expiry_retry_at DATETIME(3) NULL COMMENT 'UTC失败后的下次尝试时间',
 ADD COLUMN expiry_error VARCHAR(160) NULL COMMENT '最近失败分类与异常类型',
 ADD CONSTRAINT ck_order_expiry_attempts CHECK(expiry_attempts>=0 AND expiry_transient_attempts>=0);

-- 支付与退款核对：瞬时失败退回已领取的核对次数，单独计数退避；最近失败证据可查。
ALTER TABLE payment_attempt
 ADD COLUMN check_transient_failures INT NOT NULL DEFAULT 0 COMMENT '渠道不可用等瞬时核对失败次数，不消耗check_attempts',
 ADD COLUMN check_error VARCHAR(160) NULL COMMENT '最近核对失败分类与异常类型';
ALTER TABLE payment_refund
 ADD COLUMN check_transient_failures INT NOT NULL DEFAULT 0 COMMENT '渠道不可用等瞬时核对失败次数，不消耗check_attempts',
 ADD COLUMN check_error VARCHAR(160) NULL COMMENT '最近核对失败分类与异常类型';
