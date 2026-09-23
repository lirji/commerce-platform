import { Alert, Button, Card, Col, Descriptions, Input, Row, Space, Statistic, Table } from "antd";
import { useState } from "react";
import { encode, useCommand, useResource } from "../shared/api";
import { CommandModal, ErrorNotice, money, time } from "../shared/ui";
type Profile = { birthday: string | null; journeyEnabled: boolean; version: number };
type Detail = { member: { memberId: string; displayName: string; memberLevel: string; status: string }; profile: Profile; facts: { browse30: number; cart30: number; completedOrders30: number; netSpend30: string; lastOrderAt: string | null; lastCartAt: string | null; daysSinceOrder: number | null; daysSinceJoin: number; birthdayToday: boolean } };
type Event = { sequenceId: number; eventId: string; kind: string; storeId: string; skuId: string; occurredAt: string };
/** 会员概览只显示服务端统计；未知成交时间保持未知。 */
export function MemberBehavior({ admin, memberId }: { admin: boolean; memberId?: string }) {
  const [selected, setSelected] = useState("");const [after, setAfter] = useState(0);
  const [rebuildAfter, setRebuildAfter] = useState("");
  const [rebuildResult, setRebuildResult] = useState<{ scanned: number; done: boolean }>();
  const rebuild = useCommand();
  const member = memberId || selected;
  const base = admin ? member ? `/admin/member-behavior/${encode(member)}` : null : "/members/me/behavior";
  const detail = useResource<Detail>(base);
  const events = useResource<Event[]>(base ? `${base}/events?after=${after}` : null);
  const refresh = () => { detail.refresh(); events.refresh(); };
  const value = detail.data;
  return <div className="cycle-workspace">
    {admin && !memberId && <Input.Search aria-label="查询会员行为" placeholder="会员标识" enterButton="查看会员详情" onSearch={v => { setSelected(v.trim()); setAfter(0); }} style={{ maxWidth: 440 }} />}
    <ErrorNotice error={detail.error} />
    {value && <>
      <Card title={`${value.member.displayName} · 会员详情`} extra={<Button onClick={refresh}>刷新会员详情</Button>}>
        <Descriptions items={[
          { key: "id", label: "会员", children: value.member.memberId }, { key: "level", label: "等级", children: value.member.memberLevel },
          { key: "birthday", label: "生日月日", children: value.profile.birthday ?? "未填写" },
          { key: "preference", label: "站内营销旅程", children: value.profile.journeyEnabled ? "接收" : "已关闭" },
          { key: "recent", label: "最近完成订单下单时间", children: time(value.facts.lastOrderAt) },
          { key: "days", label: "距最近成交", children: value.facts.daysSinceOrder == null ? "暂无成交事实" : `${value.facts.daysSinceOrder} 天` },
          { key: "cart", label: "近30天最近加购", children: time(value.facts.lastCartAt) },
        ]} />
        <CommandModal title="编辑生日与偏好" path={`${base}/profile`} disabled={value.member.status !== "ACTIVE"} fields={[
          { name: "birthday", label: "生日月日", required: false, initial: value.profile.birthday ?? "", help: "仅填写MM-DD，例如06-18；留空清除。生日按UTC经营日判断。" },
          { name: "journeyEnabled", label: "接收站内营销旅程", type: "switch", initial: value.profile.journeyEnabled, required: false },
          { name: "reason", label: "偏好变更原因" },
        ]} build={v => ({ ...v, birthday: String(v.birthday ?? "").trim() || null, expectedVersion: value.profile.version })} onDone={refresh} />
      </Card>
      <Row gutter={[16, 16]}>{[
        ["近30天浏览", value.facts.browse30], ["近30天加购", value.facts.cart30], ["近30天完成订单", value.facts.completedOrders30], ["近30天净现金消费", money(value.facts.netSpend30)],
      ].map(([title, number]) => <Col xs={24} sm={12} xl={6} key={title}><Card><Statistic title={title} value={number} /></Card></Col>)}</Row>
      <Alert type="info" title="统计口径" description="近30天包含UTC今天及前29天。完成订单按原下单时间统计，退款冲减净现金消费；浏览和加购是交互信号。历史订单需要完成投影后才计入。" />
      <Card title="商品交互记录"><ErrorNotice error={events.error} />
        <Table<Event> rowKey="sequenceId" dataSource={events.data} loading={events.loading} pagination={false} scroll={{ x: 650 }} columns={[
          { title: "时间", dataIndex: "occurredAt", render: time }, { title: "行为", dataIndex: "kind", render: v => v === "BROWSE" ? "查看商品" : "加入购物袋" },
          { title: "门店", dataIndex: "storeId" }, { title: "商品", dataIndex: "skuId" },
        ]} />
        <Space><Button disabled={!after} onClick={() => setAfter(0)}>最早交互</Button><Button disabled={events.data?.length !== 50} onClick={() => setAfter(events.data!.at(-1)!.sequenceId)}>下一页交互</Button></Space>
      </Card>
    </>}
    {!value && !detail.error && <Alert type="info" title={detail.loading ? "正在读取会员详情" : "选择会员查看经营画像"} />}
    {admin && <Card title="历史成交补建">
      <ErrorNotice error={rebuild.error} />
      <Space wrap><Input aria-label="成交补建游标" placeholder="首次留空，完成后自动续接下一批" value={rebuildAfter} onChange={e => setRebuildAfter(e.target.value)} />
        <Button loading={rebuild.busy} onClick={async () => {
          const result = await rebuild.run<{ next: string; scanned: number; done: boolean }>("/admin/member-behavior/rebuild", { after: rebuildAfter, limit: 50 });
          if (result) { setRebuildAfter(result.next); setRebuildResult(result); refresh(); }
        }}>补建一批成交事实</Button></Space>
      {rebuildResult && <p>本批扫描 {rebuildResult.scanned} 单 · {rebuildResult.done ? "已到当前订单末尾" : "可继续下一批"}</p>}
    </Card>}
  </div>;
}
