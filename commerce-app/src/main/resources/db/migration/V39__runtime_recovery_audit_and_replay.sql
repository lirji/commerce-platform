-- 仅扩展：运维恢复审计、有界历史重放任务与重放扫描索引（证据见phase4 recovery-model与replay-verification）。
-- 审计与任务只保存标识、状态、分类与原因，不保存业务载荷、异常文本或凭据。

-- 每一次运维恢复（含被拒绝的请求项）一行：谁、何时、哪项工作、前后状态、恢复时的失败分类、原因与结果。
-- 与命令幂等记录同一事务写入；同一幂等键重放命令只返回原结果，不重复写审计。
CREATE TABLE platform_recovery (
 id BIGINT NOT NULL AUTO_INCREMENT COMMENT '恢复审计流水',
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识，恢复只作用于本租户',
 actor_id VARCHAR(64) NOT NULL COMMENT '可信操作者',
 operation VARCHAR(64) NOT NULL COMMENT '命令用例代码',
 command_key VARCHAR(64) NOT NULL COMMENT '关联命令幂等键',
 work_type VARCHAR(48) NOT NULL COMMENT '工作类型稳定代码',
 work_id VARCHAR(128) NOT NULL COMMENT '工作标识',
 action VARCHAR(24) NOT NULL COMMENT 'RETRY、SKIP或重放任务控制动作',
 previous_state VARCHAR(32) NULL COMMENT '恢复前状态',
 new_state VARCHAR(32) NULL COMMENT '恢复后状态，被拒绝时为空',
 failure_class VARCHAR(32) NULL COMMENT '恢复时保留的失败分类证据',
 reason VARCHAR(256) NULL COMMENT '运维填写的原因；旧接口无原因时为空',
 result VARCHAR(16) NOT NULL COMMENT 'APPLIED已执行或REJECTED被拒绝',
 rejection VARCHAR(48) NULL COMMENT '拒绝代码：NOT_FOUND、STATE_CHANGED、FAILURE_CLASS_MISMATCH等',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC记录时间',
 PRIMARY KEY(id),
 KEY ix_recovery_tenant(tenant_id,id),
 KEY ix_recovery_work(tenant_id,work_type,work_id),
 CONSTRAINT ck_recovery_result CHECK(result IN ('APPLIED','REJECTED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='运行时运维恢复审计';

-- 历史重放任务：限定租户、单一消费者、事件类型、时间区间与事件上限；按(created_at,event_id)游标推进，可暂停、恢复、取消。
-- 只重放DELIVERED事件；安全门在创建时与每次执行时都校验消费者的副作用分类。
CREATE TABLE platform_replay (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 job_id VARCHAR(64) NOT NULL COMMENT '调用方给定的任务标识',
 consumer_id VARCHAR(64) NOT NULL COMMENT '目标消费者',
 event_types VARCHAR(512) NOT NULL COMMENT '逗号分隔的事件类型，均须由该消费者处理',
 mode VARCHAR(16) NOT NULL COMMENT 'UNPROCESSED只执行该消费者未处理过的事件；REPROCESS对纯投影重新执行',
 from_at DATETIME(3) NOT NULL COMMENT 'UTC事件创建时间下界（含）',
 to_at DATETIME(3) NOT NULL COMMENT 'UTC事件创建时间上界（不含）',
 max_events INT NOT NULL COMMENT '本任务最多检查的事件数',
 status VARCHAR(16) NOT NULL COMMENT 'RUNNING、PAUSED、COMPLETED、CANCELLED或FAILED',
 cursor_created_at DATETIME(3) NULL COMMENT '已检查到的最后事件创建时间',
 cursor_event_id VARCHAR(64) NULL COMMENT '已检查到的最后事件标识',
 examined INT NOT NULL DEFAULT 0 COMMENT '已检查事件数',
 executed INT NOT NULL DEFAULT 0 COMMENT '已执行消费者的事件数',
 already_processed INT NOT NULL DEFAULT 0 COMMENT '该消费者已处理而跳过的事件数（去重）',
 failed INT NOT NULL DEFAULT 0 COMMENT '消费者非瞬时失败的事件数',
 last_error VARCHAR(160) NULL COMMENT '最近失败事件、分类与异常类型',
 reason VARCHAR(256) NOT NULL COMMENT '运维填写的原因',
 created_by VARCHAR(64) NOT NULL COMMENT '创建者',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
 updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT 'UTC最后更新时间',
 PRIMARY KEY(tenant_id,job_id),
 KEY ix_replay_active(status,tenant_id,created_at),
 CONSTRAINT ck_replay_status CHECK(status IN ('RUNNING','PAUSED','COMPLETED','CANCELLED','FAILED')),
 CONSTRAINT ck_replay_mode CHECK(mode IN ('UNPROCESSED','REPROCESS')),
 CONSTRAINT ck_replay_scope CHECK(to_at>from_at AND max_events>0),
 CONSTRAINT ck_replay_counts CHECK(examined>=0 AND executed>=0 AND already_processed>=0 AND failed>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='有界历史事件重放任务';

-- 重放按租户、状态与创建时间顺序扫描已投递事件，不随其他租户或其他状态的事件增长。
ALTER TABLE platform_event ADD KEY ix_event_replay(tenant_id,status,created_at,event_id);
