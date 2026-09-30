-- 仅扩展已实现的会员基础族；保留不可回退与不可删除路由触发器。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '已实现能力族INVENTORY、DIRECTORY或MEMBER_PROFILE';

-- 会员审计只记录实际会员ID，不伪造门店；历史库存与目录审计保持原解释。
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际目标类型store、merchant或commerce_member',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id))
   OR (resource_type IN ('merchant','commerce_member') AND store_id IS NULL AND resource_id IS NOT NULL));
