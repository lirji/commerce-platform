ALTER TABLE marketing_audience_snapshot DROP CHECK ck_audience_window,
 ADD CONSTRAINT ck_audience_window CHECK(version>0 AND valid_until>watermark AND member_count>=0 AND member_count<=100000);
CREATE TABLE marketing_segment (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 segment_id VARCHAR(64) NOT NULL COMMENT '动态人群标识',
 audience_id VARCHAR(64) NOT NULL COMMENT '保留dyn前缀的快照标识',
 current_version BIGINT NOT NULL COMMENT '当前发布的定义版本',
 snapshot_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '已分配快照版本序号取消后不复用',
 enabled BOOLEAN NOT NULL DEFAULT FALSE COMMENT '周期刷新开关',
 next_due TIMESTAMP(3) NOT NULL COMMENT '下一次周期刷新时间UTC',
 lock_version BIGINT NOT NULL DEFAULT 0 COMMENT '定义与调度配置并发版本',
 PRIMARY KEY(tenant_id,segment_id),UNIQUE KEY uk_segment_audience(tenant_id,audience_id),KEY ix_segment_due(enabled,next_due,tenant_id,segment_id)
) COMMENT='动态人群当前定义指针和有界调度状态';
CREATE TABLE marketing_segment_definition (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 segment_id VARCHAR(64) NOT NULL COMMENT '动态人群标识',
 version BIGINT NOT NULL COMMENT '不可变定义版本',
 definition_json JSON NOT NULL COMMENT '规则与刷新资源预算完整快照',
 PRIMARY KEY(tenant_id,segment_id,version),CONSTRAINT ck_segment_definition CHECK(version>0)
) COMMENT='动态人群受限规则版本';
CREATE TABLE marketing_segment_run (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 run_id VARCHAR(64) NOT NULL COMMENT '持久刷新任务标识',
 segment_id VARCHAR(64) NOT NULL COMMENT '来源人群',
 definition_version BIGINT NOT NULL COMMENT '执行固定定义版本',
 audience_id VARCHAR(64) NOT NULL COMMENT '目标快照标识',
 snapshot_version BIGINT NOT NULL COMMENT '目标快照版本',
 cursor_member VARCHAR(64) NOT NULL DEFAULT '' COMMENT '已提交的最后会员ID检查点',
 processed INT NOT NULL DEFAULT 0 COMMENT '已扫描会员数',
 matched INT NOT NULL DEFAULT 0 COMMENT '已匹配会员数',
 status VARCHAR(16) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING ISOLATED COMPLETED CANCELLED FAILED',
 attempts INT NOT NULL DEFAULT 0 COMMENT '当前批次连续失败次数',
 error_code VARCHAR(32) NULL COMMENT '不含业务载荷的失败分类',
 started_at TIMESTAMP(3) NOT NULL COMMENT '扫描区间起点及会员创建截止UTC',
 valid_until TIMESTAMP(3) NOT NULL COMMENT '快照新鲜度截止UTC',
 available_at TIMESTAMP(3) NOT NULL COMMENT '允许下次执行时间UTC',
 active_segment VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status IN ('RUNNING','ISOLATED') THEN segment_id ELSE NULL END) STORED COMMENT '每个人群最多一个活动任务',
 PRIMARY KEY(tenant_id,run_id),UNIQUE KEY uk_segment_running(tenant_id,active_segment),
 KEY ix_segment_runs(tenant_id,segment_id,run_id),KEY ix_segment_run_ready(status,available_at,tenant_id,run_id),
 CONSTRAINT ck_segment_run CHECK(status IN ('RUNNING','ISOLATED','COMPLETED','CANCELLED','FAILED') AND processed>=0 AND processed<=100000 AND matched>=0 AND matched<=processed AND attempts>=0 AND snapshot_version>0 AND valid_until>started_at)
) COMMENT='动态人群逐批刷新检查点与可恢复失败状态';
