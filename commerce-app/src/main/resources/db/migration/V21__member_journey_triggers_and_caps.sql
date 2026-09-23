ALTER TABLE journey_definition
 DROP CHECK ck_journey_definition,
 ADD COLUMN published_at TIMESTAMP(3) NULL COMMENT '当前版本最近发布时间用于拒绝追溯入组',
 ADD CONSTRAINT ck_journey_definition CHECK(version>0 AND valid_to>valid_from AND status IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','PAUSED') AND trigger_type IN ('MANUAL','ORDER_PAID','MEMBER_REGISTERED','LEVEL_CHANGED','SEGMENT_ENTERED'));
UPDATE journey_definition SET published_at=UTC_TIMESTAMP(3) WHERE status='PUBLISHED';
CREATE TABLE journey_member_cap (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 journey_id VARCHAR(64) NOT NULL COMMENT '旅程标识跨版本共享频控',
 member_id VARCHAR(64) NOT NULL COMMENT '会员标识',
 entry_window BIGINT NOT NULL DEFAULT -1 COMMENT '入组固定UTC窗口起点秒数',
 entries INT NOT NULL DEFAULT 0 COMMENT '本窗口已入组次数',
 notification_window BIGINT NOT NULL DEFAULT -1 COMMENT '通知固定UTC窗口起点秒数',
 notifications INT NOT NULL DEFAULT 0 COMMENT '本窗口已通知次数',
 suppressed_entries BIGINT NOT NULL DEFAULT 0 COMMENT '累计自动入组抑制次数',
 suppressed_notifications BIGINT NOT NULL DEFAULT 0 COMMENT '累计通知抑制次数',
 PRIMARY KEY(tenant_id,journey_id,member_id),
 CONSTRAINT ck_journey_cap CHECK(entries>=0 AND notifications>=0 AND suppressed_entries>=0 AND suppressed_notifications>=0)
) COMMENT='会员旅程入组与站内通知频控权威计数';
CREATE TABLE journey_effect (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 effect_id VARCHAR(64) NOT NULL COMMENT '效果事实稳定标识',
 journey_id VARCHAR(64) NOT NULL COMMENT '旅程标识',
 journey_version BIGINT NOT NULL COMMENT '旅程内容版本',
 store_id VARCHAR(64) NOT NULL COMMENT '门店归属',
 member_id VARCHAR(64) NOT NULL COMMENT '所属会员',
 kind VARCHAR(32) NOT NULL COMMENT 'ENROLLED ENTRY_SUPPRESSED NOTIFIED NOTIFY_SUPPRESSED COMPLETED',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '事实时间UTC',
 PRIMARY KEY(tenant_id,effect_id),KEY ix_journey_effect_report(tenant_id,store_id,created_at,journey_id,journey_version),
 CONSTRAINT ck_journey_effect_kind CHECK(kind IN ('ENROLLED','ENTRY_SUPPRESSED','NOTIFIED','NOTIFY_SUPPRESSED','COMPLETED'))
) COMMENT='旅程执行指标事实不承担成交归因';
ALTER TABLE marketing_segment_run
 ADD COLUMN entry_cursor VARCHAR(64) NOT NULL DEFAULT '' COMMENT '已发送新入组事实的最后会员ID',
 ADD COLUMN entries_announced BOOLEAN NOT NULL DEFAULT FALSE COMMENT '完整快照的新入组事实是否全部发出',
 ADD COLUMN entry_attempts INT NOT NULL DEFAULT 0 COMMENT '入组事件连续失败次数达到5隔离',
 ADD COLUMN entry_available_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入组事件下次允许发送时间UTC';
-- 历史完成快照不在升级时突然触发会员营销。
UPDATE marketing_segment_run SET entries_announced=TRUE WHERE status='COMPLETED';
