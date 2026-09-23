CREATE TABLE catalog_category (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 store_id VARCHAR(64) NOT NULL COMMENT '所属门店',
 category_id VARCHAR(64) NOT NULL COMMENT '门店类目标识',
 parent_id VARCHAR(64) NULL COMMENT '父类目，空为根级',
 name VARCHAR(64) NOT NULL COMMENT '类目名称',
 depth INT NOT NULL COMMENT '从1开始的层级，最多三级',
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE可绑定、RETIRED停用',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '乐观版本',
 PRIMARY KEY(tenant_id,store_id,category_id),
 FOREIGN KEY(tenant_id,store_id,parent_id) REFERENCES catalog_category(tenant_id,store_id,category_id),
 KEY ix_category_parent(tenant_id,store_id,parent_id,status),
 CHECK(depth BETWEEN 1 AND 3 AND status IN ('ACTIVE','RETIRED') AND version>=0)
) COMMENT='门店商品分类树，父级不可换绑';
CREATE TABLE catalog_specification_template (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 store_id VARCHAR(64) NOT NULL COMMENT '所属门店',
 template_id VARCHAR(64) NOT NULL COMMENT '规格模板标识',
 version BIGINT NOT NULL COMMENT '不可变模板版本',
 content_json JSON NOT NULL COMMENT '名称与允许的属性值集合',
 PRIMARY KEY(tenant_id,store_id,template_id,version),
 CHECK(version>0)
) COMMENT='商品规格模板不可变版本';
CREATE TABLE catalog_product_profile (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 store_id VARCHAR(64) NOT NULL COMMENT '所属门店',
 product_id VARCHAR(64) NOT NULL COMMENT '商品SPU标识',
 category_id VARCHAR(64) NULL COMMENT '结构化类目，旧文字分类仍保留',
 template_id VARCHAR(64) NULL COMMENT '首次绑定后固定的模板',
 template_version BIGINT NULL COMMENT '固定模板版本',
 description TEXT NOT NULL COMMENT '纯文本商品说明',
 images_json JSON NOT NULL COMMENT '最多6张图片地址与替代文字',
 version BIGINT NOT NULL COMMENT '经营详情独立乐观版本',
 PRIMARY KEY(tenant_id,product_id),
 FOREIGN KEY(tenant_id,product_id) REFERENCES catalog_product(tenant_id,product_id),
 FOREIGN KEY(tenant_id,store_id,category_id) REFERENCES catalog_category(tenant_id,store_id,category_id),
 FOREIGN KEY(tenant_id,store_id,template_id,template_version) REFERENCES catalog_specification_template(tenant_id,store_id,template_id,version),
 KEY ix_profile_category(tenant_id,store_id,category_id,product_id),
 CHECK(version>0 AND ((template_id IS NULL AND template_version IS NULL) OR (template_id IS NOT NULL AND template_version>0)))
) COMMENT='商品详情与模板类目绑定，不改成交价格';
CREATE TABLE catalog_sku_barcode (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 store_id VARCHAR(64) NOT NULL COMMENT '所属门店',
 sku_id VARCHAR(64) NOT NULL COMMENT '销售规格标识',
 barcode VARCHAR(64) NULL COMMENT '规范大写经营条码，空值允许多个',
 version BIGINT NOT NULL COMMENT '条码独立乐观版本',
 PRIMARY KEY(tenant_id,sku_id),
 UNIQUE KEY uk_store_barcode(tenant_id,store_id,barcode),
 FOREIGN KEY(tenant_id,sku_id) REFERENCES catalog_sku(tenant_id,sku_id),
 CHECK(version>0)
) COMMENT='门店唯一SKU经营条码';
