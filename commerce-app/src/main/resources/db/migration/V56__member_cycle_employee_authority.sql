-- 周期管理与周期权益分别接管，不切换现有租户或客户本人入口。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG','MEMBER_BEHAVIOR','MEMBER_CYCLE','CYCLE_BENEFIT')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '独立员工能力族，包含MEMBER_CYCLE周期管理与CYCLE_BENEFIT周期权益';

-- 礼包定义以实际bindingId审计，不以虚构会员或策略编号代替。
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际目标store、merchant、commerce_member、commerce_member_policy、commerce_member_tag、commerce_member_behavior_batch或commerce_cycle_benefit',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id))
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy','commerce_member_tag','commerce_member_behavior_batch','commerce_cycle_benefit') AND store_id IS NULL AND resource_id IS NOT NULL));
