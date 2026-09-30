CREATE TABLE employee_command_identity (
 tenant_id VARCHAR(64) NOT NULL COMMENT '商城命令所属可信租户',
 actor_id VARCHAR(64) NOT NULL COMMENT '保留原业务引用的本地操作员',
 operation VARCHAR(64) NOT NULL COMMENT '成功提交的用例代码',
 command_key VARCHAR(64) NOT NULL COMMENT '幂等命令键，同命令只留一次归属证据',
 principal_id VARCHAR(36) NOT NULL COMMENT '中央验证的OA权威主体',
 membership_id VARCHAR(36) NOT NULL COMMENT '操作时中央成员关系',
 generation BIGINT NOT NULL COMMENT '操作时成员代际，重入不覆盖历史',
 execution_id VARCHAR(36) NOT NULL COMMENT '不含Token的中央执行引用，仅供审计关联',
 capability VARCHAR(100) NOT NULL COMMENT '本命令精确使用的中央能力',
 store_id VARCHAR(100) NOT NULL COMMENT 'Owner读取的真实目标门店',
 route_version BIGINT NOT NULL COMMENT '事务锁定的接管路由版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '命令提交UTC记录时刻',
 PRIMARY KEY(tenant_id,actor_id,operation,command_key),
 CONSTRAINT fk_employee_command FOREIGN KEY(tenant_id,actor_id,operation,command_key) REFERENCES platform_command(tenant_id,actor_id,operation,command_key),
 CHECK(generation > 0),
 CHECK(route_version > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='中央员工成功命令的不可变身份归属；随原命令生命周期保留，不另行自动清理';
