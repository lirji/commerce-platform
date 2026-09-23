CREATE TABLE ops_page (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 page_id VARCHAR(64) NOT NULL COMMENT '运营页面标识',
 version BIGINT NOT NULL COMMENT '不可变DSL内容版本',
 definition_json JSON NOT NULL COMMENT '通过组件数据源和动作白名单校验的页面定义',
 status VARCHAR(32) NOT NULL DEFAULT 'DRAFT' COMMENT '审批与发布状态',
 lock_version BIGINT NOT NULL DEFAULT 0 COMMENT '状态乐观并发版本',
 published_id VARCHAR(64) GENERATED ALWAYS AS (CASE WHEN status='PUBLISHED' THEN page_id ELSE NULL END) STORED COMMENT '每页面单发布版本约束',
 PRIMARY KEY(tenant_id,page_id,version),
 UNIQUE KEY uk_ops_page_published(tenant_id,published_id),
 CONSTRAINT ck_ops_page CHECK(version>0 AND status IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','PUBLISHED','PAUSED'))
) COMMENT='低代码运营页面版本和发布审批';
