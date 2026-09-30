-- 成长与基础资料分族接管，保留既有状态/不可删除触发器，不自动迁移任何租户。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '已实现能力族INVENTORY、DIRECTORY、MEMBER_PROFILE或MEMBER_GROWTH';

-- 政策审计绑定实际新增版本；不是虚构会员或门店，不改变已有三类审计解释。
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际目标类型store、merchant、commerce_member或commerce_member_policy',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id))
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy') AND store_id IS NULL AND resource_id IS NOT NULL));
