ALTER TABLE store_opening_category
  ADD COLUMN node_kind VARCHAR(8) NOT NULL DEFAULT 'LEAF' COMMENT 'BRANCH分组或LEAF可绑定分类',
  ADD COLUMN depth TINYINT UNSIGNED NOT NULL DEFAULT 2 COMMENT '从1开始的类目层级',
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT '维护变更的乐观锁版本',
  ADD COLUMN sibling_key VARCHAR(64) GENERATED ALWAYS AS (COALESCE(parent_code,'')) STORED COMMENT '同层唯一名称键，根层用空串',
  ADD UNIQUE KEY uk_platform_category_sibling_name (sibling_key,name);

UPDATE store_opening_category SET node_kind='BRANCH',depth=1 WHERE parent_code IS NULL;

ALTER TABLE store_opening_category
  ADD CONSTRAINT ck_platform_category_node CHECK (
    (parent_code IS NULL AND depth=1 AND node_kind='BRANCH')
    OR (parent_code IS NOT NULL AND depth=2 AND node_kind IN ('BRANCH','LEAF'))
    OR (parent_code IS NOT NULL AND depth=3 AND node_kind='LEAF')
  ),
  ADD CONSTRAINT ck_platform_category_version CHECK (version>=0);

ALTER TABLE store_record
  MODIFY COLUMN opening_category VARCHAR(192) NULL COMMENT '开店申报快照，最多三级类目路径';

ALTER TABLE catalog_category
  ADD COLUMN platform_category_code VARCHAR(64) COLLATE utf8mb4_bin NULL COMMENT '关联的平台末级类目代码，历史门店分类为空',
  ADD CONSTRAINT fk_catalog_platform_category FOREIGN KEY (platform_category_code) REFERENCES store_opening_category(category_code);

CREATE TABLE platform_category_curator (
  tenant_id VARCHAR(64) NOT NULL COMMENT '平台凭据归属租户标识',
  actor_id VARCHAR(64) NOT NULL COMMENT '获授平台类目维护权的运营身份',
  active BOOLEAN NOT NULL DEFAULT TRUE COMMENT '授予维护权或暂停授予',
  granted_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC授予时间',
  PRIMARY KEY (tenant_id,actor_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='平台类目专岗写权限，独立于平台只读运维权限';

CREATE TABLE platform_category_history (
  category_code VARCHAR(64) NOT NULL COMMENT '类目稳定代码',
  version BIGINT NOT NULL COMMENT '提交后的类目版本',
  name VARCHAR(56) NOT NULL COMMENT '提交后的类目名称',
  sort_order SMALLINT UNSIGNED NOT NULL COMMENT '提交后的排序值',
  enabled BOOLEAN NOT NULL COMMENT '提交后的本节点启停状态',
  actor_tenant_id VARCHAR(64) NOT NULL COMMENT '操作者凭据所属租户',
  actor_id VARCHAR(64) NOT NULL COMMENT '提交变更的受信操作者',
  reason VARCHAR(256) NOT NULL COMMENT '本次调整原因',
  changed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT 'UTC提交时间',
  PRIMARY KEY (category_code,version),
  CONSTRAINT fk_platform_category_history FOREIGN KEY (category_code) REFERENCES store_opening_category(category_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='类目变更快照，一次有效修改追加一条';
