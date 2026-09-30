-- 在同一ALTER中替换已核实的V49范围约束，状态和不可回退触发器保持。
ALTER TABLE employee_authority_route
 DROP CHECK employee_authority_route_chk_1,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '已实现门禁的能力族INVENTORY或DIRECTORY';

-- 默认store兼容旧库存INSERT与历史审计，不批量回填或覆盖已记录身份。
ALTER TABLE employee_command_identity
 MODIFY store_id VARCHAR(100) NULL COMMENT '实际门店目标；商家目标为空，不伪造门店',
 ADD resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际业务目标类型store或merchant，历史库存默认为store',
 ADD resource_id VARCHAR(100) NULL COMMENT '实际业务目标ID；旧库存记录为空时使用store_id',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id))
   OR (resource_type='merchant' AND store_id IS NULL AND resource_id IS NOT NULL));
