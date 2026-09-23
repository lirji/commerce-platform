CREATE TABLE inventory_stock (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 store_id VARCHAR(64) NOT NULL COMMENT '店铺引用',
 sku_id VARCHAR(64) NOT NULL COMMENT '商品引用',
 available BIGINT NOT NULL COMMENT '可售件数',
 held BIGINT NOT NULL COMMENT '已预占件数',
 sold BIGINT NOT NULL COMMENT '已确认销售件数',
 version BIGINT NOT NULL COMMENT '并发版本',
 PRIMARY KEY(tenant_id,store_id,sku_id),
 CHECK(available BETWEEN 0 AND 1000000000), CHECK(held BETWEEN 0 AND 1000000000), CHECK(sold BETWEEN 0 AND 1000000000), CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='店铺可售库存权威数量';
CREATE TABLE inventory_hold (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 order_id VARCHAR(64) NOT NULL COMMENT '订单引用',
 store_id VARCHAR(64) NOT NULL COMMENT '店铺引用',
 sku_id VARCHAR(64) NOT NULL COMMENT '商品引用',
 quantity INT NOT NULL COMMENT '预占整数件数',
 status VARCHAR(16) NOT NULL COMMENT 'RESERVED或CONFIRMED或RELEASED',
 PRIMARY KEY(tenant_id,order_id,sku_id),
 CHECK(quantity BETWEEN 1 AND 10000), CHECK(status IN ('RESERVED','CONFIRMED','RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='订单商品预占及终态';
CREATE TABLE order_record (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 order_id VARCHAR(64) NOT NULL COMMENT '订单标识',
 member_id VARCHAR(64) NOT NULL COMMENT '所属会员',
 store_id VARCHAR(64) NOT NULL COMMENT '店铺引用',
 merchant_id VARCHAR(64) NOT NULL COMMENT '商家引用',
 quote_id VARCHAR(64) NOT NULL COMMENT '一次性消费报价引用',
 payable DECIMAL(14,2) NOT NULL COMMENT '人民币应付金额',
 status VARCHAR(32) NOT NULL COMMENT '受状态机约束的订单状态',
 payment_kind VARCHAR(32) NOT NULL COMMENT 'CHANNEL_REQUIRED或NO_PAYMENT_REQUIRED',
 version BIGINT NOT NULL COMMENT '乐观版本',
 created_at DATETIME(3) NOT NULL COMMENT 'UTC创建时间',
 expires_at DATETIME(3) NOT NULL COMMENT 'UTC支付期限',
 items_json LONGTEXT NOT NULL COMMENT '不可变订单行及价格版本快照',
 address_cipher VARBINARY(4096) NOT NULL COMMENT 'AES-GCM地址密文含nonce',
 address_key_version INT NOT NULL COMMENT '地址密钥版本',
 PRIMARY KEY(tenant_id,order_id), UNIQUE KEY uk_order_quote(tenant_id,quote_id), KEY ix_order_member(tenant_id,member_id,order_id),
 KEY ix_order_expiry(status,expires_at,tenant_id,order_id),
 CHECK(payable>=0), CHECK(version>=0), CHECK(JSON_VALID(items_json)),
 CHECK(status IN ('PENDING_PAYMENT','PAYMENT_IN_PROGRESS','CLOSING','PAID','FULFILLING','COMPLETED','CANCELLED')),
 CHECK(payment_kind IN ('CHANNEL_REQUIRED','NO_PAYMENT_REQUIRED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='订单权威状态与成交快照';
CREATE TABLE platform_event (
 event_id VARCHAR(64) NOT NULL COMMENT '稳定事件标识',
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 event_type VARCHAR(64) NOT NULL COMMENT '带版本的事件类型',
 aggregate_id VARCHAR(64) NOT NULL COMMENT '聚合标识',
 aggregate_version BIGINT NOT NULL COMMENT '聚合版本',
 payload_json LONGTEXT NOT NULL COMMENT '最小业务载荷不含敏感地址',
 status VARCHAR(16) NOT NULL COMMENT 'PENDING或DELIVERED或ISOLATED',
 attempts INT NOT NULL DEFAULT 0 COMMENT '投递尝试次数',
 available_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC下次允许投递时间',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC事件发生时间',
 PRIMARY KEY(event_id), UNIQUE KEY uk_event_fact(tenant_id,event_type,aggregate_id,aggregate_version),
 KEY ix_event_delivery(status,available_at,event_id),
 CHECK(status IN ('PENDING','DELIVERED','ISOLATED')), CHECK(attempts>=0), CHECK(JSON_VALID(payload_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='与业务同事务的可靠事件Outbox';
