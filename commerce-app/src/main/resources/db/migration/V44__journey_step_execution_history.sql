-- 旧实例和旧进程创建的实例不伪造完整历史，新进程入组显式写入0。
ALTER TABLE journey_instance
 ADD COLUMN trace_origin_version BIGINT NULL COMMENT '逐步历史开始的实例版本，NULL表示旧代码无完整历史',
 ADD CONSTRAINT ck_journey_trace_origin CHECK(trace_origin_version IS NULL OR trace_origin_version>=0);

CREATE TABLE journey_step_execution (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 instance_id VARCHAR(64) NOT NULL COMMENT '固定旅程实例标识',
 transition_version BIGINT NOT NULL COMMENT '执行前实例版本，同时作为尝试与游标唯一键',
 ordinal INT NOT NULL COMMENT '逻辑节点序号，重试仍属于同一序号',
 node_id VARCHAR(64) NOT NULL COMMENT '执行的固定图节点',
 node_kind VARCHAR(16) NULL COMMENT '原节点类型，读取配置前故障时为空',
 status VARCHAR(16) NOT NULL COMMENT 'EXECUTING事务内、COMPLETED、WAITING、FAILED、DEFERRED、ISOLATED、STOPPED',
 started_at TIMESTAMP(3) NOT NULL COMMENT '显式UTC执行时间，不复制会员事实载荷',
 completed_at TIMESTAMP(3) NULL COMMENT '本次结果记录UTC时间，事务内EXECUTING为空',
 next_node VARCHAR(64) NULL COMMENT '已决定的下一节点',
 wake_at TIMESTAMP(3) NULL COMMENT 'WAIT资格UTC时间或失败重试资格时间',
 decision VARCHAR(16) NULL COMMENT 'MATCH、NO_MATCH、UNKNOWN三值规则判定',
 outcome VARCHAR(32) NULL COMMENT '稳定业务或运行原因码',
 action_ref VARCHAR(64) NULL COMMENT '既有权益、券或站内信的结果标识',
 failure_class VARCHAR(32) NULL COMMENT '共享FailureClass分类，不保留异常文本',
 PRIMARY KEY(tenant_id,instance_id,transition_version),
 CONSTRAINT fk_journey_step_instance FOREIGN KEY(tenant_id,instance_id) REFERENCES journey_instance(tenant_id,instance_id),
 CONSTRAINT ck_journey_step_version CHECK(transition_version>=0 AND ordinal BETWEEN 1 AND 33),
 CONSTRAINT ck_journey_step_kind CHECK(node_kind IS NULL OR node_kind IN ('WAIT','DECIDE','GRANT','COUPON','NOTIFY','END')),
 CONSTRAINT ck_journey_step_status CHECK(status IN ('EXECUTING','COMPLETED','WAITING','FAILED','DEFERRED','ISOLATED','STOPPED')),
 CONSTRAINT ck_journey_step_decision CHECK(decision IS NULL OR decision IN ('MATCH','NO_MATCH','UNKNOWN')),
 CONSTRAINT ck_journey_step_time CHECK(completed_at IS NULL OR completed_at>=started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='旅程逐步执行证据，成功动作与检查点原子提交，失败受版本保护';
