import { Alert, Button, Card, Col, Input, Row, Space, Statistic, Table } from "antd";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import { ActionButton, CommandModal, ErrorNotice, instant, time } from "../shared/ui";

type Wallet = { memberId: string; available: number; held: number; debt: number; credit: number; version: number };
type Entry = { sequenceId: number; action: string; sourceId: string; delta: number; available: number; debt: number; policyVersion: number; reason: string; createdAt: string; held: number };
type Policy = { version: number; effectiveFrom: string; earnPerYuan: string; expiryDays: number; spendEnabled: boolean; pointsPerYuan: number; maxDeductionBps: number };
const actions: Record<string, string> = { EARN: "消费奖励", ADJUST: "运营校准", REVOKE: "退款扣回", EXPIRE: "积分到期", HOLD: "订单冻结", SPEND: "支付核销", RELEASE: "取消释放", REFUND: "售后返还", EXCHANGE: "积分兑换" };

/** 积分资产从独立账本读取，不能用页面累计成交或成长值自行估算。 */
export function MemberPoints({ admin }: { admin: boolean }) {
  const [member, setMember] = useState("");
  const [after, setAfter] = useState(0);
  const [policyAfter, setPolicyAfter] = useState(0);
  const base = admin ? member ? `/admin/member-points/${encode(member)}` : null : "/members/me/points";
  const wallet = useResource<Wallet>(base);
  const ledger = useResource<Entry[]>(base ? `${base}/ledger?after=${after}` : null);
  const policies = useResource<Policy[]>(admin ? `/admin/member-points/policies?after=${policyAfter}` : null);
  const refresh = () => { wallet.refresh(); ledger.refresh(); };
  return <div className="cycle-workspace">
    {admin && <Input.Search aria-label="查询会员积分" placeholder="会员标识" enterButton="查看积分" style={{ maxWidth: 440 }} onSearch={v => { setMember(v.trim()); setAfter(0); }} />}
    <ErrorNotice error={wallet.error} />
    {wallet.data && <>
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="可用积分" value={wallet.data.available} /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="订单冻结积分" value={wallet.data.held} /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="待偿扣回积分" value={wallet.data.debt} /></Card></Col>
        <Col xs={24} sm={12} xl={6}><Card><Statistic title="有效批次剩余积分" value={wallet.data.credit} /></Card></Col>
      </Row>
      {wallet.data.debt > 0 && <Alert type="warning" title="有待偿扣回积分" description="可用积分已扣除待偿部分，后续新增积分优先抵偿。这不是现金欠款。" />}
      <Card title="积分账本" extra={<Space wrap>
        {admin && <CommandModal title="校准积分" path={`${base}/adjust`} fields={[
          { name: "delta", label: "积分调整量", type: "number", min: -1000000000, max: 1000000000, help: "扣回填写负数；不足部分记录为待偿扣回。" },
          { name: "reason", label: "积分调整原因" },
        ]} build={v => ({ ...v, expectedVersion: wallet.data!.version })} onDone={refresh} />}
        {admin && <ActionButton label="处理到期批次" path={`${base}/expire`} onDone={refresh} />}
        <Button onClick={refresh} loading={wallet.loading}>刷新积分</Button>
      </Space>}>
        <p className="muted">奖励来自完成订单的现金净消费。退款沿用原获取规则扣回，过期未用积分不重复扣回；积分不支持提现。</p>
        <ErrorNotice error={ledger.error} />
        <Table<Entry> rowKey="sequenceId" dataSource={ledger.data} loading={ledger.loading} pagination={false} scroll={{ x: 1050 }} columns={[
          { title: "时间", dataIndex: "createdAt", render: time, width: 180 }, { title: "类型", dataIndex: "action", render: v => actions[v] ?? v, width: 100 },
          { title: "变动积分", dataIndex: "delta", align: "right", width: 110, render: v => v > 0 ? `+${v}` : v },
          { title: "可用快照", dataIndex: "available", align: "right", width: 100 }, { title: "待偿快照", dataIndex: "debt", align: "right", width: 100 },
          { title: "冻结快照", dataIndex: "held", align: "right", width: 100 },
          { title: "原因", dataIndex: "reason" }, { title: "来源", dataIndex: "sourceId", ellipsis: true, width: 200 },
        ]} />
        <Space className="section-actions"><Button disabled={!after} onClick={() => setAfter(0)}>最早积分记录</Button><Button disabled={ledger.data?.length !== 50} onClick={() => setAfter(ledger.data!.at(-1)!.sequenceId)}>下一页积分</Button></Space>
      </Card>
    </>}
    {!wallet.data && !wallet.error && <Alert type="info" title={wallet.loading ? "正在读取积分账户" : "选择会员后查看积分余额与账本"} />}
    {admin && <Card title="积分规则" extra={<CommandModal title="发布积分规则" path="/admin/member-points/policies" fields={[
      { name: "version", label: "积分策略版本", type: "number", min: 1 }, { name: "effectiveFrom", label: "积分规则生效时间", type: "datetime" },
      { name: "earnPerYuan", label: "每元现金净消费奖励积分", type: "money" }, { name: "expiryDays", label: "积分有效天数", type: "number", min: 1, max: 366 },
      { name: "spendEnabled", label: "允许消费积分", type: "switch", initial: false }, { name: "pointsPerYuan", label: "每元抵扣所需积分", type: "number", min: 1, max: 100000 },
      { name: "maxDeductionBps", label: "订单最高抵扣比例（基点）", type: "number", min: 0, max: 10000, help: "100基点=1%，10000基点=100%。" },
    ]} build={v => ({ ...v, effectiveFrom: instant(v.effectiveFrom) })} onDone={policies.refresh} />}>
      <p className="muted">获取率按下单时的规则确定。有效期从奖励入账时起计算，退款不延长；未配置规则不会自动赠分。</p>
      <ErrorNotice error={policies.error} />
      <Table<Policy> rowKey="version" dataSource={policies.data} loading={policies.loading} pagination={false} scroll={{ x: 920 }} columns={[
        { title: "版本", dataIndex: "version" }, { title: "生效时间", dataIndex: "effectiveFrom", render: time }, { title: "每元奖励", dataIndex: "earnPerYuan", align: "right" },
        { title: "有效天数", dataIndex: "expiryDays", align: "right" }, { title: "积分消费", dataIndex: "spendEnabled", render: v => v ? "允许" : "关闭" },
        { title: "每元抵扣积分", dataIndex: "pointsPerYuan", align: "right" }, { title: "最高抵扣", dataIndex: "maxDeductionBps", align: "right", render: v => `${v / 100}%` },
      ]} />
      <Space className="section-actions"><Button disabled={!policyAfter} onClick={() => setPolicyAfter(0)}>最早积分策略</Button><Button disabled={policies.data?.length !== 50} onClick={() => setPolicyAfter(policies.data!.at(-1)!.version)}>下一页积分策略</Button></Space>
    </Card>}
  </div>;
}
