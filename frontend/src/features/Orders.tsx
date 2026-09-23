import {
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  InputNumber,
  Space,
  Table,
  Typography,
} from "antd";
import { useState } from "react";
import type {
  Order,
  Payment,
  QuoteLine,
  Capabilities,
} from "../shared/contracts";
import { encode, useCommand, useResource } from "../shared/api";
import {
  ActionButton,
  Blank,
  CommandModal,
  ErrorNotice,
  ListPanel,
  PageHead,
  PrimaryCell,
  RecordHero,
  Status,
  Workbench,
  money,
  time,
} from "../shared/ui";
export function Orders({
  admin,
  capabilities,
}: {
  admin: boolean;
  capabilities: Capabilities;
}) {
  const [after, setAfter] = useState("");
  const [selected, setSelected] = useState<string>();
  const resource = useResource<Order[]>(
    (admin ? "/admin/orders" : "/orders") + `?after=${encode(after)}`,
  );
  return (
    <Workbench>
      <PageHead
        eyebrow={admin ? "交易与交付" : "会员服务"}
        title={admin ? "订单工作台" : "我的订单"}
        description={
          admin
            ? "查看真实交易状态，跟进支付与履约。"
            : "报价在下单时锁定，支付结果以渠道核对为准。"
        }
        extra={<Button onClick={resource.refresh}>刷新队列</Button>}
      />
      <ErrorNotice error={resource.error} />
      <ListPanel
        count={resource.data?.length ?? 0}
        after={after}
        onHome={() => setAfter("")}
        onNext={() => setAfter(resource.data!.at(-1)!.orderId)}
      >
        <Table<Order>
          rowKey="orderId"
          dataSource={resource.data}
          loading={resource.loading}
          pagination={false}
          locale={{
            emptyText: <Blank text="还没有订单，完成报价后即可下单" />,
          }}
          scroll={{ x: 720 }}
          columns={[
            {
              title: "订单编号",
              dataIndex: "orderId",
              render: (v: string) => (
                <Button type="link" onClick={() => setSelected(v)}>
                  <PrimaryCell title={v.slice(0, 8) + "…"} subtitle={v} />
                </Button>
              ),
            },
            { title: "店铺", dataIndex: "storeId" },
            { title: "应付金额", dataIndex: "payable", render: money },
            {
              title: "订单状态",
              dataIndex: "status",
              render: (v: string) => <Status value={v} />,
            },
            { title: "创建时间", dataIndex: "createdAt", render: time },
            {
              title: "操作",
              render: (_, r) => (
                <Button onClick={() => setSelected(r.orderId)}>查看详情</Button>
              ),
            },
          ]}
        />
      </ListPanel>
      <Drawer
        className="record-drawer"
        size={760}
        open={!!selected}
        onClose={() => setSelected(undefined)}
        title="订单详情"
        destroyOnHidden
      >
        {selected && (
          <OrderDetails
            key={selected}
            id={selected}
            admin={admin}
            capabilities={capabilities}
            onUpdate={resource.refresh}
          />
        )}
      </Drawer>
    </Workbench>
  );
}
function OrderDetails({
  id,
  admin,
  capabilities,
  onUpdate,
}: {
  id: string;
  admin: boolean;
  capabilities: Capabilities;
  onUpdate: () => void;
}) {
  const prefix = admin ? "/admin/orders/" : "/orders/";
  const order = useResource<Order>(prefix + encode(id));
  const [paymentVisible, setPaymentVisible] = useState(false);
  const payment = useResource<Payment>(
    paymentVisible ? prefix + encode(id) + "/payment" : null,
  );
  const command = useCommand();
  const [returnOpen, setReturnOpen] = useState(false);
  const [quantities, setQuantities] = useState<Record<string, number>>({});
  const refresh = () => {
    order.refresh();
    payment.refresh();
    onUpdate();
  };
  const value = order.data;
  return (
    <>
      <ErrorNotice error={order.error} />
      <ErrorNotice error={command.error} />
      {value && (
        <>
          <RecordHero
            eyebrow="交易记录"
            title={<Typography.Text copyable>{id}</Typography.Text>}
            id={value.storeId}
            status={value.status}
            metrics={[
              { label: "应付金额", value: money(value.payable) },
              { label: "会员", value: value.memberId },
              { label: "创建时间", value: time(value.createdAt) },
            ]}
          />
          <Descriptions
            column={2}
            items={[
              { key: "kind", label: "支付方式", children: value.paymentKind || "—" },
              { key: "expires", label: "支付截止", children: time(value.expiresAt) },
              { key: "version", label: "版本", children: value.version },
              { key: "lines", label: "商品行数", children: value.items.length },
            ]}
          />
          <Card size="small" title="商品明细" className="detail-block">
            <Table<QuoteLine>
              rowKey="skuId"
              dataSource={value.items}
              pagination={false}
              columns={[
                { title: "商品", dataIndex: "title" },
                { title: "数量", dataIndex: "quantity" },
                { title: "行实付", dataIndex: "payable", render: money },
                { title: "抵扣积分", dataIndex: "points", render: v => v ?? 0 },
                { title: "积分抵扣额", dataIndex: "pointDiscount", render: v => money(v ?? "0.00") },
              ]}
            />
          </Card>
          <Space wrap className="section-actions">
            <Button onClick={refresh}>刷新状态</Button>
            {!admin &&
              ["PENDING_PAYMENT", "PAYMENT_IN_PROGRESS"].includes(
                value.status,
              ) && (
                <Button
                  type="primary"
                  loading={command.busy}
                  onClick={async () => {
                    if (
                      (await command.run(
                        "/orders/" + encode(id) + "/payments",
                      )) !== undefined
                    ) {
                      setPaymentVisible(true);
                      refresh();
                    }
                  }}
                >
                  发起支付
                </Button>
              )}
            {!admin &&
              ["PENDING_PAYMENT", "PAYMENT_IN_PROGRESS", "CLOSING"].includes(
                value.status,
              ) && (
                <ActionButton
                  label="取消订单"
                  path={"/orders/" + encode(id) + "/cancel"}
                  onDone={refresh}
                />
              )}
            <Button onClick={() => setPaymentVisible(true)}>查询支付</Button>
            {!admin &&
              ["PAID", "FULFILLING", "COMPLETED"].includes(value.status) && (
                <Button onClick={() => setReturnOpen(true)}>申请售后</Button>
              )}
          </Space>
          {paymentVisible && (
            <Card
              size="small"
              title="支付状态"
              extra={<Button onClick={payment.refresh}>刷新支付</Button>}
            >
              <ErrorNotice error={payment.error} />
              {payment.data && (
                <>
                  <p>
                    <Status value={payment.data.status} />{" "}
                    {money(payment.data.amount)} ·{" "}
                    {payment.data.provider === "SANDBOX"
                      ? "本地隔离沙箱"
                      : payment.data.provider}
                  </p>
                  <Space wrap>
                    <ActionButton
                      label="核对渠道结果"
                      path={prefix + encode(id) + "/payment/reconcile"}
                      onDone={refresh}
                    />
                    {admin && capabilities.sandboxEnabled && (
                      <>
                        <ActionButton
                          label="沙箱：置为待支付"
                          path={
                            "/admin/sandbox/payments/" +
                            encode(payment.data.paymentId) +
                            "/fact"
                          }
                          body={{ status: "OPEN" }}
                          onDone={refresh}
                        />
                        <ActionButton
                          label="沙箱：模拟收款"
                          path={
                            "/admin/sandbox/payments/" +
                            encode(payment.data.paymentId) +
                            "/fact"
                          }
                          body={{ status: "PAID" }}
                          onDone={refresh}
                        />
                      </>
                    )}
                  </Space>
                  <p className="muted">
                    未知结果会保留订单与库存占用。核对后由异步事件推进订单，可稍后刷新。
                  </p>
                </>
              )}
            </Card>
          )}
          {returnOpen && (
            <Card title="申请退货退款" className="section">
              <p className="muted">
                未发货订单需全量申请；已发货可选择部分数量。退款金额由原订单计算。
              </p>
              {value.items.map((line) => (
                <div className="return-line" key={line.skuId}>
                  <span>
                    {line.title}（原数量 {line.quantity}）
                  </span>
                  <InputNumber
                    aria-label={line.title + "退货数量"}
                    min={0}
                    max={line.quantity}
                    precision={0}
                    value={quantities[line.skuId] ?? 0}
                    onChange={(v) =>
                      setQuantities((q) => ({ ...q, [line.skuId]: v ?? 0 }))
                    }
                  />
                </div>
              ))}
              <CommandModal
                title="提交售后申请"
                path="/aftersales"
                fields={[
                  { name: "reason", label: "申请原因", type: "textarea" },
                ]}
                disabled={!Object.values(quantities).some((q) => q > 0)}
                build={(v) => ({
                  orderId: id,
                  reason: v.reason,
                  items: Object.entries(quantities)
                    .filter(([, q]) => q > 0)
                    .map(([skuId, quantity]) => ({ skuId, quantity })),
                })}
                onDone={() => {
                  setReturnOpen(false);
                  refresh();
                }}
              />
            </Card>
          )}
        </>
      )}
    </>
  );
}
