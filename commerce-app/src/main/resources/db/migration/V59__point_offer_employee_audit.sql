-- V58已执行；独立追加实际积分商品审计类型，保留所有已有类型及门店空值约束。
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际业务目标类型，包含point_offer积分兑换商品；门店类型独立携带store_id',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id))
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy','commerce_member_tag','commerce_member_behavior_batch','commerce_cycle_benefit','point_offer') AND store_id IS NULL AND resource_id IS NOT NULL));
