-- 规则独立接管，不自动迁移租户或撤销已发布规则的可信交易引用。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG','MEMBER_BEHAVIOR','MEMBER_CYCLE','CYCLE_BENEFIT','MEMBER_POINTS','POINT_OFFER','COUPON_DEFINITION','ENTITLEMENT_DEFINITION','ENTITLEMENT','RULE')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '独立员工能力族，包含RULE营销规则';

-- 实际ruleId作为审计目标，资产version由同命令响应关联，发布和审计同事务。
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际业务目标类型，包含marketing_rule营销规则；只有门店类型携带store_id',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id))
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy','commerce_member_tag','commerce_member_behavior_batch','commerce_cycle_benefit','point_offer','coupon_definition','entitlement_definition','entitlement','marketing_rule') AND store_id IS NULL AND resource_id IS NOT NULL));
