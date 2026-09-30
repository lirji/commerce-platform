-- 积分兑换商品员工管理独立接管，不自动切换租户或会员自助兑换。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG','MEMBER_BEHAVIOR','MEMBER_CYCLE','CYCLE_BENEFIT','MEMBER_POINTS','POINT_OFFER')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '独立员工能力族，包含POINT_OFFER积分兑换商品';
