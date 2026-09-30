import { useRouteState } from "../shared/routeState";
import {
  Button,
  Card,
  Descriptions,
  Spin,
  InputNumber,
  Space,
  Table,
  Typography,
} from "antd";
import { useEffect, useRef, useState } from "react";
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
  RecordDrawer as Drawer,
  RowActions,
  formatField,
  Status,
  Workbench,
  money,
  time,
} from "../shared/ui";
function workspaceOrder() {
  return (
    new URLSearchParams(location.hash.split("?")[1]).get("order") ?? undefined
  );
}
export function Orders({
  admin,
  capabilities,
}: {
  admin: boolean;
  capabilities: Capabilities;
}) {
  const [after, setAfter] = useRouteState("after", "");
  const [selected, setSelected] = useState<string>();
  const [workspaceId, setWorkspaceId] = useState(workspaceOrder);
  const listRef = useRef<HTMLDivElement>(null);
  const lastOpener = useRef<HTMLElement | null>(null);
  useEffect(() => {
    const sync = () => {
      setWorkspaceId(workspaceOrder());
      setSelected(undefined);
    };
    addEventListener("hashchange", sync);
    return () => removeEventListener("hashchange", sync);
  }, []);
  const previewOrder = (id: string) => {
    lastOpener.current =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null;
    setSelected(id);
  };
  const openWorkspace = (id: string) => {
    if (!selected)
      lastOpener.current =
        document.activeElement instanceof HTMLElement
          ? document.activeElement
          : null;
    setSelected(undefined);
    const query = new URLSearchParams(location.hash.split("?")[1]);
    query.set("order", id);
    location.hash = "orders?" + query.toString();
  };
  const returnToList = () => {
    const query = new URLSearchParams(location.hash.split("?")[1]);
    query.delete("order");
    history.replaceState(
      null,
      "",
      "#orders" + (query.size ? "?" + query.toString() : ""),
    );
    setWorkspaceId(undefined);
    dispatchEvent(new Event("hashchange"));
    requestAnimationFrame(() => {
      const opener = lastOpener.current;
      if (opener?.isConnected && opener.getClientRects().length) opener.focus();
      else listRef.current?.querySelector<HTMLButtonElement>("button")?.focus();
    });
  };
  const resource = useResource<Order[]>(
    (admin ? "/admin/orders" : "/orders") + `?after=${encode(after)}`,
  );
  return (
    <Workbench>
      {workspaceId && (
        <div className="order-workspace">
          <PageHead
            eyebrow={admin ? "交易与交付 / 订单" : "我的订单"}
            title="订单完整详情"
            description="集中查看商品、支付和售后处理。"
            extra={<Button onClick={returnToList}>返回订单列表</Button>}
          />
          <OrderDetails
            key={workspaceId}
            id={workspaceId}
            admin={admin}
            capabilities={capabilities}
            onUpdate={resource.refresh}
          />
        </div>
      )}
      <div ref={listRef} hidden={!!workspaceId}>
        <PageHead
          eyebrow={admin ? "交易与交付" : undefined}
          title={admin ? "订单工作台" : "我的订单"}
          description={
            admin
              ? "查看真实交易状态，跟进支付与履约。"
              : "报价在下单时锁定，支付结果以渠道核对为准。"
          }
          extra={
            <Button onClick={resource.refresh}>
              {admin ? "刷新队列" : "刷新订单"}
            </Button>
          }
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
                  <Button type="link" onClick={() => previewOrder(v)}>
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
                className: "row-actions-cell",
                width: 180,
                render: (_, r) => (
                  <RowActions>
                    <Button
                      type="link"
                      size="small"
                      onClick={() => previewOrder(r.orderId)}
                    >
                      查看详情
                    </Button>
                    <Button
                      type="link"
                      size="small"
                      onClick={() => openWorkspace(r.orderId)}
                    >
                      完整详情
                    </Button>
                  </RowActions>
                ),
              },
            ]}
          />
        </ListPanel>
      </div>
      <Drawer
        className="record-drawer"
        size={760}
        open={!!selected}
        onClose={() => setSelected(undefined)}
        title="订单详情"
        extra={
          selected && (
            <Button type="link" onClick={() => openWorkspace(selected)}>
              打开完整详情
            </Button>
          )
        }
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
      {order.loading && !value && (
        <div className="record-loading" role="status">
          <Spin />
          <span>正在读取订单详情</span>
        </div>
      )}
      {order.error && !value && (
        <Button onClick={order.refresh}>重新加载详情</Button>
      )}
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
              {
                key: "kind",
                label: "支付方式",
                children: formatField("paymentKind", value.paymentKind),
              },
              {
                key: "expires",
                label: "支付截止",
                children: time(value.expiresAt),
              },
              { key: "version", label: "版本", children: value.version },
              { key: "lines", label: "商品行数", children: value.items.length },
            ]}
          />
          <Card size="small" title="商品明细" className="detail-block">
            <Table<QuoteLine>
              rowKey="skuId"
              dataSource={value.items}
              pagination={false}
              scroll={{ x: 560 }}
              columns={[
                { title: "商品", dataIndex: "title" },
                { title: "数量", dataIndex: "quantity" },
                { title: "行实付", dataIndex: "payable", render: money },
                {
                  title: "抵扣积分",
                  dataIndex: "points",
                  render: (v) => v ?? 0,
                },
                {
                  title: "积分抵扣额",
                  dataIndex: "pointDiscount",
                  render: (v) => money(v ?? "0.00"),
                },
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
                  danger
                  confirm={{
                    title: "确认取消这笔订单？",
                    description:
                      "取消请求会交由服务端核对支付结果，处理中的订单可稍后刷新查看。",
                  }}
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
              {payment.loading && !payment.data && (
                <div className="record-loading" role="status">
                  <Spin />
                  <span>正在读取支付结果</span>
                </div>
              )}
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
