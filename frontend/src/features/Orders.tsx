import { tradeNumberColumnWidth } from "../shared/tradeNumbers";
import { BusinessId, Table } from "../shared/BusinessTable";
import { useListFilters } from "../shared/listFilters";
import { TradeLinks } from "../shared/workspaceLinks";
import { OrderEvidencePanel } from "./OrderEvidencePanel";
import { TradeRowFacts } from "../shared/TradeRowFacts";
import { useCursorState } from "../shared/routeState";
import {
  Button,
  Card,
  Descriptions,
  Grid,
  Spin,
  InputNumber,
  Space,
  Typography,
} from "antd";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
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
  RecordModal,
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
  const compact = Grid.useBreakpoint().md === false;
  const [after, setAfter] = useCursorState("after", "");
  const [selected, setSelected] = useState<string>();
  const [workspaceId, setWorkspaceId] = useState(workspaceOrder);
  const listRef = useRef<HTMLDivElement>(null);
  const lastOpener = useRef<HTMLElement | null>(null);
  const detailHeading = useRef<HTMLDivElement>(null);
  const listScroll = useRef({ windowTop: 0, tableTop: 0 });
  const previousWorkspace = useRef<string | undefined>(undefined);
  useEffect(() => {
    const sync = () => {
      setWorkspaceId(workspaceOrder());
      setSelected(undefined);
    };
    addEventListener("hashchange", sync);
    return () => removeEventListener("hashchange", sync);
  }, []);
  useLayoutEffect(() => {
    if (workspaceId === previousWorkspace.current) return;
    previousWorkspace.current = workspaceId;
    if (workspaceId) {
      detailHeading.current?.focus({ preventScroll: true });
      window.scrollTo({ top: 0, behavior: "instant" });
      return;
    }
    const opener = lastOpener.current;
    if (opener?.isConnected && opener.getClientRects().length) {
      opener.focus({ preventScroll: true });
      const { windowTop, tableTop } = listScroll.current;
      // 隐藏列表时部分浏览器会清除表体的独立滚动位置。
      const frame = requestAnimationFrame(() => {
        const body = opener.closest<HTMLElement>(".ant-table-body");
        if (body) body.scrollTop = tableTop;
        window.scrollTo({ top: windowTop, behavior: "instant" });
      });
      return () => cancelAnimationFrame(frame);
    } else if (listRef.current) {
      listRef.current.focus({ preventScroll: true });
      window.scrollTo({ top: 0, behavior: "instant" });
    }
  }, [workspaceId]);
  const rememberOpener = (opener: HTMLElement) => {
    lastOpener.current = opener;
    listScroll.current = {
      windowTop: window.scrollY,
      tableTop: opener.closest(".ant-table-body")?.scrollTop ?? 0,
    };
  };
  const previewOrder = (id: string, opener: HTMLElement) => {
    rememberOpener(opener);
    setSelected(id);
  };
  const openWorkspace = (id: string, opener?: HTMLElement) => {
    if (opener) rememberOpener(opener);
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
  };
  const filters = useListFilters(
    "Orders",
    admin ? "/admin/orders" : "/orders",
    () => setAfter(""),
    compact,
  );
  const resource = useResource<Order[]>(
    (admin ? "/admin/orders" : "/orders") +
      `?after=${encode(after)}&${filters.query}`,
  );
  return (
    <Workbench>
      {workspaceId && (
        <div className="order-workspace">
          <div
            ref={detailHeading}
            tabIndex={-1}
            role="region"
            aria-label="订单完整详情"
          >
            <PageHead
              eyebrow={admin ? "交易与交付 / 订单" : "我的订单"}
              title="订单完整详情"
              description="集中查看商品、支付和售后处理。"
              extra={<Button onClick={returnToList}>返回订单列表</Button>}
            />
          </div>
          <OrderDetails
            key={workspaceId}
            id={workspaceId}
            admin={admin}
            capabilities={capabilities}
            onUpdate={resource.refresh}
          />
        </div>
      )}
      <div ref={listRef} tabIndex={-1} hidden={!!workspaceId}>
        <PageHead
          eyebrow={admin ? "交易与交付" : undefined}
          title={admin ? "订单工作台" : "我的订单"}
          description={
            admin
              ? "查看当前权限范围内各店铺的订单，可用店铺编号定位。"
              : "查看本人在各店铺的订单。支付结果以渠道核对为准。"
          }
          extra={
            <Button loading={resource.loading} onClick={resource.refresh}>
              {admin ? "刷新队列" : "刷新订单"}
            </Button>
          }
        />
        {admin && capabilities.orderAuditEnabled && <OrderEvidencePanel />}
        <ErrorNotice error={resource.error} onRetry={resource.refresh} />
        <ListPanel
          toolbar={filters.toolbar}
          pageSize={filters.limit}
          count={resource.data?.length ?? 0}
          loading={resource.loading}
          error={resource.error}
          after={after}
          cursorName={"after"}
          onHome={() => setAfter("")}
          onNext={() => setAfter(resource.data!.at(-1)!.orderId)}
        >
          <Table<Order>
            rowKey="orderId"
            tableId="orders"
            dataSource={resource.data}
            loading={resource.loading}
            pagination={false}
            locale={{
              emptyText: (
                <Blank
                  text={
                    resource.error
                      ? "列表未加载，请根据错误重试"
                      : filters.active
                        ? "暂无符合筛选条件的订单，请调整或重置筛选"
                        : "还没有订单，完成报价后即可下单"
                  }
                />
              ),
            }}
            scroll={{ x: admin ? (compact ? 680 : 1160) : 720 }}
            columns={[
              {
                title: "订单编号",
                dataIndex: "orderId",
                width: admin
                  ? tradeNumberColumnWidth
                  : compact
                    ? 218
                    : undefined,
                render: (v: string, row) => {
                  const number = row.businessNo ?? v;
                  return (
                    <div className={compact ? "trade-row-identity" : undefined}>
                      <div className={admin ? "order-number-cell" : undefined}>
                        <Button
                          type="link"
                          title={number}
                          onClick={(event) =>
                            previewOrder(v, event.currentTarget)
                          }
                        >
                          {number}
                        </Button>
                        {admin && <BusinessId value={number} copyOnly />}
                      </div>
                      {compact && <TradeRowFacts row={row} workflow={admin} />}
                    </div>
                  );
                },
              },
              ...(!compact
                ? [
                    {
                      title: "订单状态",
                      dataIndex: "status",
                      width: admin ? 112 : undefined,
                      render: (v: string) => <Status value={v} />,
                    },
                    {
                      title: "应付金额",
                      dataIndex: "payable",
                      align: "right" as const,
                      width: admin ? 108 : undefined,
                      className: "trade-amount",
                      render: money,
                    },
                    ...(admin
                      ? [
                          {
                            title: "店铺",
                            dataIndex: "storeId",
                            render: (id: string, row: Order) =>
                              row.storeName ? (
                                <PrimaryCell
                                  title={row.storeName}
                                  subtitle={<BusinessId value={id} />}
                                />
                              ) : (
                                <BusinessId value={id} />
                              ),
                          },
                        ]
                      : [
                          { title: "店铺编号", dataIndex: "storeId" },
                          {
                            title: "店铺名称",
                            dataIndex: "storeName",
                            render: (name: string | undefined) => name ?? "—",
                          },
                        ]),
                  ]
                : []),
              {
                title: "创建时间",
                dataIndex: "createdAt",
                width: admin ? 136 : undefined,
                render: time,
              },
              ...(admin
                ? [
                    ...(!compact
                      ? [
                          {
                            title: "支付方式",
                            dataIndex: "paymentKind",
                            width: 104,
                            render: (value: string) =>
                              formatField("paymentKind", value),
                          },
                        ]
                      : []),
                    {
                      title: "支付截止",
                      dataIndex: "expiresAt",
                      width: 136,
                      render: time,
                    },
                  ]
                : []),
              {
                title: "操作",
                className: "row-actions-cell",
                width: admin && !compact ? 184 : 180,
                fixed: admin && !compact ? "right" : undefined,
                render: (_, r) => (
                  <RowActions>
                    <Button
                      type="link"
                      onClick={(event) =>
                        previewOrder(r.orderId, event.currentTarget)
                      }
                    >
                      查看详情
                    </Button>
                    <Button
                      type="link"
                      onClick={(event) =>
                        openWorkspace(r.orderId, event.currentTarget)
                      }
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
      <RecordModal
        className="record-modal"
        width={600}
        open={!!selected}
        onCancel={() => setSelected(undefined)}
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
      </RecordModal>
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
  const primaryId = order.data?.orderId ?? id;
  const [paymentVisible, setPaymentVisible] = useState(false);
  const payment = useResource<Payment>(
    paymentVisible ? prefix + encode(primaryId) + "/payment" : null,
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
      <ErrorNotice error={order.error} onRetry={order.refresh} />
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
            title={
              <Typography.Text copyable>
                {value.businessNo ?? value.orderId}
              </Typography.Text>
            }
            id={admin ? (value.storeName ?? value.storeId) : value.storeId}
            status={value.status}
            metrics={[
              { label: "应付金额", value: money(value.payable) },
              { label: "会员", value: value.memberId },
              { label: "创建时间", value: time(value.createdAt) },
            ]}
          />
          <div className="order-related">
            {value.businessNo && value.businessNo !== value.orderId && (
              <span>
                原订单编号 <BusinessId value={value.orderId} />
              </span>
            )}
            <TradeLinks
              orderId={value.orderId}
              member={!admin}
              pages={
                admin
                  ? ["fulfillments", "aftersales", "refunds"]
                  : ["aftersales"]
              }
            />
          </div>
          <Descriptions
            className={admin ? "order-operating-facts" : undefined}
            title={admin ? "交易信息" : undefined}
            column={2}
            items={[
              ...(admin && value.storeName
                ? [
                    {
                      key: "storeId",
                      label: "店铺编号",
                      children: <BusinessId value={value.storeId} />,
                    },
                  ]
                : []),
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
              tools={false}
              rowKey="skuId"
              dataSource={value.items}
              pagination={false}
              scroll={{ x: 560 }}
              columns={[
                { title: "商品", dataIndex: "title" },
                { title: "数量", dataIndex: "quantity", align: "right" },
                {
                  title: "行实付",
                  dataIndex: "payable",
                  align: "right",
                  render: money,
                },
                {
                  title: "抵扣积分",
                  dataIndex: "points",
                  align: "right",
                  render: (v) => v ?? 0,
                },
                {
                  title: "积分抵扣额",
                  dataIndex: "pointDiscount",
                  align: "right",
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
                        "/orders/" + encode(primaryId) + "/payments",
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
                  path={"/orders/" + encode(primaryId) + "/cancel"}
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
              <ErrorNotice error={payment.error} onRetry={payment.refresh} />
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
                      path={prefix + encode(primaryId) + "/payment/reconcile"}
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
                  orderId: primaryId,
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
