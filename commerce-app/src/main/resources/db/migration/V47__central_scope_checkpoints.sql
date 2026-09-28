CREATE TABLE central_scope_cursor (
 id VARCHAR(36) NOT NULL COMMENT '服务端生成的随机分页游标',
 tenant_id VARCHAR(80) NOT NULL COMMENT '显式身份桥接后的商城租户',
 principal_id VARCHAR(36) NOT NULL COMMENT '经过中央认证的申请主体',
 binding CHAR(64) NOT NULL COMMENT '主体代际及策略目录版本和完整范围摘要',
 resource_type VARCHAR(32) NOT NULL COMMENT '受控门店或商品资源类型',
 search VARCHAR(100) NOT NULL COMMENT '游标固定检索条件',
 after_id VARCHAR(100) NOT NULL COMMENT '授权过滤后的稳定资源主键游标',
 expires_at TIMESTAMP(3) NOT NULL COMMENT '服务端过期UTC时刻',
 PRIMARY KEY(tenant_id,id),INDEX ix_scope_cursor_expiry(expires_at)
) COMMENT='中央范围分页检查点，浏览器不能修改范围';
CREATE TABLE central_scope_quota (
 tenant_id VARCHAR(80) NOT NULL COMMENT '企业配额锁边界',
 PRIMARY KEY(tenant_id)
) COMMENT='导出提交短事务配额锁，不承载任何授权事实';
CREATE TABLE central_scope_export (
 id VARCHAR(36) NOT NULL COMMENT '持久导出任务UUID',
 tenant_id VARCHAR(80) NOT NULL COMMENT '商城可信租户',
 principal_id VARCHAR(36) NOT NULL COMMENT '中央申请主体，不接受代理下载',
 membership_id VARCHAR(36) NOT NULL COMMENT '提交时中央成员标识',
 generation BIGINT NOT NULL COMMENT '提交时成员代际',
 binding CHAR(64) NOT NULL COMMENT '授权上下文及范围版本摘要',
 resource_type VARCHAR(32) NOT NULL COMMENT '门店或商品类型',
 search VARCHAR(100) NOT NULL COMMENT '固定检索条件',
 state VARCHAR(16) NOT NULL COMMENT 'SUBMITTED/RUNNING/COMPLETED，过期由expires_at判定',
 after_id VARCHAR(100) NOT NULL COMMENT '已原子完成批次的末资源主键',
 row_count INT NOT NULL COMMENT '已持久化行数，上限1000',
 version BIGINT NOT NULL COMMENT '并发执行检查点版本',
 expires_at TIMESTAMP(3) NOT NULL COMMENT '下载及运行截止UTC时刻',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '提交UTC时刻',
 PRIMARY KEY(tenant_id,id),INDEX ix_scope_export_budget(tenant_id,principal_id,expires_at),INDEX ix_scope_export_expiry(expires_at),
 CHECK(generation>0 AND version>0 AND row_count BETWEEN 0 AND 1000),CHECK(state IN ('SUBMITTED','RUNNING','COMPLETED'))
) COMMENT='中央只读试点导出状态，Token与ALLOW不落库';
CREATE TABLE central_scope_export_row (
 tenant_id VARCHAR(80) NOT NULL COMMENT '与任务头相同的商城租户',
 job_id VARCHAR(36) NOT NULL COMMENT '所属导出任务',
 sequence_no INT NOT NULL COMMENT '稳定行号，随批次原子提交',
 resource_id VARCHAR(100) NOT NULL COMMENT '真实资源Owner主键',
 resource_version BIGINT NOT NULL COMMENT '生成时可信资源版本，下载复查',
 snapshot_json JSON NOT NULL COMMENT '有界展示字段快照，不包含Token',
 PRIMARY KEY(tenant_id,job_id,sequence_no),UNIQUE KEY uk_scope_export_resource(tenant_id,job_id,resource_id),
 FOREIGN KEY(tenant_id,job_id) REFERENCES central_scope_export(tenant_id,id),CHECK(sequence_no BETWEEN 1 AND 1000 AND resource_version>=0)
) COMMENT='私有导出行快照，仅经当前授权下载接口读取';
