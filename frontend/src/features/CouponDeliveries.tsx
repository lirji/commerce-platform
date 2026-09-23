import { Alert, Button, Card, Drawer, Space, Table } from "antd";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import { ActionButton, CommandModal, ErrorNotice, PageHead, Status, instant, time } from "../shared/ui";
import type { CouponDefinition } from "../shared/contracts";
type Batch = { content: { batchId: string; name: string; storeId: string; definitionId: string; definitionVersion: number; audience: { id: string; version: number }; deadline: string; minIntervalHours: number }; status: string; mode: string; processed: number; issued: number; skipped: number; revoked: number; kept: number; attempts: number; errorCode?: string; version: number };
type Recipient = { memberId: string; status: string; couponId?: string; errorCode?: string; createdAt: string };
type Audience = { audienceId: string; version: number; name: string; validUntil: string };
/** 发券和撤销进度均来自持久任务，不能把按钮点击当作完成。 */
export function CouponDeliveries({ store }: { store: string }) {
  const [after, setAfter] = useState("");const [selected, setSelected] = useState<Batch>();const [recipientAfter, setRecipientAfter] = useState("");
  const batches = useResource<Batch[]>(store ? `/admin/coupon-deliveries?storeId=${encode(store)}&after=${encode(after)}` : null);
  const coupons = useResource<{ content: CouponDefinition; issued: number }[]>(store ? `/admin/coupon-definitions?storeId=${encode(store)}` : null);
  const audiences = useResource<Audience[]>("/admin/audiences");
  const recipients = useResource<Recipient[]>(selected ? `/admin/coupon-deliveries/${encode(selected.content.batchId)}/recipients?after=${encode(recipientAfter)}` : null);
  const refresh = () => { batches.refresh(); recipients.refresh(); };
  const selectedNow = batches.data?.find(b => b.content.batchId === selected?.content.batchId) ?? selected;
  const controls = (b: Batch) => <Space wrap>{[
    ...(["RUNNING", "ISOLATED"].includes(b.status) ? [["CANCEL", "停止发放"]] : []),
    ...(b.status === "ISOLATED" ? [["RETRY", "从检查点重试"]] : []),
    ...(["COMPLETED", "CANCELLED", "EXPIRED", "ISOLATED"].includes(b.status) ? [["REVOKE", "撤销可用券"]] : []),
  ].map(([action, title]) => <CommandModal key={action} title={title} path={`/admin/coupon-deliveries/${encode(b.content.batchId)}/control`} fields={[{ name: "reason", label: "批次操作原因" }]} build={v => ({ ...v, expectedVersion: b.version, action })} onDone={refresh} buttonType="link" />)}</Space>;
  return <>
    <PageHead title="定向发券" description="固定人群版本，分批发放、会员频控和可恢复进度。" extra={<Space wrap><Button onClick={refresh}>刷新批次</Button><ActionButton label="推进一批发券" path="/admin/coupon-deliveries/pump" onDone={refresh} /></Space>} />
    <ErrorNotice error={batches.error} /><ErrorNotice error={coupons.error} /><ErrorNotice error={audiences.error} />
    <Alert type="info" title="停止与撤销分别处理" description="停止只影响未发对象。撤销只处理仍可用的券；已占用、使用或到期的券会保留并标明原因，不回收已发生的优惠。" style={{ marginBottom: 16 }} />
    <Card extra={<CommandModal title="创建定向发券" path="/admin/coupon-deliveries" disabled={!store} fields={[
      { name: "batchId", label: "发券批次标识" }, { name: "name", label: "发券批次名称" },
      { name: "coupon", label: "受控券定义", type: "select", options: coupons.data?.filter(c => c.content.issuanceMode === "SOURCE_ONLY").map(c => ({ label: `${c.content.name} · v${c.content.version}`, value: JSON.stringify({ definitionId: c.content.definitionId, definitionVersion: c.content.version }) })), help: "只展示本店最新受控定义；配额由券定义统一约束。" },
      { name: "audience", label: "固定人群快照", type: "select", options: audiences.data?.map(a => ({ label: `${a.name} · v${a.version} · 到期 ${time(a.validUntil)}`, value: JSON.stringify({ id: a.audienceId, version: a.version }) })) },
      { name: "deadline", label: "发券截止时间", type: "datetime", help: "不能超过人群过期与券发行截止时间。" },
      { name: "minIntervalHours", label: "该会员后续发券冷却小时数", type: "number", min: 1, max: 720, initial: 24 },
    ]} build={v => ({ batchId: v.batchId, name: v.name, storeId: store, ...JSON.parse(String(v.coupon)), audience: JSON.parse(String(v.audience)), deadline: instant(v.deadline), minIntervalHours: v.minIntervalHours })} onDone={refresh} />}>
      <Table<Batch> rowKey={r => r.content.batchId} dataSource={batches.data} loading={batches.loading} pagination={false} scroll={{ x: 1250 }} columns={[
        { title: "批次", render: (_, r) => <><strong>{r.content.name}</strong><div className="muted">{r.content.batchId}</div></> },
        { title: "状态", dataIndex: "status", render: v => <Status value={v} /> }, { title: "处理 / 成功 / 跳过", render: (_, r) => `${r.processed} / ${r.issued} / ${r.skipped}` },
        { title: "撤销 / 保留", render: (_, r) => `${r.revoked} / ${r.kept}` }, { title: "截止时间", render: (_, r) => time(r.content.deadline) },
        { title: "重试 / 错误", render: (_, r) => `${r.attempts} / ${r.errorCode ?? "—"}` },
        { title: "操作", render: (_, r) => <Space wrap><Button type="link" onClick={() => { setSelected(r); setRecipientAfter(""); }}>发券回执</Button>{controls(r)}</Space> },
      ]} />
      <Space><Button disabled={!after} onClick={() => setAfter("")}>批次首页</Button><Button disabled={batches.data?.length !== 50} onClick={() => setAfter(batches.data!.at(-1)!.content.batchId)}>下一页批次</Button></Space>
    </Card>
    <Drawer title={`${selected?.content.name ?? "定向券"} · 收件人回执`} open={!!selected} onClose={() => setSelected(undefined)} size="large">
      <ErrorNotice error={recipients.error} />
      {selectedNow && <><p>发放 {selectedNow.issued} · 跳过 {selectedNow.skipped} · 撤销 {selectedNow.revoked} · 保留 {selectedNow.kept}</p>{controls(selectedNow)}</>}
      <Button onClick={refresh}>刷新回执</Button>
      <Table<Recipient> rowKey="memberId" dataSource={recipients.data} loading={recipients.loading} pagination={false} scroll={{ x: 800 }} columns={[
        { title: "会员", dataIndex: "memberId" }, { title: "结果", dataIndex: "status", render: v => ({ ISSUED: "已发放", SKIPPED: "已跳过", REVOKED: "已撤销", KEPT: "保留" })[v as string] ?? v },
        { title: "原因", dataIndex: "errorCode" }, { title: "券标识", dataIndex: "couponId", ellipsis: true }, { title: "处理时间", dataIndex: "createdAt", render: time },
      ]} />
      <Space><Button disabled={!recipientAfter} onClick={() => setRecipientAfter("")}>回执首页</Button><Button disabled={recipients.data?.length !== 50} onClick={() => setRecipientAfter(recipients.data!.at(-1)!.memberId)}>下一页收件人</Button></Space>
    </Drawer>
  </>;
}
