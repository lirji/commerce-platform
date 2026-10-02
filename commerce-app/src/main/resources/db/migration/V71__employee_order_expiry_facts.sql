CREATE TABLE employee_order_expiry_fact (
 tenant_id VARCHAR(64) NOT NULL COMMENT '订单与命令所属真实商城租户',
 actor_id VARCHAR(64) NOT NULL COMMENT '原手工批次操作员',
 operation VARCHAR(64) NOT NULL COMMENT '固定 order.expire 用例',
 command_key VARCHAR(64) NOT NULL COMMENT '原幂等命令键，重试不替换批次事实',
 order_id VARCHAR(100) NOT NULL COMMENT '该命令实际提交到期转换的原订单',
 store_id VARCHAR(100) NOT NULL COMMENT '订单 Owner 提供的真实门店',
 store_version BIGINT NOT NULL COMMENT '提交时锁定核对的真实门店版本',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '本地事务提交 UTC 记录时刻',
 PRIMARY KEY(tenant_id,actor_id,operation,command_key,order_id),
 CONSTRAINT fk_expiry_fact_command FOREIGN KEY(tenant_id,actor_id,operation,command_key) REFERENCES platform_command(tenant_id,actor_id,operation,command_key),
 CHECK(operation='order.expire'),
 CHECK(store_version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='手工订单到期批次实际订单及门店事实，与原命令同事务保留';
