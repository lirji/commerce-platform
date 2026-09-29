-- MySQL DDL非事务；只为本迁移首次失败后的受控重跑保留已创建的空路由表。
CREATE TABLE IF NOT EXISTS catalog_authority_route (
 tenant_id VARCHAR(80) PRIMARY KEY COMMENT '商城本地租户，迁移单元固定为commerce目录经营',
 auth_tenant_id VARCHAR(36) NOT NULL UNIQUE COMMENT '显式映射的中央治理租户',
 state VARCHAR(16) NOT NULL COMMENT '唯一权威LEGACY、SHADOW、CENTRAL或安全停止STOPPED',
 frozen BOOLEAN NOT NULL DEFAULT FALSE COMMENT '旧授权管理冻结，直接SQL同样受约束',
 ever_central BOOLEAN NOT NULL DEFAULT FALSE COMMENT '一旦接管永久记录，禁止回到忽略撤权的旧权威',
 version BIGINT NOT NULL DEFAULT 1 COMMENT '迁移操作CAS版本',
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '状态最后变化UTC时间',
 CHECK(state IN ('LEGACY','SHADOW','CENTRAL','STOPPED')),
 CHECK(version > 0),
 CHECK(NOT ever_central OR (frozen AND state IN ('CENTRAL','STOPPED'))),
 CHECK(state <> 'CENTRAL' OR (ever_central AND frozen))
) COMMENT='商城目录经营的持久权威路由，不从客户端角色或开关推断';

DELIMITER $$
CREATE TRIGGER catalog_route_no_regression BEFORE UPDATE ON catalog_authority_route FOR EACH ROW
BEGIN
 IF OLD.tenant_id <> NEW.tenant_id OR OLD.auth_tenant_id <> NEW.auth_tenant_id OR NEW.version <> OLD.version+1
 OR (OLD.ever_central AND (NOT NEW.ever_central OR NOT NEW.frozen OR NEW.state NOT IN ('CENTRAL','STOPPED')))
 OR (OLD.state='LEGACY' AND NEW.state NOT IN ('LEGACY','SHADOW','STOPPED')) THEN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='catalog authority transition rejected';
 END IF;
 SET NEW.updated_at=UTC_TIMESTAMP(3);
END$$
CREATE TRIGGER catalog_route_no_delete BEFORE DELETE ON catalog_authority_route FOR EACH ROW
BEGIN
 SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='catalog authority route cannot be deleted';
END$$
CREATE TRIGGER catalog_grant_freeze_insert BEFORE INSERT ON store_operator_grant FOR EACH ROW
BEGIN
 DECLARE freeze_state BOOLEAN DEFAULT FALSE;
 SELECT frozen INTO freeze_state FROM catalog_authority_route WHERE tenant_id=NEW.tenant_id FOR SHARE;
 IF freeze_state THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='legacy catalog grants frozen'; END IF;
END$$
CREATE TRIGGER catalog_grant_freeze_update BEFORE UPDATE ON store_operator_grant FOR EACH ROW
BEGIN
 DECLARE freeze_state BOOLEAN DEFAULT FALSE;
 SELECT frozen INTO freeze_state FROM catalog_authority_route WHERE tenant_id=OLD.tenant_id FOR SHARE;
 IF freeze_state OR OLD.tenant_id <> NEW.tenant_id THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='legacy catalog grants frozen'; END IF;
END$$
CREATE TRIGGER catalog_grant_freeze_delete BEFORE DELETE ON store_operator_grant FOR EACH ROW
BEGIN
 DECLARE freeze_state BOOLEAN DEFAULT FALSE;
 SELECT frozen INTO freeze_state FROM catalog_authority_route WHERE tenant_id=OLD.tenant_id FOR SHARE;
 IF freeze_state THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='legacy catalog grants frozen'; END IF;
END$$
DELIMITER ;
