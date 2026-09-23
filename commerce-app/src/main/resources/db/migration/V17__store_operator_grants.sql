ALTER TABLE platform_credential DROP CHECK ck_credential_role,
 ADD CONSTRAINT ck_credential_role CHECK(role IN ('ADMIN','MEMBER','OPERATOR'));
CREATE TABLE store_operator_grant (
 tenant_id VARCHAR(64) NOT NULL COMMENT '授权租户',
 grant_id VARCHAR(64) NOT NULL COMMENT '授权记录标识',
 actor_id VARCHAR(64) NOT NULL COMMENT '被授权运营主体',
 resource_type VARCHAR(16) NOT NULL COMMENT 'MERCHANT商家及其门店或STORE单店',
 resource_id VARCHAR(64) NOT NULL COMMENT '租户内商家或店铺标识',
 permission VARCHAR(32) NOT NULL COMMENT '经营动作CATALOG商品管理',
 active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '即时生效的授权开关',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
 reason VARCHAR(256) NOT NULL COMMENT '最近授权或撤销原因',
 updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '最近变更时间UTC',
 PRIMARY KEY(tenant_id,grant_id),
 UNIQUE KEY uk_operator_scope(tenant_id,actor_id,resource_type,resource_id,permission),
 CONSTRAINT ck_operator_grant CHECK(resource_type IN ('MERCHANT','STORE') AND permission='CATALOG' AND version>=0)
) COMMENT='商品经营资源授权平台管理员维护且每次操作直接校验';
