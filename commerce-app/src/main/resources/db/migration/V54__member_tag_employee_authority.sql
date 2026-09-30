-- 标签独立接管，保留既有路由状态与不可删除触发器，不自动切换任何租户。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '已实现员工能力族INVENTORY、DIRECTORY、MEMBER_PROFILE、MEMBER_GROWTH或MEMBER_TAG';

-- 字典定义的实际审计目标是标签；授权范围仍是会员域，不把标签编号伪装成会员事实。
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际目标类型store、merchant、commerce_member、commerce_member_policy或commerce_member_tag',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id))
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy','commerce_member_tag') AND store_id IS NULL AND resource_id IS NOT NULL));
