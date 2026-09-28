CREATE TABLE central_store_identity_binding (
    auth_tenant_id VARCHAR(36) NOT NULL COMMENT '中央治理企业，不能直接假定等于商城租户',
    principal_id VARCHAR(36) NOT NULL COMMENT '已验证中央主体',
    membership_id VARCHAR(36) NOT NULL COMMENT '中央成员关系',
    generation BIGINT NOT NULL COMMENT '绑定成员代际，退出重入不继承旧映射',
    tenant_id VARCHAR(80) NOT NULL COMMENT '显式映射的商城租户',
    actor_id VARCHAR(80) NOT NULL COMMENT '原商城运营身份，保留既有业务引用',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '本地接入开关，停用后实时拒绝',
    created_by VARCHAR(80) NOT NULL COMMENT '受控映射登记操作员',
    created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '登记UTC时刻',
    PRIMARY KEY(auth_tenant_id,principal_id,membership_id,generation),
    CHECK(generation > 0),
    INDEX ix_central_store_actor(tenant_id,actor_id)
) COMMENT='中央只读试点身份桥，不修改旧凭据或授予全局管理员';
