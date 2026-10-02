-- SEGMENT是独立迁移单元；追加约束不切换任何真实租户。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG','MEMBER_BEHAVIOR','MEMBER_CYCLE','CYCLE_BENEFIT','MEMBER_POINTS','POINT_OFFER','COUPON_DEFINITION','ENTITLEMENT_DEFINITION','ENTITLEMENT','RULE','AUDIENCE','CAMPAIGN','SEGMENT')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '独立员工能力族，包含SEGMENT动态人群';
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际业务目标类型；campaign与segment记录正内容版本，只有store携带门店',
 MODIFY resource_version BIGINT NULL COMMENT '实际不可变内容版本，活动与动态人群必填正数，旧审计保留空值',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id) AND resource_version IS NULL)
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy','commerce_member_tag','commerce_member_behavior_batch','commerce_cycle_benefit','point_offer','coupon_definition','entitlement_definition','entitlement','marketing_rule','audience') AND store_id IS NULL AND resource_id IS NOT NULL AND resource_version IS NULL)
   OR (resource_type IN ('campaign','segment') AND store_id IS NULL AND resource_id IS NOT NULL AND resource_version IS NOT NULL AND resource_version>0));

-- 准入五秒窗口不是持久引用期限，保存签发结果供Owner重启后核对，绝不保存Token。
CREATE TABLE employee_segment_execution (
 tenant_id VARCHAR(64) NOT NULL COMMENT '业务租户，来自受控身份绑定',
 actor_id VARCHAR(64) NOT NULL COMMENT '原业务员工身份',
 execution_id CHAR(36) NOT NULL COMMENT '原中央segment.refresh执行引用，不能更换',
 source_json JSON NOT NULL COMMENT '中央身份代际与应用环境调用方、路由及准确引用期限，不含Token',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本地保存签发来源时间UTC',
 PRIMARY KEY(tenant_id,actor_id,execution_id),
 UNIQUE KEY uk_segment_execution(execution_id),
 CONSTRAINT ck_segment_execution_source CHECK((JSON_TYPE(source_json)='OBJECT'
   AND JSON_CONTAINS_PATH(source_json,'all','$.identity','$.route','$.applicationId','$.environment','$.callerServiceId','$.membershipVersion','$.principalVersion','$.expiresAt')=1) IS TRUE)
) COMMENT='动态人群手工刷新原执行来源，不缓存授权结果';

-- 旧空值明确保持未知来源，CENTRAL下不推断为系统政策；新定义/停用时清除未来政策。
ALTER TABLE marketing_segment
 ADD COLUMN schedule_policy_json JSON NULL COMMENT '已批准周期政策的固定定义版本、调度版本、主体、路由与命令来源；旧空值不自动授权',
 ADD CONSTRAINT ck_segment_schedule_policy CHECK(schedule_policy_json IS NULL OR
   (JSON_TYPE(schedule_policy_json)='OBJECT' AND JSON_CONTAINS_PATH(schedule_policy_json,'all','$.tenant','$.segmentId','$.definitionVersion','$.scheduleVersion','$.approvedAt','$.commandKey')=1) IS TRUE);
ALTER TABLE marketing_segment_run
 ADD COLUMN execution_source_json JSON NULL COMMENT '原手工Actor与中央引用来源或独立固定周期政策；旧空值须显式对账',
 ADD CONSTRAINT ck_segment_run_source CHECK(execution_source_json IS NULL OR
   (JSON_TYPE(execution_source_json)='OBJECT' AND JSON_UNQUOTE(JSON_EXTRACT(execution_source_json,'$.kind')) IN ('MANUAL','SYSTEM','LEGACY')
    AND JSON_CONTAINS_PATH(execution_source_json,'all','$.createdAt')=1) IS TRUE);
