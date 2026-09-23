CREATE TABLE catalog_product (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 product_id VARCHAR(64) NOT NULL COMMENT '经营商品SPU标识',
 store_id VARCHAR(64) NOT NULL COMMENT '拥有该商品的门店',
 title VARCHAR(128) NOT NULL COMMENT '商品展示名称',
 category VARCHAR(64) NOT NULL COMMENT '运营分类',
 brand VARCHAR(64) NOT NULL COMMENT '品牌名称',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '资料并发版本',
 PRIMARY KEY(tenant_id,product_id),KEY ix_product_store(tenant_id,store_id,product_id),
 CONSTRAINT ck_product_version CHECK(version>=0)
) COMMENT='门店经营商品SPU主数据';
ALTER TABLE catalog_sku
 ADD COLUMN product_id VARCHAR(64) NULL COMMENT 'SPU引用历史SKU允许为空',
 ADD COLUMN specifications_json JSON NULL COMMENT '规范化规格名值对创建后不可变',
 ADD COLUMN specification_key CHAR(64) NULL COMMENT '有序规格组合SHA256用于唯一约束',
 ADD UNIQUE KEY uk_product_specification(tenant_id,product_id,specification_key),
 ADD CONSTRAINT ck_sku_product_spec CHECK((product_id IS NULL AND specifications_json IS NULL AND specification_key IS NULL) OR (product_id IS NOT NULL AND specifications_json IS NOT NULL AND specification_key IS NOT NULL));
CREATE TABLE catalog_revision (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 sku_id VARCHAR(64) NOT NULL COMMENT 'SKU标识',
 revision BIGINT NOT NULL COMMENT '商品修订版本',
 store_id VARCHAR(64) NOT NULL COMMENT '门店标识快照',
 title VARCHAR(128) NOT NULL COMMENT '商品标题快照',
 unit_price DECIMAL(14,2) NOT NULL COMMENT '人民币售价快照',
 status VARCHAR(16) NOT NULL COMMENT 'ACTIVE上架FROZEN下架',
 reason VARCHAR(256) NOT NULL COMMENT '修订原因',
 actor_id VARCHAR(64) NOT NULL COMMENT '操作主体历史回填SYSTEM',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '修订记录时间UTC',
 PRIMARY KEY(tenant_id,sku_id,revision),
 CONSTRAINT ck_catalog_revision CHECK(revision>0 AND unit_price>=0 AND status IN ('ACTIVE','FROZEN'))
) COMMENT='商品标题售价上下架不可变修订记录';
INSERT INTO catalog_revision(tenant_id,sku_id,revision,store_id,title,unit_price,status,reason,actor_id)
 SELECT tenant_id,sku_id,revision,store_id,title,unit_price,status,'迁移时现有快照','SYSTEM' FROM catalog_sku;
