-- 仅扩展终态；历史会员与交易主键、等级不变。
ALTER TABLE member_record DROP CHECK member_record_chk_1,
 ADD CONSTRAINT ck_member_lifecycle CHECK(status IN ('ACTIVE','FROZEN','CLOSED')),
 MODIFY status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '会员生命周期ACTIVE正常FROZEN冻结CLOSED注销终态';
CREATE TABLE member_change (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 member_id VARCHAR(64) NOT NULL COMMENT '会员标识',
 version BIGINT NOT NULL COMMENT '变更后的会员并发版本',
 action VARCHAR(16) NOT NULL COMMENT 'PROFILE资料或STATUS状态变更',
 before_value VARCHAR(128) NOT NULL COMMENT '变更前名称或状态',
 after_value VARCHAR(128) NOT NULL COMMENT '变更后名称或状态',
 reason VARCHAR(256) NOT NULL COMMENT '运营填写的变更原因',
 actor_id VARCHAR(64) NOT NULL COMMENT '操作主体',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '提交时间UTC',
 PRIMARY KEY(tenant_id,member_id,version),
 CONSTRAINT ck_member_change CHECK(version>0 AND action IN ('PROFILE','STATUS'))
) COMMENT='会员资料与生命周期不可变变更记录';
