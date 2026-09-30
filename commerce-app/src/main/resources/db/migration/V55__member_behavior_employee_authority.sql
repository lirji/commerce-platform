-- 行为能力独立接管，不切换现有租户，也不改变客户本人身份规则。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG','MEMBER_BEHAVIOR')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '已实现员工能力族INVENTORY、DIRECTORY、MEMBER_PROFILE、MEMBER_GROWTH、MEMBER_TAG或MEMBER_BEHAVIOR';

-- 重建审计指向实际持久命令批次，tenant/actor/operation/key共同定位platform_command。
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际目标类型store、merchant、commerce_member、commerce_member_policy、commerce_member_tag或commerce_member_behavior_batch',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id))
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy','commerce_member_tag','commerce_member_behavior_batch') AND store_id IS NULL AND resource_id IS NOT NULL));
