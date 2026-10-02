-- 仅登记已批准CE06/07族与审计类型，不自动接管任何实际租户。
ALTER TABLE employee_authority_route DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG','MEMBER_BEHAVIOR','MEMBER_CYCLE','CYCLE_BENEFIT','MEMBER_POINTS','POINT_OFFER','COUPON_DEFINITION','ENTITLEMENT_DEFINITION','ENTITLEMENT','RULE','AUDIENCE','CAMPAIGN','SEGMENT','COUPON_DELIVERY','JOURNEY','MARKETING_REPORT','ORDER','PAYMENT','FULFILLMENT','AFTERSALE','REFUND','OPS_PAGE','EVENT','RUNTIME')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '独立员工能力族，含CE06订单履约与CE07页面运行时';
ALTER TABLE employee_command_identity
 DROP CHECK ck_employee_resource,
 MODIFY resource_type VARCHAR(40) NOT NULL DEFAULT 'store' COMMENT '实际业务目标类型；活动、人群及发券记录正内容版本，只有store携带门店',
 MODIFY resource_version BIGINT NULL COMMENT '实际不可变内容版本；活动、人群及发券必填正数，旧审计保留空值',
 ADD CONSTRAINT ck_employee_resource CHECK(
   (resource_type='store' AND store_id IS NOT NULL AND (resource_id IS NULL OR resource_id=store_id) AND resource_version IS NULL)
   OR (resource_type IN ('merchant','commerce_member','commerce_member_policy','commerce_member_tag','commerce_member_behavior_batch','commerce_cycle_benefit','point_offer','coupon_definition','entitlement_definition','entitlement','marketing_rule','audience','marketing_report','commerce_runtime','order_expiry_batch') AND store_id IS NULL AND resource_id IS NOT NULL AND resource_version IS NULL)
   OR (resource_type IN ('campaign','segment','coupon_delivery','journey','journey_instance','journey_scan','ops_page') AND store_id IS NULL AND resource_id IS NOT NULL AND resource_version IS NOT NULL AND resource_version>0));

