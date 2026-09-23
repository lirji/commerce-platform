import { Alert, Button, Card, Col, Row, Space, Statistic, Table, Tag } from "antd";
import { useState } from "react";
import { encode, useResource } from "../shared/api";
import { CommandModal, ErrorNotice, instant, time } from "../shared/ui";
type Offer = { offerId: string; storeId: string; name: string; kind: "COUPON" | "ENTITLEMENT"; assetId: string; assetVersion: number; points: number; quota: number; perMemberLimit: number; validFrom: string; validTo: string };
type OfferView = { content: Offer; status: string; issued: number; version: number };
type Receipt = { redemptionId: string; offerId: string; memberId: string; points: number; kind: string; assetId: string; createdAt: string };
/** 兑换结果只引用服务端回执，权益实际到账继续查看原钱包。 */
export function PointOffers({ admin, store }: { admin: boolean; store: string }) {
  const [after, setAfter] = useState("");
  const [receiptAfter, setReceiptAfter] = useState("");
  const offers = useResource<OfferView[]>(store ? `${admin ? "/admin" : ""}/point-offers?storeId=${encode(store)}&after=${encode(after)}` : null);
  const receipts = useResource<Receipt[]>(!admin ? `/point-redemptions?after=${encode(receiptAfter)}` : null);
  const wallet = useResource<{ available: number }>(!admin ? "/members/me/points" : null);
  const refresh = () => { offers.refresh(); receipts.refresh(); wallet.refresh(); };
  return <div className="cycle-workspace">
    <Alert type="info" showIcon title={admin ? "创建兑换玩法，设置积分价格与每人限额" : "积分兑换专享礼遇"} description="优惠券受理后即可进入券钱包；权益需等待发放完成后使用。兑换成功不支持退积分，券和权益沿用各自有效期。" />
    <ErrorNotice error={offers.error} /><ErrorNotice error={wallet.error} />
    {!admin && <Card><Statistic title="当前可兑换积分" value={wallet.data?.available ?? "—"} /></Card>}
    <Space wrap>
      {admin && <CommandModal title="创建积分兑换" path="/admin/point-offers" disabled={!store} fields={[
        { name: "offerId", label: "兑换标识" }, { name: "name", label: "兑换名称" },
        { name: "kind", label: "兑换类型", type: "select", initial: "COUPON", options: [{ label: "优惠券", value: "COUPON" }, { label: "权益", value: "ENTITLEMENT" }] },
        { name: "assetId", label: "券定义或权益标识", help: "券必须设为受控发放；请先在优惠券定义或权益定义中创建。" },
        { name: "assetVersion", label: "资产定义版本", type: "number", min: 1, initial: 1 },
        { name: "points", label: "每次兑换所需积分", type: "number", min: 1, max: 1000000000 },
        { name: "quota", label: "兑换总次数", type: "number", min: 1, max: 1000000 },
        { name: "perMemberLimit", label: "每位会员最多兑换次数", type: "number", min: 1, max: 1000, initial: 1 },
        { name: "validFrom", label: "兑换开始时间", type: "datetime" }, { name: "validTo", label: "兑换结束时间", type: "datetime" },
      ]} build={v => ({ ...v, storeId: store, validFrom: instant(v.validFrom), validTo: instant(v.validTo) })} onDone={refresh} />}
      <Button onClick={refresh}>刷新兑换</Button>
    </Space>
    {!store && <Alert type="info" title="先选择门店查看兑换目录" />}
    {admin ? <Table<OfferView> rowKey={r => r.content.offerId} dataSource={offers.data} loading={offers.loading} pagination={false} scroll={{ x: 1000 }} columns={[
      { title: "兑换名称", render: (_, r) => <><strong>{r.content.name}</strong><div className="muted">{r.content.offerId}</div></> },
      { title: "类型", render: (_, r) => r.content.kind === "COUPON" ? "优惠券" : "权益" }, { title: "积分价格", render: (_, r) => r.content.points },
      { title: "已兑 / 总次数", render: (_, r) => `${r.issued} / ${r.content.quota}` }, { title: "每人上限", render: (_, r) => r.content.perMemberLimit },
      { title: "结束时间", render: (_, r) => time(r.content.validTo) }, { title: "状态", render: (_, r) => r.status === "ACTIVE" ? "已启用" : "已停用" },
      { title: "操作", render: (_, r) => <CommandModal title={r.status === "ACTIVE" ? "停用兑换" : "启用兑换"} path={`/admin/point-offers/${encode(r.content.offerId)}/status`} fields={[{ name: "reason", label: "兑换状态变更原因" }]} build={v => ({ ...v, expectedVersion: r.version, active: r.status !== "ACTIVE" })} onDone={refresh} /> },
    ]} /> : <Row gutter={[16, 16]}>{offers.data?.map(r => <Col xs={24} md={12} xl={8} key={r.content.offerId}><Card title={r.content.name} className="point-offer-card" extra={<Tag>{r.content.kind === "COUPON" ? "优惠券" : "权益"}</Tag>}>
      <Statistic title="兑换积分" value={r.content.points} />
      <p className="muted">剩余 {r.content.quota - r.issued} 份 · 每人最多 {r.content.perMemberLimit} 次</p>
      <p className="muted">兑换截止 {time(r.content.validTo)}</p>
      <CommandModal title={`兑换 · ${r.content.points} 积分`} path={`/point-offers/${encode(r.content.offerId)}/redeem`} fields={[]} build={() => null} disabled={!wallet.data || wallet.data.available < r.content.points || r.issued >= r.content.quota} onDone={refresh} />
    </Card></Col>)}</Row>}
    <Space><Button disabled={!after} onClick={() => setAfter("")}>兑换首页</Button><Button disabled={offers.data?.length !== 50} onClick={() => setAfter(offers.data!.at(-1)!.content.offerId)}>下一页兑换</Button></Space>
    {!admin && <Card title="我的兑换回执">
      <ErrorNotice error={receipts.error} />
      <Table<Receipt> rowKey="redemptionId" dataSource={receipts.data} loading={receipts.loading} pagination={false} scroll={{ x: 800 }} columns={[
        { title: "受理时间", dataIndex: "createdAt", render: time }, { title: "兑换项目", dataIndex: "offerId" }, { title: "所用积分", dataIndex: "points" },
        { title: "类型", dataIndex: "kind", render: v => v === "COUPON" ? "优惠券" : "权益（请查看钱包发放状态）" }, { title: "券 / 权益标识", dataIndex: "assetId", ellipsis: true },
      ]} />
      <Space><Button disabled={!receiptAfter} onClick={() => setReceiptAfter("")}>回执首页</Button><Button disabled={receipts.data?.length !== 50} onClick={() => setReceiptAfter(receipts.data!.at(-1)!.redemptionId)}>下一页回执</Button></Space>
    </Card>}
  </div>;
}
