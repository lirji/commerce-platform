-- 旅程和报表独立接管，旧来源保持未知，不改变原数据。
ALTER TABLE employee_authority_route DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG','MEMBER_BEHAVIOR','MEMBER_CYCLE','CYCLE_BENEFIT','MEMBER_POINTS','POINT_OFFER','COUPON_DEFINITION','ENTITLEMENT_DEFINITION','ENTITLEMENT','RULE','AUDIENCE','CAMPAIGN','SEGMENT','COUPON_DELIVERY','JOURNEY','MARKETING_REPORT')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '独立员工能力族，含旅程JOURNEY与营销报表MARKETING_REPORT';
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际业务目标类型；活动、人群及发券记录正内容版本，只有store携带门店',
 MODIFY resource_version BIGINT NULL COMMENT '实际不可变内容版本；活动、人群及发券必填正数，旧审计保留空值',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id) AND resource_version IS NULL)
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy','commerce_member_tag','commerce_member_behavior_batch','commerce_cycle_benefit','point_offer','coupon_definition','entitlement_definition','entitlement','marketing_rule','audience','marketing_report') AND store_id IS NULL AND resource_id IS NOT NULL AND resource_version IS NULL)
   OR (resource_type IN ('campaign','segment','coupon_delivery','journey','journey_instance','journey_scan') AND store_id IS NULL AND resource_id IS NOT NULL AND resource_version IS NOT NULL AND resource_version>0));

CREATE TABLE employee_finite_execution (
 tenant_id VARCHAR(64) NOT NULL COMMENT '实际业务租户，来自身份绑定',
 actor_id VARCHAR(64) NOT NULL COMMENT '原业务员工身份',
 execution_id CHAR(36) NOT NULL COMMENT '中央签发的原有限执行引用，不可替换',
 capability VARCHAR(100) NOT NULL COMMENT '原精确持久任务能力，禁止控制或推进借用',
 source_json JSON NOT NULL COMMENT '原身份代际、应用环境调用方、路由和准确expiresAt，不含Token或ALLOW',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本地保存原引用时间UTC',
 PRIMARY KEY(tenant_id,actor_id,execution_id,capability),
 UNIQUE KEY uk_finite_execution(execution_id),
 CONSTRAINT ck_finite_execution_cap CHECK(capability IN ('commerce.journey_instance.create','commerce.runtime.replay.create')),
 CONSTRAINT ck_finite_execution_source CHECK((JSON_TYPE(source_json)='OBJECT' AND JSON_CONTAINS_PATH(source_json,'all','$.identity','$.route','$.applicationId','$.environment','$.callerServiceId','$.membershipVersion','$.principalVersion','$.expiresAt')=1) IS TRUE)
) COMMENT='有限主动任务的原执行元数据，不缓存授权结果，不接受续期覆盖';
ALTER TABLE journey_definition ADD COLUMN published_policy_json JSON NULL COMMENT '本版本实际publish提交的固定自动政策及批准路由，旧空值保持未知';
ALTER TABLE journey_instance ADD COLUMN execution_source_json JSON NULL COMMENT '原MANUAL员工引用或已发布SYSTEM固定政策，旧空值保持未知';

ALTER TABLE journey_definition ADD CONSTRAINT ck_journey_employee_policy CHECK(published_policy_json IS NULL OR ((JSON_TYPE(published_policy_json)='OBJECT' AND JSON_CONTAINS_PATH(published_policy_json,'all','$.tenant','$.journeyId','$.contentVersion','$.approvedBy','$.key','$.approvedAt')=1) IS TRUE));
ALTER TABLE journey_instance ADD CONSTRAINT ck_journey_employee_source CHECK(execution_source_json IS NULL OR ((JSON_TYPE(execution_source_json)='OBJECT' AND JSON_CONTAINS_PATH(execution_source_json,'all','$.kind','$.tenant','$.journeyId','$.contentVersion','$.key','$.createdAt')=1 AND JSON_UNQUOTE(JSON_EXTRACT(execution_source_json,'$.kind')) IN ('MANUAL','SYSTEM','LEGACY')) IS TRUE));
