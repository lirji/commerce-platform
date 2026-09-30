CREATE TABLE employee_authority_route (
 tenant_id VARCHAR(80) NOT NULL COMMENT '商城本地租户，来自显式身份映射',
 auth_tenant_id VARCHAR(36) NOT NULL COMMENT '中央治理租户，不从浏览器直接替代本地租户',
 family VARCHAR(40) NOT NULL COMMENT '已经实现用例门禁的能力族，首片仅INVENTORY',
 state VARCHAR(16) NOT NULL COMMENT '唯一权威LEGACY、SHADOW、CENTRAL或安全停止STOPPED',
 ever_central BOOLEAN NOT NULL DEFAULT FALSE COMMENT '接管后不可清除，禁止回退旧管理员权限',
 version BIGINT NOT NULL DEFAULT 1 COMMENT '迁移状态CAS版本',
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最后状态变化UTC时间',
 PRIMARY KEY(tenant_id,family),
 UNIQUE KEY uk_employee_central_family(auth_tenant_id,family),
 CHECK(family IN ('INVENTORY')),
 CHECK(state IN ('LEGACY','SHADOW','CENTRAL','STOPPED')),
 CHECK(version > 0),
 CHECK(NOT ever_central OR state IN ('CENTRAL','STOPPED')),
 CHECK(state <> 'CENTRAL' OR ever_central)
) COMMENT='商城员工按租户及能力族持久接管路由，独立于CATALOG';

DELIMITER $$
CREATE TRIGGER employee_route_initial BEFORE INSERT ON employee_authority_route FOR EACH ROW
BEGIN
 IF NEW.state NOT IN ('LEGACY','SHADOW') OR NEW.ever_central THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='employee authority initial state rejected';
 END IF;
END$$
CREATE TRIGGER employee_route_no_regression BEFORE UPDATE ON employee_authority_route FOR EACH ROW
BEGIN
 IF OLD.tenant_id <> NEW.tenant_id OR OLD.auth_tenant_id <> NEW.auth_tenant_id OR OLD.family <> NEW.family
 OR NEW.version <> OLD.version+1
 OR (OLD.ever_central AND (NOT NEW.ever_central OR NEW.state NOT IN ('CENTRAL','STOPPED')))
 OR (OLD.state='LEGACY' AND NEW.state NOT IN ('LEGACY','SHADOW','STOPPED'))
 OR (OLD.state='STOPPED' AND NEW.state NOT IN ('STOPPED','CENTRAL')) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='employee authority transition rejected';
 END IF;
 SET NEW.updated_at=UTC_TIMESTAMP(3);
END$$
CREATE TRIGGER employee_route_no_delete BEFORE DELETE ON employee_authority_route FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='employee authority route cannot be deleted';
END$$
DELIMITER ;
