ALTER TABLE platform_credential ADD COLUMN sales_channel VARCHAR(16) NOT NULL DEFAULT 'WEB' COMMENT '受控认证销售渠道';
ALTER TABLE order_record ADD COLUMN sales_channel VARCHAR(16) NOT NULL DEFAULT 'WEB' COMMENT '成交时可信销售渠道';
CREATE TABLE catalog_channel_price (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 store_id VARCHAR(64) NOT NULL COMMENT '所属门店',
 sku_id VARCHAR(64) NOT NULL COMMENT '销售规格',
 channel VARCHAR(16) NOT NULL COMMENT '认证销售渠道WEB或MINI_APP',
 version BIGINT NOT NULL COMMENT '渠道价格版本',
 unit_price DECIMAL(14,2) NOT NULL COMMENT '精确人民币售价',
 valid_from TIMESTAMP(3) NOT NULL COMMENT 'UTC生效时间',
 valid_to TIMESTAMP(3) NOT NULL COMMENT 'UTC失效时间不包含端点',
 active BOOLEAN NOT NULL COMMENT '运营启用标记',
 reason VARCHAR(256) NOT NULL COMMENT '变更原因',
 actor_id VARCHAR(64) NOT NULL COMMENT '操作主体',
 changed_at TIMESTAMP(3) NOT NULL COMMENT 'UTC变更时间',
 PRIMARY KEY (tenant_id,store_id,sku_id,channel),
 CHECK (channel IN ('WEB','MINI_APP')),
 CHECK (unit_price>=0 AND version>0 AND valid_to>valid_from)
) COMMENT='当前渠道价权威表';
CREATE TABLE catalog_channel_price_revision (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 store_id VARCHAR(64) NOT NULL COMMENT '所属门店',
 sku_id VARCHAR(64) NOT NULL COMMENT '销售规格',
 channel VARCHAR(16) NOT NULL COMMENT '认证销售渠道WEB或MINI_APP',
 version BIGINT NOT NULL COMMENT '渠道价格版本',
 unit_price DECIMAL(14,2) NOT NULL COMMENT '精确人民币售价',
 valid_from TIMESTAMP(3) NOT NULL COMMENT 'UTC生效时间',
 valid_to TIMESTAMP(3) NOT NULL COMMENT 'UTC失效时间不包含端点',
 active BOOLEAN NOT NULL COMMENT '运营启用标记',
 reason VARCHAR(256) NOT NULL COMMENT '变更原因',
 actor_id VARCHAR(64) NOT NULL COMMENT '操作主体',
 changed_at TIMESTAMP(3) NOT NULL COMMENT 'UTC变更时间',
 PRIMARY KEY (tenant_id,store_id,sku_id,channel,version),
 CHECK (channel IN ('WEB','MINI_APP')),
 CHECK (unit_price>=0 AND version>0 AND valid_to>valid_from)
) COMMENT='不可变渠道价修订';
CREATE TABLE catalog_operation_job (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 job_id VARCHAR(64) NOT NULL COMMENT '经营批次标识',
 store_id VARCHAR(64) NOT NULL COMMENT '门店边界',
 creator_json JSON NOT NULL COMMENT '创建时授权主体和角色',
 content_json JSON NOT NULL COMMENT '不可变目标规格和预期版本',
 status VARCHAR(16) NOT NULL COMMENT '持久任务状态',
 run_at TIMESTAMP(3) NOT NULL COMMENT 'UTC计划开始时间',
 deadline TIMESTAMP(3) NOT NULL COMMENT 'UTC执行截止时间',
 available_at TIMESTAMP(3) NOT NULL COMMENT 'UTC下次允许推进时间',
 cursor_index INT NOT NULL DEFAULT 0 COMMENT '已提交目标数量',
 succeeded INT NOT NULL DEFAULT 0 COMMENT '成功目标数量',
 conflicted INT NOT NULL DEFAULT 0 COMMENT '版本冲突或已缺失数量',
 attempts INT NOT NULL DEFAULT 0 COMMENT '当前目标连续失败次数',
 error_code VARCHAR(32) NULL COMMENT '安全错误码不存内部异常',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '控制并发版本',
 PRIMARY KEY(tenant_id,job_id),
 INDEX ix_catalog_job_due(status,available_at,tenant_id),
 INDEX ix_catalog_job_store(tenant_id,store_id,job_id),
 CHECK(cursor_index>=0 AND cursor_index<=100 AND attempts>=0 AND attempts<=5)
) COMMENT='逐项可恢复的批量定时商品经营任务';
CREATE TABLE catalog_operation_item (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 job_id VARCHAR(64) NOT NULL COMMENT '所属批次',
 item_index INT NOT NULL COMMENT '输入目标稳定序号从1开始',
 sku_id VARCHAR(64) NOT NULL COMMENT '目标规格',
 status VARCHAR(16) NOT NULL COMMENT 'SUCCEEDED或CONFLICT',
 expected_revision BIGINT NOT NULL COMMENT '固定预期规格版本',
 actual_revision BIGINT NULL COMMENT '冲突时当前版本或执行后的版本',
 reason VARCHAR(64) NOT NULL COMMENT '可展示的结果原因码',
 processed_at TIMESTAMP(3) NOT NULL COMMENT 'UTC提交时间',
 PRIMARY KEY(tenant_id,job_id,item_index),
 UNIQUE KEY uk_catalog_job_sku(tenant_id,job_id,sku_id),
 FOREIGN KEY(tenant_id,job_id) REFERENCES catalog_operation_job(tenant_id,job_id)
) COMMENT='与商品效果及检查点同事务提交的逐项回执';
