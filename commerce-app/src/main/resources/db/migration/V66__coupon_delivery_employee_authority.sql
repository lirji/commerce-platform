-- 发券独立权威只追加约束，不接管任何真实租户或猜测历史任务来源。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG','MEMBER_BEHAVIOR','MEMBER_CYCLE','CYCLE_BENEFIT','MEMBER_POINTS','POINT_OFFER','COUPON_DEFINITION','ENTITLEMENT_DEFINITION','ENTITLEMENT','RULE','AUDIENCE','CAMPAIGN','SEGMENT','COUPON_DELIVERY')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '独立员工能力族，包含定向发券COUPON_DELIVERY';
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际业务目标类型；活动、人群及发券记录正内容版本，只有store携带门店',
 MODIFY resource_version BIGINT NULL COMMENT '实际不可变内容版本；活动、人群及发券必填正数，旧审计保留空值',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id) AND resource_version IS NULL)
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy','commerce_member_tag','commerce_member_behavior_batch','commerce_cycle_benefit','point_offer','coupon_definition','entitlement_definition','entitlement','marketing_rule','audience') AND store_id IS NULL AND resource_id IS NOT NULL AND resource_version IS NULL)
   OR (resource_type IN ('campaign','segment','coupon_delivery') AND store_id IS NULL AND resource_id IS NOT NULL AND resource_version IS NOT NULL AND resource_version>0));

CREATE TABLE employee_coupon_delivery_execution (
 tenant_id VARCHAR(64) NOT NULL COMMENT '业务租户，来自受控身份绑定',
 actor_id VARCHAR(64) NOT NULL COMMENT '原业务员工身份',
 execution_id CHAR(36) NOT NULL COMMENT '中央签发的原执行引用，不保存Token',
 capability VARCHAR(100) NOT NULL COMMENT '准确create或control能力，不能交叉使用',
 source_json JSON NOT NULL COMMENT '原主体代际、应用环境调用方、路由及准确中央引用期限',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本地保存签发来源时间UTC',
 PRIMARY KEY(tenant_id,actor_id,execution_id,capability),
 UNIQUE KEY uk_coupon_delivery_execution(execution_id),
 CONSTRAINT ck_coupon_delivery_execution_cap CHECK(capability IN ('commerce.coupon_delivery.create','commerce.coupon_delivery.control')),
 CONSTRAINT ck_coupon_delivery_execution_source CHECK((JSON_TYPE(source_json)='OBJECT'
   AND JSON_CONTAINS_PATH(source_json,'all','$.identity','$.route','$.applicationId','$.environment','$.callerServiceId','$.membershipVersion','$.principalVersion','$.expiresAt')=1) IS TRUE)
) COMMENT='定向发券有限执行来源，Owner分别固定发放及首次撤回来源，不缓存ALLOW';

ALTER TABLE automation_coupon_batch
 ADD COLUMN content_version BIGINT NOT NULL DEFAULT 1 COMMENT '批次不可变content_json的实际正版本，独立于进度CAS version',
 ADD COLUMN issue_source_json JSON NULL COMMENT '原发放Actor及中央create引用或明确旧权威来源，旧空值保持未知',
 ADD COLUMN revoke_source_json JSON NULL COMMENT '首次明确撤回Actor及独立control引用，后续重试不得替换',
 ADD CONSTRAINT ck_coupon_batch_content_version CHECK(content_version=1),
 ADD CONSTRAINT ck_coupon_batch_issue_source CHECK(issue_source_json IS NULL OR
   (JSON_TYPE(issue_source_json)='OBJECT' AND JSON_UNQUOTE(JSON_EXTRACT(issue_source_json,'$.kind')) IN ('CENTRAL','LEGACY')
    AND JSON_CONTAINS_PATH(issue_source_json,'all','$.actor','$.commandKey','$.createdAt')=1) IS TRUE),
 ADD CONSTRAINT ck_coupon_batch_revoke_source CHECK(revoke_source_json IS NULL OR
   (JSON_TYPE(revoke_source_json)='OBJECT' AND JSON_UNQUOTE(JSON_EXTRACT(revoke_source_json,'$.kind')) IN ('CENTRAL','LEGACY')
    AND JSON_CONTAINS_PATH(revoke_source_json,'all','$.actor','$.commandKey','$.createdAt')=1) IS TRUE);
