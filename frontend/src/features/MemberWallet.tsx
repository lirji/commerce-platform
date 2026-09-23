import { Button, Card, Col, Row, Space, Table, Tabs } from "antd";
import type {
  Aftersale,
  Coupon,
  CouponDefinition,
  Entitlement,
  Notification,
} from "../shared/contracts";
import { encode, useResource } from "../shared/api";
import {
  ActionButton,
  Blank,
  CommandModal,
  ErrorNotice,
  PageHead,
  Status,
  money,
  time,
} from "../shared/ui";
export function MemberWallet({
  store,
  section,
}: {
  store: string;
  section: string;
}) {
  const available = useResource<
    { content: CouponDefinition; issued: number }[]
  >(
    store && section === "coupons"
      ? "/coupon-definitions?storeId=" + encode(store)
      : null,
  );
  const wallet = useResource<Coupon[]>(
    section === "coupons" ? "/coupons" : null,
  );
  const benefits = useResource<Entitlement[]>(
    section === "benefits" ? "/entitlements" : null,
  );
  const notices = useResource<Notification[]>(
    section === "notifications" ? "/notifications" : null,
  );
  const cases = useResource<Aftersale[]>(
    section === "aftersales" ? "/aftersales" : null,
  );
  const refresh = () => {
    available.refresh();
    wallet.refresh();
    benefits.refresh();
    notices.refresh();
    cases.refresh();
  };
  return (
    <>
      <PageHead
        title={
          {
            coupons: "优惠券中心",
            benefits: "我的权益",
            notifications: "站内消息",
            aftersales: "售后记录",
          }[section] ?? "会员中心"
        }
        description="账户资产与处理进度来自真实业务记录。"
        extra={<Button onClick={refresh}>刷新</Button>}
      />
      {[available, wallet, benefits, notices, cases].map((r, i) => (
        <ErrorNotice key={i} error={r.error} />
      ))}
      {section === "coupons" && (
        <Tabs
          items={[
            {
              key: "claim",
              label: "领取优惠",
              children: (
                <Row gutter={[16, 16]}>
                  {available.data?.map(({ content: c, issued }) => (
                    <Col xs={24} md={12} lg={8} key={c.definitionId}>
                      <Card title={c.name}>
                        <p className="coupon-value">
                          {money(c.discountAmount)}
                        </p>
                        <p>
                          满 {money(c.minimumSpend)} 可用 ·{" "}
                          {c.stackable ? "可叠加活动" : "择优使用"}
                        </p>
                        <p className="muted">
                          有效期至 {time(c.validTo)} · 已领 {issued}/{c.quota}
                        </p>
                        <ActionButton
                          path={
                            "/coupons/" +
                            encode(c.definitionId) +
                            "/" +
                            c.version +
                            "/claim"
                          }
                          label="领取优惠券"
                          onDone={refresh}
                        />
                      </Card>
                    </Col>
                  ))}
                </Row>
              ),
            },
            {
              key: "wallet",
              label: "我的优惠券",
              children: (
                <Table<Coupon>
                  rowKey="couponId"
                  dataSource={wallet.data}
                  columns={[
                    { title: "优惠券", dataIndex: "name" },
                    {
                      title: "优惠金额",
                      dataIndex: "discountAmount",
                      render: money,
                    },
                    {
                      title: "使用状态",
                      dataIndex: "status",
                      render: (v: string) => <Status value={v} />,
                    },
                    { title: "有效期", dataIndex: "validTo", render: time },
                  ]}
                  pagination={false}
                />
              ),
            },
          ]}
        />
      )}
      {section === "benefits" && (
        <Row gutter={[16, 16]}>
          {benefits.data?.map((b) => (
            <Col xs={24} md={12} lg={8} key={b.grantId}>
              <Card title={b.name} extra={<Status value={b.status} />}>
                <p className="balance-number">
                  {b.remainingUnits}
                  <small> / {b.units} 单位</small>
                </p>
                <p className="muted">有效期至 {time(b.expiresAt)}</p>
                {b.status === "AVAILABLE" && (
                  <CommandModal
                    title="核销权益"
                    path={"/entitlements/" + encode(b.grantId) + "/consume"}
                    fields={[
                      {
                        name: "units",
                        label: "核销数量",
                        type: "number",
                        min: 1,
                        max: b.remainingUnits,
                        initial: 1,
                      },
                    ]}
                    onDone={refresh}
                  />
                )}
              </Card>
            </Col>
          ))}
          {benefits.data?.length === 0 && (
            <Blank text="暂无权益，参与活动后可在这里查看" />
          )}
        </Row>
      )}
      {section === "notifications" && (
        <Space orientation="vertical" size={16} style={{ width: "100%" }}>
          {notices.data?.map((n) => (
            <Card
              key={n.notificationId}
              title={n.title}
              extra={<span className="muted">{time(n.createdAt)}</span>}
            >
              <p>{n.body}</p>
            </Card>
          ))}
          {notices.data?.length === 0 && <Blank text="暂时没有新消息" />}
        </Space>
      )}
      {section === "aftersales" && (
        <Card>
          <Table<Aftersale>
            rowKey="caseId"
            dataSource={cases.data}
            pagination={false}
            columns={[
              { title: "申请编号", dataIndex: "caseId", ellipsis: true },
              { title: "订单编号", dataIndex: "orderId", ellipsis: true },
              { title: "退款金额", dataIndex: "refundAmount", render: money },
              {
                title: "处理状态",
                dataIndex: "status",
                render: (v: string) => <Status value={v} />,
              },
            ]}
            expandable={{
              expandedRowRender: (r) => (
                <p>
                  {r.returnRequired
                    ? "请按运营确认的退货指引完成退回，入库后继续退款。"
                    : "无需退货，等待审批及退款核对。"}{" "}
                  {r.refundId && `退款编号：${r.refundId}`}
                </p>
              ),
            }}
          />
        </Card>
      )}
    </>
  );
}
