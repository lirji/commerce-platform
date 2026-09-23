CREATE TABLE member_point_policy (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 version BIGINT NOT NULL COMMENT '不可变策略版本',
 effective_from DATETIME(3) NOT NULL COMMENT 'UTC新订单策略生效时间',
 policy_json JSON NOT NULL COMMENT '获取有效期及消费配置快照',
 PRIMARY KEY(tenant_id,version),
 INDEX idx_point_policy_effective(tenant_id,effective_from,version),
 CHECK(version>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='独立积分获取与消费策略';
CREATE TABLE member_point_account (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 member_id VARCHAR(64) NOT NULL COMMENT '会员标识',
 debt BIGINT NOT NULL DEFAULT 0 COMMENT '已消费奖励扣回后待偿积分，不是现金债务',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '账户并发版本',
 PRIMARY KEY(tenant_id,member_id),
 FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id),
 CHECK(debt>=0 AND debt<=9000000000000000 AND version>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='会员积分账户版本与待偿扣回';
CREATE TABLE member_point_order (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 order_id VARCHAR(64) NOT NULL COMMENT '权威来源订单标识',
 member_id VARCHAR(64) NOT NULL COMMENT '奖励会员标识',
 paid DECIMAL(18,2) NOT NULL COMMENT '原订单现金实付金额',
 completed BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已观察到订单完成事实',
 policy_version BIGINT NOT NULL COMMENT '下单时积分获取规则版本，0表示未配置',
 earn_rate DECIMAL(8,2) NOT NULL COMMENT '原每元现金净消费奖励积分率',
 expiry_days INT NOT NULL COMMENT '原规则入账有效天数',
 contribution BIGINT NOT NULL DEFAULT 0 COMMENT '来源当前净贡献积分',
 PRIMARY KEY(tenant_id,order_id),
 INDEX idx_point_order_member(tenant_id,member_id),
 FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id),
 CHECK(paid>=0 AND earn_rate>=0 AND contribution>=0 AND contribution<=1000000000000)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='订单积分净贡献，退款保持原规则';
CREATE TABLE member_point_refund (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 refund_id VARCHAR(64) NOT NULL COMMENT '权威退款幂等标识',
 order_id VARCHAR(64) NOT NULL COMMENT '来源订单',
 amount DECIMAL(18,2) NOT NULL COMMENT '现金成功退款金额',
 PRIMARY KEY(tenant_id,refund_id),
 INDEX idx_point_refund_order(tenant_id,order_id),
 FOREIGN KEY(tenant_id,order_id) REFERENCES member_point_order(tenant_id,order_id),
 CHECK(amount>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='积分奖励退款事实去重';
CREATE TABLE member_point_lot (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 lot_id VARCHAR(64) NOT NULL COMMENT '奖励来源摘要，按订单或调整唯一',
 member_id VARCHAR(64) NOT NULL COMMENT '会员标识',
 policy_version BIGINT NOT NULL COMMENT '原获取策略版本',
 credited BIGINT NOT NULL COMMENT '原批次总入账积分，包含抵偿扣回部分',
 remaining BIGINT NOT NULL COMMENT '未使用积分，到期后由任务归档',
 held BIGINT NOT NULL DEFAULT 0 COMMENT '订单冻结积分，消费链路后续接入',
 expired BIGINT NOT NULL DEFAULT 0 COMMENT '已过期且尚未被退款免扣抵消的积分',
 expires_at DATETIME(3) NOT NULL COMMENT 'UTC原有效期截止，不随退款延长',
 PRIMARY KEY(tenant_id,lot_id),
 INDEX idx_point_lot_member(tenant_id,member_id,expires_at,lot_id),
 INDEX idx_point_lot_expiry(expires_at,tenant_id,lot_id),
 FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id),
 CHECK(credited>=0 AND remaining>=0 AND held>=0 AND expired>=0 AND remaining+held+expired<=credited)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='会员积分来源批次及有效期';
CREATE TABLE member_point_ledger (
 sequence_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '稳定递增账本游标',
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 member_id VARCHAR(64) NOT NULL COMMENT '会员标识',
 action VARCHAR(24) NOT NULL COMMENT '稳定动作码EARN、ADJUST、REVOKE、EXPIRE',
 source_id VARCHAR(128) NOT NULL COMMENT '业务来源订单、退款或调整摘要',
 delta BIGINT NOT NULL COMMENT '净积分资产变动，已过期免扣部分不重复扣回',
 available BIGINT NOT NULL COMMENT '变动后可用积分快照',
 debt BIGINT NOT NULL COMMENT '变动后待偿扣回积分快照',
 policy_version BIGINT NOT NULL COMMENT '相关获取策略版本，0表示人工扣回',
 reason VARCHAR(256) NOT NULL COMMENT '中文业务原因或人工校准说明',
 created_at DATETIME(3) NOT NULL COMMENT 'UTC业务入账时间',
 PRIMARY KEY(sequence_id),
 INDEX idx_point_ledger_member(tenant_id,member_id,sequence_id),
 FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id),
 CHECK(available>=0 AND debt>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='不可变积分账本，变化与账户同事务';
