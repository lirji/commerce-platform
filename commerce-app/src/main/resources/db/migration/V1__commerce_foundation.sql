CREATE TABLE platform_command (
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户稳定标识',
  actor_id VARCHAR(64) NOT NULL COMMENT '可信操作者',
  operation VARCHAR(64) NOT NULL COMMENT '用例代码',
  command_key VARCHAR(64) NOT NULL COMMENT '调用方幂等键',
  request_hash CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '规范化请求SHA256',
  response_json LONGTEXT NULL COMMENT '已提交命令结果快照',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY(tenant_id,actor_id,operation,command_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='事务命令与幂等结果';

CREATE TABLE platform_audit (
  id BIGINT NOT NULL AUTO_INCREMENT COMMENT '审计流水',
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户稳定标识',
  actor_id VARCHAR(64) NOT NULL COMMENT '可信操作者',
  operation VARCHAR(64) NOT NULL COMMENT '用例代码',
  command_key VARCHAR(64) NOT NULL COMMENT '关联命令键',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY(id),
  KEY ix_audit_tenant(tenant_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='业务命令审计';

CREATE TABLE platform_credential (
  token_hash CHAR(64) CHARACTER SET ascii NOT NULL COMMENT '随机令牌SHA256不存明文',
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户稳定标识',
  actor_id VARCHAR(64) NOT NULL COMMENT '业务主体标识',
  role VARCHAR(16) NOT NULL COMMENT 'ADMIN或MEMBER',
  expires_at DATETIME(3) NOT NULL COMMENT 'UTC失效时间',
  active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否有效',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY(token_hash),
  CONSTRAINT ck_credential_role CHECK(role IN ('ADMIN','MEMBER'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='本地隔离环境访问凭据';

CREATE TABLE member_record (
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户稳定标识',
  member_id VARCHAR(64) NOT NULL COMMENT '会员标识',
  actor_id VARCHAR(64) NOT NULL COMMENT '身份主体绑定',
  display_name VARCHAR(128) NOT NULL COMMENT '会员显示名',
  member_level VARCHAR(64) NOT NULL COMMENT '受管理会员等级事实',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '资源状态ACTIVE或FROZEN',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY(tenant_id,member_id),
  UNIQUE KEY uk_member_actor(tenant_id,actor_id),
  CHECK(status IN ('ACTIVE','FROZEN')),
  CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='会员权威主数据';

CREATE TABLE merchant_record (
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户稳定标识',
  merchant_id VARCHAR(64) NOT NULL COMMENT '商家标识',
  name VARCHAR(128) NOT NULL COMMENT '商家名称',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '资源状态ACTIVE或FROZEN',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY(tenant_id,merchant_id),
  CHECK(status IN ('ACTIVE','FROZEN')),
  CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='商家权威主数据';

CREATE TABLE store_record (
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户稳定标识',
  store_id VARCHAR(64) NOT NULL COMMENT '店铺标识',
  merchant_id VARCHAR(64) NOT NULL COMMENT '所属商家引用由商家端口校验',
  name VARCHAR(128) NOT NULL COMMENT '店铺名称',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '资源状态ACTIVE或FROZEN',
  version BIGINT NOT NULL DEFAULT 0 COMMENT '并发版本',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY(tenant_id,store_id),
  KEY ix_store_merchant(tenant_id,merchant_id,store_id),
  CHECK(status IN ('ACTIVE','FROZEN')),
  CHECK(version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='店铺权威主数据';

CREATE TABLE catalog_sku (
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户稳定标识',
  sku_id VARCHAR(64) NOT NULL COMMENT 'SKU稳定标识',
  store_id VARCHAR(64) NOT NULL COMMENT '销售店铺引用',
  title VARCHAR(128) NOT NULL COMMENT '商品名称',
  unit_price DECIMAL(14,2) NOT NULL COMMENT '人民币单位价格',
  revision BIGINT NOT NULL COMMENT '发布版本',
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT '资源状态ACTIVE或FROZEN',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY(tenant_id,sku_id),
  KEY ix_sku_store(tenant_id,store_id,sku_id),
  CHECK(unit_price>=0),
  CHECK(revision>0),
  CHECK(status IN ('ACTIVE','FROZEN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='商品SKU发布快照';

CREATE TABLE marketing_campaign (
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户稳定标识',
  campaign_id VARCHAR(64) NOT NULL COMMENT '活动稳定标识',
  version BIGINT NOT NULL COMMENT '不可变内容版本',
  store_id VARCHAR(64) NOT NULL COMMENT '生效店铺',
  merchant_id VARCHAR(64) NOT NULL COMMENT '生效商家',
  name VARCHAR(128) NOT NULL COMMENT '活动名称',
  valid_from DATETIME(3) NOT NULL COMMENT 'UTC有效期含起点',
  valid_to DATETIME(3) NOT NULL COMMENT 'UTC有效期不含终点',
  minimum_spend DECIMAL(14,2) NOT NULL COMMENT '人民币满额门槛',
  discount_amount DECIMAL(14,2) NOT NULL COMMENT '人民币固定减免金额',
  rule_json TEXT NOT NULL COMMENT '受限条件树JSON',
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT或PUBLISHED或PAUSED',
  lock_version BIGINT NOT NULL DEFAULT 0 COMMENT '发布状态并发版本',
  active_campaign_id VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN campaign_id ELSE NULL END) STORED COMMENT '同活动单个发布版本约束',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY(tenant_id,campaign_id,version),
  KEY ix_campaign_store(tenant_id,store_id,status),
  UNIQUE KEY uk_campaign_active(tenant_id,active_campaign_id),
  CHECK(status IN ('DRAFT','PUBLISHED','PAUSED')),
  CHECK(version>0 AND lock_version>=0),
  CHECK(minimum_spend>=0 AND discount_amount>0),
  CHECK(valid_from<valid_to)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='营销活动不可变业务版本';

CREATE TABLE trade_quote (
  tenant_id VARCHAR(64) NOT NULL COMMENT '租户稳定标识',
  quote_id VARCHAR(64) NOT NULL COMMENT '报价标识',
  member_id VARCHAR(64) NOT NULL COMMENT '报价所属会员',
  store_id VARCHAR(64) NOT NULL COMMENT '报价店铺',
  merchant_id VARCHAR(64) NOT NULL COMMENT '报价商家',
  gross_amount DECIMAL(14,2) NOT NULL COMMENT '原总金额',
  discount_amount DECIMAL(14,2) NOT NULL COMMENT '优惠总金额',
  payable_amount DECIMAL(14,2) NOT NULL COMMENT '应付总金额',
  snapshot_json LONGTEXT NOT NULL COMMENT '完整报价与版本快照',
  expires_at DATETIME(3) NOT NULL COMMENT 'UTC报价失效时间',
  consumed_order_id VARCHAR(64) NULL COMMENT '消费该报价的订单后续原子绑定',
  created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC创建时间',
  PRIMARY KEY(tenant_id,quote_id),
  KEY ix_quote_member(tenant_id,member_id,quote_id),
  CHECK(gross_amount>=0 AND discount_amount>=0 AND payable_amount>=0),
  CHECK(gross_amount=discount_amount+payable_amount)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='交易报价不可变快照';

