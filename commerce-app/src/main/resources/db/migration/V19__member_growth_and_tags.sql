CREATE TABLE member_growth_policy (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 version BIGINT NOT NULL COMMENT '不可变策略版本',
 effective_from TIMESTAMP(3) NOT NULL COMMENT '新订单采用策略的起点UTC',
 policy_json JSON NOT NULL COMMENT '成长率与等级门槛完整快照',
 PRIMARY KEY(tenant_id,version),KEY ix_growth_policy_time(tenant_id,effective_from,version),
 CONSTRAINT ck_growth_policy_version CHECK(version>0)
) COMMENT='运营发布的会员成长与等级规则版本';
CREATE TABLE member_growth_account (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 member_id VARCHAR(64) NOT NULL COMMENT '会员标识',
 growth BIGINT NOT NULL DEFAULT 0 COMMENT '成长余额允许冲正债务非支付积分',
 net_spend DECIMAL(20,2) NOT NULL DEFAULT 0 COMMENT '已完成订单累计净实付人民币',
 policy_version BIGINT NOT NULL DEFAULT 0 COMMENT '最近等级计算策略零表示未启用',
 version BIGINT NOT NULL DEFAULT 0 COMMENT '账本并发版本',
 PRIMARY KEY(tenant_id,member_id),
 CONSTRAINT ck_growth_account CHECK(net_spend>=0 AND version>=0 AND policy_version>=0 AND growth BETWEEN -9000000000000000 AND 9000000000000000)
) COMMENT='会员成长与完成订单净消费投影';
CREATE TABLE member_growth_order (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 order_id VARCHAR(64) NOT NULL COMMENT '订单来源幂等标识',
 member_id VARCHAR(64) NOT NULL COMMENT '所属会员',
 paid DECIMAL(14,2) NOT NULL COMMENT '订单原始实付人民币',
 completed BOOLEAN NOT NULL COMMENT '是否已收到可信完成事实只可前进',
 policy_version BIGINT NOT NULL COMMENT '订单创建时适用规则零为无奖励',
 growth_rate DECIMAL(6,2) NOT NULL COMMENT '原策略每元成长率用于退款冲回',
 contribution BIGINT NOT NULL DEFAULT 0 COMMENT '已入账成长净贡献',
 net_spend DECIMAL(14,2) NOT NULL DEFAULT 0 COMMENT '该来源已入账净消费',
 PRIMARY KEY(tenant_id,order_id),KEY ix_growth_order_member(tenant_id,member_id,order_id),
 CONSTRAINT ck_growth_order CHECK(paid>=0 AND growth_rate>=0 AND growth_rate<=1000 AND contribution>=0 AND net_spend>=0 AND net_spend<=paid)
) COMMENT='完成订单成长来源及原规则快照';
CREATE TABLE member_growth_refund (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 refund_id VARCHAR(64) NOT NULL COMMENT '成功退款事实幂等标识',
 order_id VARCHAR(64) NOT NULL COMMENT '原订单标识',
 amount DECIMAL(14,2) NOT NULL COMMENT '成功退款人民币金额',
 PRIMARY KEY(tenant_id,refund_id),KEY ix_growth_refund_order(tenant_id,order_id),
 CONSTRAINT ck_growth_refund_amount CHECK(amount>0)
) COMMENT='会员成长消费的成功退款事实投影';
CREATE TABLE member_growth_ledger (
 sequence_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '稳定单调账本游标',
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 member_id VARCHAR(64) NOT NULL COMMENT '所属会员',
 source_id VARCHAR(64) NOT NULL COMMENT '订单或人工命令来源',
 delta BIGINT NOT NULL COMMENT '本次成长增量可为冲正负值',
 balance BIGINT NOT NULL COMMENT '提交后的成长余额',
 policy_version BIGINT NOT NULL COMMENT '该来源成长策略版本',
 reason VARCHAR(256) NOT NULL COMMENT '入账原因',
 created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '入账时间UTC',
 PRIMARY KEY(sequence_id),KEY ix_growth_ledger_member(tenant_id,member_id,sequence_id)
) COMMENT='成长发放冲回和人工调整不可变账本';
CREATE TABLE member_tag_definition (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 tag_id VARCHAR(64) NOT NULL COMMENT '稳定标签标识',
 name VARCHAR(64) NOT NULL COMMENT '运营标签名称',
 PRIMARY KEY(tenant_id,tag_id)
) COMMENT='会员标签字典';
CREATE TABLE member_tag_assignment (
 tenant_id VARCHAR(64) NOT NULL COMMENT '所属租户',
 member_id VARCHAR(64) NOT NULL COMMENT '会员标识',
 tag_id VARCHAR(64) NOT NULL COMMENT '标签标识',
 active BOOLEAN NOT NULL COMMENT '当前标签是否生效',
 version BIGINT NOT NULL COMMENT '标签关联并发版本',
 source VARCHAR(16) NOT NULL COMMENT '来源MANUAL运营维护',
 reason VARCHAR(256) NOT NULL COMMENT '最近变更原因',
 PRIMARY KEY(tenant_id,member_id,tag_id),
 CONSTRAINT ck_tag_assignment CHECK(version>0 AND source='MANUAL')
) COMMENT='会员标签关联通过命令审计保留变更痕迹';
