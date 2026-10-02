CREATE TABLE employee_runtime_replay_source (
 tenant_id VARCHAR(64) NOT NULL COMMENT '回放任务所属原可信租户',
 job_id VARCHAR(64) NOT NULL COMMENT '原回放任务标识，控制动作不得替换来源',
 source_json LONGTEXT NOT NULL COMMENT '原手工 Actor 与中央有限引用准确元数据，不含 Token 或缓存 ALLOW',
 created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '原来源记录的 UTC 时刻',
 PRIMARY KEY(tenant_id,job_id),
 CONSTRAINT fk_replay_employee_source FOREIGN KEY(tenant_id,job_id) REFERENCES platform_replay(tenant_id,job_id),
 CHECK(JSON_VALID(source_json))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='回放手工来源不可替换证据；系统调度不授予员工或系统新权限';

-- 原接口创建者必为租户 ADMIN：只标记实际历史 created_by 为 LEGACY，中央接管后该旧来源不能继续。
-- 不签发新引用，不将旧员工任务提升为 SYSTEM；原时间与任务标识保留。
INSERT INTO employee_runtime_replay_source(tenant_id,job_id,source_json,created_at)
SELECT tenant_id,job_id,JSON_OBJECT('kind','LEGACY','tenant',tenant_id,'jobId',job_id,
 'actor',JSON_OBJECT('tenantId',tenant_id,'actorId',created_by,'role','ADMIN','channel','WEB','executionId',NULL),
 'execution',NULL,'key',NULL,'createdAt',DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%fZ')),created_at
FROM platform_replay;
