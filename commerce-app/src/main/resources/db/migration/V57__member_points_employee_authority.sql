-- 积分政策、读取和人工调整/到期独立接管，不自动切换租户或客户本人路径。
ALTER TABLE employee_authority_route
 DROP CHECK ck_employee_family,
 ADD CONSTRAINT ck_employee_family CHECK(family IN ('INVENTORY','DIRECTORY','MEMBER_PROFILE','MEMBER_GROWTH','MEMBER_TAG','MEMBER_BEHAVIOR','MEMBER_CYCLE','CYCLE_BENEFIT','MEMBER_POINTS')),
 MODIFY family VARCHAR(40) NOT NULL COMMENT '独立员工能力族，包含MEMBER_POINTS积分经营';
