-- 独立活动族不自动切换真实租户；历史订单仍按原版本快照履约。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG','MEMBER_BEHAVIOR','MEMBER_CYCLE','CYCLE_BENEFIT','MEMBER_POINTS','POINT_OFFER','COUPON_DEFINITION','ENTITLEMENT_DEFINITION','ENTITLEMENT','RULE','AUDIENCE','CAMPAIGN')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '独立员工能力族，包含CAMPAIGN活动与预算';

-- 旧审计无内容版本，保留NULL；活动必须记录正内容版本，与状态lockVersion分离。
ALTER TABLE employee_command_identity
 ADD COLUMN resource_version BIGINT NULL COMMENT '实际不可变资源内容版本；活动必填正数，旧审计保留空值',
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际业务目标类型，活动记录campaign及内容版本；只有门店类型携带store_id',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id) AND resource_version IS NULL)
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy','commerce_member_tag','commerce_member_behavior_batch','commerce_cycle_benefit','point_offer','coupon_definition','entitlement_definition','entitlement','marketing_rule','audience') AND store_id IS NULL AND resource_id IS NOT NULL AND resource_version IS NULL)
   OR (resource_type='campaign' AND store_id IS NULL AND resource_id IS NOT NULL AND resource_version IS NOT NULL AND resource_version>0));
