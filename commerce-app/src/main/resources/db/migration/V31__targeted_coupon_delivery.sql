ALTER TABLE benefit_coupon_definition ADD COLUMN validity_days INT NULL COMMENT '领取后有效天数，空或0为固定有效期', ADD CONSTRAINT ck_coupon_validity_days CHECK(validity_days IS NULL OR validity_days BETWEEN 0 AND 366);
ALTER TABLE benefit_coupon
 ADD COLUMN valid_from TIMESTAMP(3) NULL COMMENT '发放时固化有效起点，旧券空值回读定义',
 DROP CHECK ck_coupon_source,
 MODIFY COLUMN source_type VARCHAR(16) NOT NULL DEFAULT 'CLAIM' COMMENT 'CLAIM公开、POINTS积分、TARGETED定向批次',
 ADD CONSTRAINT ck_coupon_source CHECK((source_type='CLAIM' AND source_id IS NULL) OR (source_type IN ('POINTS','TARGETED') AND source_id IS NOT NULL)),
 DROP CHECK ck_coupon_state,
 MODIFY COLUMN status VARCHAR(16) NOT NULL COMMENT 'AVAILABLE可用HELD占用USED已用EXPIRED到期REVOKED撤销',
 ADD CONSTRAINT ck_coupon_state CHECK(status IN ('AVAILABLE','HELD','USED','EXPIRED','REVOKED'));
CREATE TABLE automation_coupon_batch (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
 batch_id VARCHAR(64) NOT NULL COMMENT '不可变批次业务标识',
 store_id VARCHAR(64) NOT NULL COMMENT '目标门店',
 content_json JSON NOT NULL COMMENT '固定券、人群版本、期限与频控配置',
 status VARCHAR(24) NOT NULL COMMENT 'RUNNING、COMPLETED、CANCELLED、EXPIRED、ISOLATED、REVOKING、REVOCATION_DONE',
 mode VARCHAR(16) NOT NULL DEFAULT 'ISSUE' COMMENT 'ISSUE发券或REVOKE撤销，失败恢复保留',
 cursor_member VARCHAR(64) NOT NULL DEFAULT '' COMMENT '已提交的发券会员游标',
 revoke_cursor VARCHAR(64) NOT NULL DEFAULT '' COMMENT '已提交的撤销会员游标',
 processed INT NOT NULL DEFAULT 0 COMMENT '已处理目标数含跳过',
 issued INT NOT NULL DEFAULT 0 COMMENT '历史成功发券数',
 skipped INT NOT NULL DEFAULT 0 COMMENT '因会员状态或频控跳过数',
 revoked INT NOT NULL DEFAULT 0 COMMENT '成功撤销可用券数',
 kept INT NOT NULL DEFAULT 0 COMMENT '占用使用或到期未撤销数',
 attempts INT NOT NULL DEFAULT 0 COMMENT '当前收件人连续失败次数',
 error_code VARCHAR(64) NULL COMMENT '安全错误分类，不存堆栈',
 available_at TIMESTAMP(3) NOT NULL COMMENT '下次允许推进UTC时间',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '每次进度及状态变更版本',
 PRIMARY KEY(tenant_id,batch_id),
 INDEX ix_coupon_batch_due(tenant_id,status,available_at,batch_id),
 INDEX ix_coupon_batch_store(tenant_id,store_id,batch_id),
 CHECK(status IN ('RUNNING','COMPLETED','CANCELLED','EXPIRED','ISOLATED','REVOKING','REVOCATION_DONE') AND mode IN ('ISSUE','REVOKE')),
 CHECK(processed>=0 AND issued>=0 AND skipped>=0 AND processed=issued+skipped AND revoked>=0 AND kept>=0 AND revoked+kept<=issued AND attempts BETWEEN 0 AND 5 AND version>=0)
) COMMENT='定向券持久批次，业务效果与游标同事务';
CREATE TABLE automation_coupon_recipient (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
 batch_id VARCHAR(64) NOT NULL COMMENT '所属批次',
 member_id VARCHAR(64) NOT NULL COMMENT '固定人群中的会员',
 status VARCHAR(16) NOT NULL COMMENT 'ISSUED发放、SKIPPED跳过、REVOKED已撤销、KEPT保留',
 coupon_id VARCHAR(64) NULL COMMENT '真实发出的券钱包ID',
 error_code VARCHAR(64) NULL COMMENT '跳过或保留原因',
 created_at TIMESTAMP(3) NOT NULL COMMENT 'UTC首次处理时间',
 PRIMARY KEY(tenant_id,batch_id,member_id),
 FOREIGN KEY(tenant_id,batch_id) REFERENCES automation_coupon_batch(tenant_id,batch_id),
 CHECK(status IN ('ISSUED','SKIPPED','REVOKED','KEPT'))
) COMMENT='发券收件人回执，不复制尚未处理的人群';
CREATE TABLE automation_coupon_frequency (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户',
 member_id VARCHAR(64) NOT NULL COMMENT '会员',
 next_eligible_at TIMESTAMP(3) NOT NULL COMMENT '全定向批次共享的下一次可发时间',
 PRIMARY KEY(tenant_id,member_id)
) COMMENT='按会员隔离的定向券冷却期，不可被新批次降低';
