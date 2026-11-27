import { countText, MetricShare, PaidOrderShare } from "../shared/MetricShare";
import { Table } from "../shared/BusinessTable";
import {
  Alert,
  Button,
  Card,
  Segmented,
  Skeleton,
  Space,
  Spin,
  Typography,
} from "antd";
import { useRouteState } from "../shared/routeState";
import type { ReactNode } from "react";
import {
  compareProjectedAmounts,
  DashboardComparison,
} from "./DashboardComparison";
import {
  DailyTrend,
  trendLabels,
  type Daily,
  type TrendMetric,
} from "./DailyTrend";
import { encode, useResource } from "../shared/api";
import { ErrorNotice, PageHead, money, time } from "../shared/ui";
import { Icon, type IconName } from "../shared/Icon";
type Summary = {
  storeId: string;
  from: string;
  to: string;
  generatedAt: string;
  members: { total: number; active: number; frozen: number; closed: number };
  catalog: { total: number; active: number; frozen: number };
  daily: Daily[];
  totals: {
    paidOrders: number;
    received: string;
    refunded: string;
    netReceipts: string;
    discountGranted: string;
  };
  coverage: string;
};
const shortcuts = [
  {
    page: "growth",
    label: "会员成长",
    text: "等级、周期权益与积分",
    mark: "member",
  },
  {
    page: "segments",
    label: "动态人群",
    text: "把真实行为转为经营对象",
    mark: "member",
  },
  {
    page: "journeys",
    label: "营销旅程",
    text: "生日、复购与流失关怀",
    mark: "marketing",
  },
  {
    page: "skus",
    label: "商品管理",
    text: "规格、渠道价与经营计划",
    mark: "catalog",
  },
];
/** 所有经营数字来自后端域聚合，空值和失败不会生成虚构趋势。 */
export function Dashboard({
  store,
  storeName,
  navigate,
  canNavigate = () => true,
  exceptions,
  embedded = false,
}: {
  store: string;
  storeName?: string;
  navigate: (page: string) => void;
  canNavigate?: (page: string) => boolean;
  exceptions?: ReactNode;
  embedded?: boolean;
}) {
  const data = useResource<Summary>(
    store ? `/admin/dashboard?storeId=${encode(store)}` : null,
  );
  const [metricQuery, setMetric] = useRouteState("metric", "netReceipts");
  const metric: TrendMetric =
    metricQuery === "received" ||
    metricQuery === "refunded" ||
    metricQuery === "paidOrders"
      ? metricQuery
      : "netReceipts";
  const [days, setDays] = useRouteState("trendDays", 30);
  const trendDays = [7, 14, 30].includes(days) ? days : 30;
  const [tableQuery, setTable] = useRouteState("daily", "");
  const table = tableQuery === "open";
  if (!store) return <Alert type="info" title="选择门店查看经营总览" />;
  const summary =
    data.error || data.data?.storeId !== store ? undefined : data.data;
  const daily = summary?.daily ?? [];
  const sums = summary?.totals;
  const visibleDays = daily.slice(-trendDays);
  const projectionTime = daily.reduce<string | undefined>(
    (latest, day) =>
      day.updatedAt &&
      (!latest || Date.parse(day.updatedAt) > Date.parse(latest))
        ? day.updatedAt
        : latest,
    undefined,
  );
  const refresh = (
    <Button onClick={data.refresh} loading={data.loading}>
      刷新总览
    </Button>
  );
  return (
    <div className="dashboard">
      {!embedded && (
        <PageHead
          eyebrow="经营工作台"
          title="经营总览"
          description="处理门店异常，核对成交与退款。"
          extra={refresh}
        />
      )}
      <div className="dashboard-intro">
        <strong className="dashboard-store">
          当前门店 {storeName ? `${storeName} · ${store}` : store}
        </strong>
        {embedded && refresh}
      </div>
      {exceptions}
      <ErrorNotice error={data.error} onRetry={data.refresh} />
      <Spin spinning={data.loading}>
        {data.loading && (
          <Skeleton active title={{ width: "28%" }} paragraph={{ rows: 2 }} />
        )}
        {summary && sums && (
          <>
            <div className="dashboard-intro">
              <span>
                门店交易 · {summary.from.slice(0, 10)} 至{" "}
                {summary.to.slice(0, 10)} UTC
              </span>
              <span>
                {projectionTime
                  ? `最近投影更新 ${time(projectionTime)}`
                  : "此窗口暂无投影更新时间"}
              </span>
              <span className="dashboard-updated">
                读取于 {time(summary.generatedAt)}
              </span>
            </div>
            <div className="metric-grid">
              {[
                {
                  label: "净收金额",
                  value: money(sums.netReceipts),
                  note: "近30天 · 实收减已知成功退款",
                  metric: "netReceipts" as const,
                  tone: "featured",
                  mark: "trade",
                },
                {
                  label: "实收金额",
                  value: money(sums.received),
                  note: "已付订单 · 成交投影",
                  metric: "received" as const,
                  tone: "blue",
                  mark: "trade",
                },
                {
                  label: "成功退款",
                  value: money(sums.refunded),
                  note: "截至投影已知成功退款",
                  metric: "refunded" as const,
                  tone: "teal",
                  mark: "trade",
                },
                {
                  label: "已付订单",
                  value: countText(sums.paidOrders),
                  note: "近30天 · 已投影订单",
                  metric: "paidOrders" as const,
                  tone: "purple",
                  mark: "trade",
                },
              ].map((k) => {
                const selectedMetric = k.metric;
                const content = (
                  <>
                    <span className="metric-label">
                      {k.label}
                      <Icon name={k.mark as IconName} />
                    </span>
                    <strong>{k.value}</strong>
                    <span className="metric-note">{k.note}</span>
                  </>
                );
                return (
                  <Card
                    key={k.label}
                    className={`metric-card metric-${k.tone}`}
                  >
                    {selectedMetric ? (
                      <button
                        className="dashboard-metric-choice"
                        type="button"
                        aria-label={`查看${k.label}趋势`}
                        aria-pressed={metric === selectedMetric}
                        onClick={() => setMetric(selectedMetric)}
                      >
                        {content}
                      </button>
                    ) : (
                      content
                    )}
                  </Card>
                );
              })}
            </div>
            <div className="dashboard-main-grid">
              <Card className="trend-card" title="成交与退款趋势">
                <div className="trend-window">
                  <Segmented
                    aria-label="趋势指标"
                    value={metric}
                    onChange={setMetric}
                    options={Object.entries(trendLabels).map(
                      ([value, label]) => ({
                        label,
                        value,
                      }),
                    )}
                  />
                  <Segmented
                    aria-label="每日趋势范围"
                    value={trendDays}
                    onChange={setDays}
                    options={[7, 14, 30].map((value) => ({
                      label: `${value} 天`,
                      value,
                    }))}
                  />
                </div>
                <div className="trend-summary">
                  <span>
                    成交优惠 <b>{money(sums.discountGranted)}</b>
                  </span>
                </div>
                <DashboardComparison
                  daily={daily}
                  end={summary.to}
                  metric={metric}
                />
                <DailyTrend daily={visibleDays} metric={metric} />
                <Button
                  type="link"
                  onClick={() => setTable(table ? "" : "open")}
                >
                  {table ? "收起每日数据" : "查看每日数据"}
                </Button>
                {table && (
                  <Table<Daily>
                    key={trendDays}
                    tableId="dashboard-daily"
                    size="small"
                    rowKey="day"
                    dataSource={visibleDays}
                    pagination={{ defaultPageSize: 7 }}
                    scroll={{ x: 680 }}
                    columns={[
                      {
                        title: "UTC日期",
                        dataIndex: "day",
                        sorter: (a, b) => a.day.localeCompare(b.day),
                      },
                      {
                        title: "投影下单",
                        dataIndex: "orders",
                        align: "right",
                        render: countText,
                      },
                      {
                        title: "已付订单",

                        dataIndex: "paidOrders",
                        sorter: (a, b) => a.paidOrders - b.paidOrders,
                        render: countText,
                        align: "right",
                      },
                      {
                        title: "已付比例",
                        key: "paidOrderShare",
                        width: 104,
                        render: (_, row) => (
                          <PaidOrderShare
                            paid={row.paidOrders}
                            orders={row.orders}
                          />
                        ),
                      },
                      {
                        title: "实收",

                        dataIndex: "received",
                        render: money,
                        align: "right",
                        sorter: (a, b) =>
                          compareProjectedAmounts(a.received, b.received),
                      },
                      {
                        title: "退款",
                        dataIndex: "refunded",
                        render: money,
                        align: "right",
                        sorter: (a, b) =>
                          compareProjectedAmounts(a.refunded, b.refunded),
                      },
                      {
                        title: "净收",
                        dataIndex: "netReceipts",
                        render: money,
                      },
                      {
                        title: "优惠",
                        dataIndex: "discountGranted",
                        render: money,
                      },
                    ]}
                  />
                )}
              </Card>
              <Card title="经营规模" className="state-card">
                <div className="state-heading">
                  <Icon name="member" />
                  <Typography.Text type="secondary">
                    会员 · 当前租户
                  </Typography.Text>
                </div>
                <div className="state-total">
                  {countText(summary.members.active)}
                  <small>可用会员</small>
                </div>
                <MetricShare
                  total={summary.members.total}
                  segments={[
                    {
                      label: "可用",
                      value: summary.members.active,
                      tone: "success",
                    },
                    {
                      label: "冻结",
                      value: summary.members.frozen,
                      tone: "warning",
                    },
                    {
                      label: "注销",
                      value: summary.members.closed,
                      tone: "muted",
                    },
                  ]}
                  caption={`租户会员状态分布，可用 ${countText(summary.members.active)}，已冻结 ${countText(summary.members.frozen)}，已注销 ${countText(summary.members.closed)}`}
                />
                <div className="state-row">
                  <span>已冻结</span>
                  <b>{countText(summary.members.frozen)}</b>
                </div>
                <div className="state-row">
                  <span>已注销</span>
                  <b>{countText(summary.members.closed)}</b>
                </div>
                <div className="state-row">
                  <span>会员总数</span>
                  <b>{countText(summary.members.total)}</b>
                </div>
                <div className="state-divider" />
                <div className="state-heading">
                  <Icon name="catalog" />
                  <Typography.Text type="secondary">
                    商品 · 当前门店
                  </Typography.Text>
                </div>
                <div className="state-row">
                  <span>在售规格</span>
                  <b>{countText(summary.catalog.active)}</b>
                </div>
                <div className="state-row">
                  <span>下架规格</span>
                  <b>{countText(summary.catalog.frozen)}</b>
                </div>
                <MetricShare
                  total={summary.catalog.total}
                  segments={[
                    {
                      label: "在售",
                      value: summary.catalog.active,
                      tone: "accent",
                    },
                    {
                      label: "下架",
                      value: summary.catalog.frozen,
                      tone: "muted",
                    },
                  ]}
                  caption={`当前门店规格状态分布，在售 ${countText(summary.catalog.active)}，下架 ${countText(summary.catalog.frozen)}`}
                />
                {canNavigate("members") && (
                  <Button block onClick={() => navigate("members")}>
                    查看会员档案
                  </Button>
                )}
                {canNavigate("skus") && (
                  <Button type="link" block onClick={() => navigate("skus")}>
                    查看门店商品
                  </Button>
                )}
              </Card>
            </div>
            <div className="shortcut-grid">
              {shortcuts
                .filter((s) => canNavigate(s.page))
                .map((s) => (
                  <button
                    className="dashboard-shortcut"
                    key={s.page}
                    onClick={() => navigate(s.page)}
                  >
                    <span>
                      <Icon name={s.mark as IconName} />
                    </span>
                    <div>
                      <strong>{s.label}</strong>
                      <small>{s.text}</small>
                    </div>
                    <Icon name="arrow" className="shortcut-arrow" />
                  </button>
                ))}
            </div>
            <details className="dashboard-basis">
              <summary>数据口径与说明</summary>
              <Typography.Text type="secondary">
                {summary.coverage}{" "}
                成交优惠不扣减退款，不含货品、支付或渠道成本；净收不等于利润。
              </Typography.Text>
              <Space wrap style={{ marginTop: 8 }}>
                {canNavigate("effects") && (
                  <Button type="link" onClick={() => navigate("effects")}>
                    进入营销效果分析
                  </Button>
                )}
                {canNavigate("events") && (
                  <Button type="link" onClick={() => navigate("events")}>
                    查看事件处理
                  </Button>
                )}
              </Space>
            </details>
          </>
        )}
      </Spin>
    </div>
  );
}
