CREATE TABLE member_cycle_policy (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 version BIGINT NOT NULL COMMENT '不可变策略版本',
 effective_from DATETIME(3) NOT NULL COMMENT 'UTC生效时间及周期锚点',
 policy_json JSON NOT NULL COMMENT '周期天数及等级门槛快照',
 PRIMARY KEY(tenant_id,version),
 INDEX idx_cycle_effective(tenant_id,effective_from,version),
 CONSTRAINT ck_cycle_policy_version CHECK(version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='会员周期等级不可变策略';
CREATE TABLE member_cycle_contribution (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 source_id VARCHAR(128) NOT NULL COMMENT '带类型前缀的订单或人工调整唯一来源',
 member_id VARCHAR(64) NOT NULL COMMENT '会员标识',
 occurred_at DATETIME(3) NOT NULL COMMENT 'UTC原业务发生时间，退款不改变归属周期',
 contribution BIGINT NOT NULL COMMENT '来源当前净成长贡献，人工校准可为负',
 PRIMARY KEY(tenant_id,source_id),
 INDEX idx_cycle_sum(tenant_id,member_id,occurred_at),
 CONSTRAINT fk_cycle_contribution_member FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='周期考核来源净贡献，非积分资产';
CREATE TABLE member_cycle_account (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 member_id VARCHAR(64) NOT NULL COMMENT '会员标识',
 policy_version BIGINT NOT NULL COMMENT '当前考核策略版本',
 cycle_start DATETIME(3) NOT NULL COMMENT 'UTC周期开始包含边界',
 cycle_end DATETIME(3) NOT NULL COMMENT 'UTC周期结束不包含边界',
 current_growth BIGINT NOT NULL COMMENT '本周期净成长',
 retention_growth BIGINT NOT NULL COMMENT '上一周期用于保级的净成长',
 member_level VARCHAR(64) NOT NULL COMMENT '最后考核等级稳定代码',
 version BIGINT NOT NULL COMMENT '快照乐观版本',
 PRIMARY KEY(tenant_id,member_id),
 INDEX idx_cycle_due(cycle_end,tenant_id,member_id),
 CONSTRAINT fk_cycle_account_member FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id),
 CONSTRAINT fk_cycle_account_policy FOREIGN KEY(tenant_id,policy_version) REFERENCES member_cycle_policy(tenant_id,version),
 CONSTRAINT ck_cycle_interval CHECK(cycle_end>cycle_start),
 CONSTRAINT ck_cycle_account_version CHECK(version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='会员当前周期与保级考核快照';
