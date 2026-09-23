ALTER TABLE member_point_ledger
 ADD COLUMN held BIGINT NOT NULL DEFAULT 0 COMMENT '变动后冻结积分快照，旧记录为0',
 MODIFY COLUMN action VARCHAR(24) NOT NULL COMMENT '稳定动作码EARN、ADJUST、REVOKE、EXPIRE、HOLD、SPEND、RELEASE、REFUND';
CREATE TABLE member_point_hold (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 order_id VARCHAR(64) NOT NULL COMMENT '订单消费引用，不跨域建立外键',
 member_id VARCHAR(64) NOT NULL COMMENT '积分会员标识',
 policy_version BIGINT NOT NULL COMMENT '报价采用的消费策略版本',
 points BIGINT NOT NULL COMMENT '原冻结整数积分',
 discount DECIMAL(18,2) NOT NULL COMMENT '原抵扣金额CNY',
 status VARCHAR(16) NOT NULL COMMENT 'RESERVED冻结、CONSUMED核销、RELEASED释放',
 returned_points BIGINT NOT NULL DEFAULT 0 COMMENT '已成功售后返还的名义积分，含原期限失效部分',
 PRIMARY KEY(tenant_id,order_id),
 INDEX idx_point_hold_member(tenant_id,member_id),
 FOREIGN KEY(tenant_id,member_id) REFERENCES member_record(tenant_id,member_id),
 CHECK(points>0 AND discount>0 AND returned_points>=0 AND returned_points<=points),
 CHECK(status IN ('RESERVED','CONSUMED','RELEASED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='订单积分持有状态与名义退款上限';
CREATE TABLE member_point_allocation (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 order_id VARCHAR(64) NOT NULL COMMENT '持有引用订单',
 lot_id VARCHAR(64) NOT NULL COMMENT '原积分来源批次',
 points BIGINT NOT NULL COMMENT '原分配积分',
 sequence_no INT NOT NULL COMMENT 'FIFO确定顺序，最多200批次',
 returned_points BIGINT NOT NULL DEFAULT 0 COMMENT '此批次已返还的名义积分',
 PRIMARY KEY(tenant_id,order_id,lot_id),
 UNIQUE KEY uk_point_allocation_sequence(tenant_id,order_id,sequence_no),
 FOREIGN KEY(tenant_id,order_id) REFERENCES member_point_hold(tenant_id,order_id),
 FOREIGN KEY(tenant_id,lot_id) REFERENCES member_point_lot(tenant_id,lot_id),
 CHECK(points>0 AND returned_points>=0 AND returned_points<=points AND sequence_no BETWEEN 0 AND 199)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='订单冻结来源分配，返还沿用原有效期';
CREATE TABLE member_point_return (
 tenant_id VARCHAR(64) NOT NULL COMMENT '租户标识',
 case_id VARCHAR(64) NOT NULL COMMENT '成功售后唯一来源',
 order_id VARCHAR(64) NOT NULL COMMENT '原积分持有订单',
 points BIGINT NOT NULL COMMENT '本次名义返还积分，0用于无积分分配的退货行',
 PRIMARY KEY(tenant_id,case_id),
 FOREIGN KEY(tenant_id,order_id) REFERENCES member_point_hold(tenant_id,order_id),
 CHECK(points>=0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin COMMENT='积分售后返还去重，不能兑换现金';
