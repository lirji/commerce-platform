import {
  Alert,
  Button,
  Card,
  Segmented,
  Space,
  Spin,
  Table,
  Typography,
} from "antd";
import { useRouteState } from "../shared/routeState";
import { DailyTrend, type Daily, type TrendMetric } from "./DailyTrend";
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
  navigate,
}: {
  store: string;
  navigate: (page: string) => void;
}) {
  const data = useResource<Summary>(
    store ? `/admin/dashboard?storeId=${encode(store)}` : null,
  );
  const [metricQuery, setMetric] = useRouteState("metric", "netReceipts");
  const metric: TrendMetric =
    metricQuery === "received" || metricQuery === "refunded"
      ? metricQuery
      : "netReceipts";
  const [tableQuery, setTable] = useRouteState("daily", "");
  const table = tableQuery === "open";
  if (!store) return <Alert type="info" title="选择门店查看经营总览" />;
  const summary = data.data;
  const daily = summary?.daily ?? [];
  const sums = summary?.totals;
  return (
    <div className="dashboard">
      <PageHead
        eyebrow="经营工作台"
        title="经营总览"
        description="会员、商品与成交，沿着真实数据看清经营。"
        extra={
          <Button onClick={data.refresh} loading={data.loading}>
            刷新总览
          </Button>
        }
      />
      <ErrorNotice error={data.error} onRetry={data.refresh} />
      <Spin spinning={data.loading}>
        {summary && sums && (
          <>
            <div className="dashboard-intro">
              <span className="live-dot" />
              <span>当前门店 · 近30个UTC自然日</span>
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
                  tone: "featured",
                  mark: "trade",
                },
                {
                  label: "会员总数",
                  value: summary.members.total.toLocaleString(),
                  note: `租户范围 · 可用 ${summary.members.active}`,
                  tone: "blue",
                  mark: "member",
                },
                {
                  label: "在售规格",
                  value: summary.catalog.active.toLocaleString(),
                  note: `当前门店 · 全部 ${summary.catalog.total}`,
                  tone: "teal",
                  mark: "catalog",
                },
                {
                  label: "已付订单",
                  value: sums.paidOrders.toLocaleString(),
                  note: "近30天 · 已投影订单",
                  tone: "purple",
                  mark: "trade",
                },
              ].map((k) => (
                <Card key={k.label} className={`metric-card metric-${k.tone}`}>
                  <span className="metric-label">
                    {k.label}
                    <Icon name={k.mark as IconName} />
                  </span>
                  <strong>{k.value}</strong>
                  <span className="metric-note">{k.note}</span>
                </Card>
              ))}
            </div>
            <div className="dashboard-main-grid">
              <Card
                className="trend-card"
                title="成交与退款趋势"
                extra={
                  <Segmented
                    aria-label="趋势指标"
                    value={metric}
                    onChange={(v) => setMetric(String(v))}
                    options={[
                      { label: "净收", value: "netReceipts" },
                      { label: "实收", value: "received" },
                      { label: "退款", value: "refunded" },
                    ]}
                  />
                }
              >
                <div className="trend-summary">
                  <span>
                    实收 <b>{money(sums.received)}</b>
                  </span>
                  <span>
                    成功退款 <b>{money(sums.refunded)}</b>
                  </span>
                  <span>
                    成交优惠 <b>{money(sums.discountGranted)}</b>
                  </span>
                </div>
                <DailyTrend daily={daily} metric={metric} />
                <Button
                  type="link"
                  onClick={() => setTable(table ? "" : "open")}
                >
                  {table ? "收起每日数据" : "查看每日数据"}
                </Button>
                {table && (
                  <Table<Daily>
                    size="small"
                    rowKey="day"
                    dataSource={daily}
                    pagination={{ pageSize: 7 }}
                    scroll={{ x: 680 }}
                    columns={[
                      { title: "UTC日期", dataIndex: "day" },
                      { title: "已付订单", dataIndex: "paidOrders" },
                      { title: "实收", dataIndex: "received", render: money },
                      { title: "退款", dataIndex: "refunded", render: money },
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
              <Card title="经营状态" className="state-card">
                <div className="state-heading">
                  <Icon name="member" />
                  <Typography.Text type="secondary">
                    会员 · 当前租户
                  </Typography.Text>
                </div>
                <div className="state-total">
                  {summary.members.active}
                  <small>可用会员</small>
                </div>
                <div className="state-track">
                  <span
                    style={{
                      width: `${summary.members.total ? (summary.members.active / summary.members.total) * 100 : 0}%`,
                    }}
                  />
                </div>
                <div className="state-row">
                  <span>已冻结</span>
                  <b>{summary.members.frozen}</b>
                </div>
                <div className="state-row">
                  <span>已注销</span>
                  <b>{summary.members.closed}</b>
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
                  <b>{summary.catalog.active}</b>
                </div>
                <div className="state-row">
                  <span>下架规格</span>
                  <b>{summary.catalog.frozen}</b>
                </div>
                <Button block onClick={() => navigate("members")}>
                  查看会员档案
                </Button>
              </Card>
            </div>
            <div className="shortcut-grid">
              {shortcuts.map((s) => (
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
                <Button type="link" onClick={() => navigate("effects")}>
                  进入营销效果分析
                </Button>
                <Button type="link" onClick={() => navigate("events")}>
                  查看事件处理
                </Button>
              </Space>
            </details>
          </>
        )}
      </Spin>
    </div>
  );
}
