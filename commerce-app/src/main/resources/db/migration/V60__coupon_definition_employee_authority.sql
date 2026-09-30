-- 券定义独立接管，不自动迁移租户或替换客户领取与系统履约身份。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG','MEMBER_BEHAVIOR','MEMBER_CYCLE','CYCLE_BENEFIT','MEMBER_POINTS','POINT_OFFER','COUPON_DEFINITION')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '独立员工能力族，包含COUPON_DEFINITION券定义';

-- 实际definitionId作为审计目标，版本由同一命令的持久回执关联，不虚构门店授权。
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际业务目标类型，包含coupon_definition券定义；只有门店类型携带store_id',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id))
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy','commerce_member_tag','commerce_member_behavior_batch','commerce_cycle_benefit','point_offer','coupon_definition') AND store_id IS NULL AND resource_id IS NOT NULL));
