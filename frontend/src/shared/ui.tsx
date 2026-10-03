import { CursorBack } from "./pagination";
import { useCursorState } from "./routeState";
import {
  Alert,
  Button,
  Card,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Spin,
  Switch,
  Tag,
  Typography,
} from "antd";
import { palette } from "../theme";
import { useDirtyClose, useRowAction } from "./interactions";
export {
  RowActions,
  RecordModal,
  FormActions,
  useDirtyClose,
  useRowAction,
} from "./interactions";
import { useId, useState, type ReactNode } from "react";
import { ApiError, useCommand } from "./api";
export const time = (v: unknown) => {
  if (v == null || v === "") return "未提供";
  const date = new Date(String(v));
  return Number.isFinite(date.getTime())
    ? date.toLocaleString("zh-CN", { hour12: false })
    : "时间格式无效";
};
export const money = (v: unknown) => {
  if (v == null || v === "") return "未提供";
  const value = String(v);
  if (!/^-?\d+(\.\d+)?$/.test(value)) return value;
  const [integer, decimal = ""] = value.split(".");
  return `¥${integer.replace(/\B(?=(\d{3})+(?!\d))/g, ",")}.${decimal.padEnd(2, "0")}`;
};
const labels: Record<string, string> = {
  ACTIVE: "可用",
  INACTIVE: "已停用",
  ISSUED: "已发放",
  KEPT: "已保留",
  PREVIEW: "已预览",
  FINISHED: "执行完成",
  FAILED: "处理失败",
  STOPPED: "已停止",
  SKIPPED: "已跳过",
  ISSUE: "发放",
  REVOKE: "撤回",
  SCHEDULED: "待执行",
  CONFLICT: "版本冲突",
  REVOCATION_DONE: "撤销处理完成",
  RETIRED: "已停用",
  IDLE: "等待下一轮",
  REVOKING: "撤销处理中",
  FROZEN: "已冻结",
  DRAFT: "草稿",
  IN_REVIEW: "待审批",
  APPROVED: "已批准",
  REJECTED: "已驳回",
  PUBLISHED: "已发布",
  PAUSED: "已暂停",
  PENDING_PAYMENT: "待付款",
  PAYMENT_IN_PROGRESS: "支付处理中",
  CLOSING: "关单确认中",
  PAID: "已付款",
  FULFILLING: "履约中",
  COMPLETED: "已完成",
  CANCELLED: "已取消",
  UNKNOWN: "结果待确认",
  OPEN: "待支付",
  CLOSED: "已关闭",
  READY: "待发货",
  SHIPPED: "已发货",
  DELIVERED: "已签收",
  REQUESTED: "已受理",
  WAIT_RETURN: "待退货入库",
  REFUNDING: "退款处理中",
  SUCCEEDED: "已成功",
  AVAILABLE: "可使用",
  HELD: "已占用",
  USED: "已使用",
  EXPIRED: "已过期",
  RESERVED: "已预留",
  CONSUMED: "已核销",
  REVOKED: "已冲正",
  COMPENSATION_REQUIRED: "待补偿",
  COMPENSATED: "已处理",
  RUNNING: "运行中",
  WAITING: "等待中",
  ISOLATED: "已隔离",
  TIMED_OUT: "已超时",
  PENDING: "待处理",
};
export const statusText = (value: string) =>
  labels[value] ?? `未知状态（${value}）`;
export function Status({ value }: { value?: string }) {
  if (value == null || value === "")
    return <Typography.Text type="secondary">未提供</Typography.Text>;
  const v = value;
  const tone = [
    "PAID",
    "COMPLETED",
    "SUCCEEDED",
    "AVAILABLE",
    "APPROVED",
    "PUBLISHED",
    "ACTIVE",
    "DELIVERED",
  ].includes(v)
    ? "ok"
    : ["ISOLATED", "COMPENSATION_REQUIRED", "REJECTED", "TIMED_OUT"].includes(v)
      ? "error"
      : ["UNKNOWN", "CLOSING", "WAIT_RETURN", "EXPIRED"].includes(v)
        ? "warn"
        : [
              "RUNNING",
              "WAITING",
              "REFUNDING",
              "PAYMENT_IN_PROGRESS",
              "HELD",
              "RESERVED",
              "REQUESTED",
              "PENDING",
            ].includes(v)
          ? "pending"
          : "idle";
  return (
    <Tag
      title={v}
      className="status-tag"
      style={{
        background: palette[`${tone}Soft`],
        color: palette[tone],
        borderColor: "transparent",
      }}
    >
      {statusText(v)}
    </Tag>
  );
}
export function ErrorNotice({ error }: { error?: Error }) {
  if (!error) return null;
  const detail =
    error instanceof ApiError
      ? `${error.status === 409 ? "状态或幂等冲突，请刷新核对后重试。" : error.status === 403 ? "当前身份没有此操作权限。" : error.status === 401 ? "访问凭据无效或已过期，请退出后重新登录。" : ""}${error.traceId ? ` 追踪编号：${error.traceId}` : ""}`
      : "网络响应未知。若刚提交过操作，请保留输入重试或刷新核对。";
  return (
    <Alert
      type="error"
      showIcon
      title={error.message}
      description={detail}
      style={{ marginBottom: 16 }}
    />
  );
}
export function Loading({
  loading,
  children,
}: {
  loading: boolean;
  children: ReactNode;
}) {
  return <Spin spinning={loading}>{children}</Spin>;
}
export function Workbench({ children }: { children: ReactNode }) {
  return <div className="workbench">{children}</div>;
}
export function PageHead({
  title,
  description,
  extra,
  eyebrow,
}: {
  title: string;
  description: string;
  extra?: ReactNode;
  eyebrow?: string;
}) {
  return (
    <div className="page-head">
      <div>
        {eyebrow && <div className="page-eyebrow">{eyebrow}</div>}
        <Typography.Title level={1}>{title}</Typography.Title>
        <Typography.Text type="secondary">{description}</Typography.Text>
      </div>
      <Space wrap className="page-head-actions">
        {extra}
      </Space>
    </div>
  );
}
export function Blank({ text = "暂无记录" }: { text?: string }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={text} />;
}
const fieldNames: Record<string, string> = {
  body: "内容",
  yesNext: "命中时下一节点",
  noNext: "未命中时下一节点",
  journeyVersion: "旅程版本",
  benefitVersion: "权益版本",
  validityDays: "领取后有效天数",
  stackable: "允许叠加",
  issuanceMode: "领取方式",
  sourceType: "来源类型",
  currency: "币种",
  permission: "经营权限",
  steps: "执行步数",
  result: "处理结果",
  transientAttempts: "自动重试次数",
  manualRetries: "人工重试次数",
  nodes: "执行节点",
  items: "商品明细",
  lines: "分摊明细",
  trigger: "触发方式",
  next: "下一节点",
  yes: "命中分支",
  no: "未命中分支",
  seconds: "等待秒数",
  expression: "条件表达式",
  controls: "进入与频次限制",
  lifecycle: "生命周期配置",
  entry: "入口节点",
  entryRule: "入组条件",
  maxDurationSeconds: "最长运行秒数",
  maxEntries: "窗口内最多进入次数",
  entryWindowSeconds: "进入窗口秒数",
  notificationLimit: "通知次数上限",
  notificationWindowSeconds: "通知窗口秒数",
  runId: "任务标识",
  snapshotVersion: "快照版本",
  definitionVersion: "定义版本",
  matched: "匹配人数",
  lastError: "最近错误",
  maxMembers: "扫描人数上限",
  ttlSeconds: "快照有效秒数",
  refreshSeconds: "刷新间隔秒数",
  pointDiscount: "积分抵扣金额",
  quantity: "数量",
  sections: "展示组件",
  actions: "业务操作",
  notificationId: "通知标识",
  headline: "标题",
  description: "说明",
  beforeValue: "变更前",
  afterValue: "变更后",
  channel: "渠道",
  thresholdDays: "触发天数",
  cartDelaySeconds: "加购等待秒数",
  scanIntervalSeconds: "扫描间隔秒数",
  conversionWindowDays: "归因窗口天数",
  id: "标识",
  rule: "资格规则",
  ruleId: "规则标识",
  policy: "活动策略",
  terms: "优惠配置",
  audience: "关联人群",
  grant: "支付后权益",
  pricing: "商品范围与阶梯",
  includedSkuIds: "参与商品",
  excludedSkuIds: "排除商品",
  tiers: "优惠阶梯",
  percentageBps: "优惠比例",
  platformFundingBps: "平台承担比例",
  memberGrowth: "会员成长值",
  memberNetSpend: "会员净消费",
  memberTags: "会员标签",
  memberStatus: "会员状态",
  memberBrowse30: "近30天浏览次数",
  memberCart30: "近30天加购次数",
  memberOrders30: "近30天完成订单数",
  memberSpend30: "近30天净现金消费",
  memberDaysSinceOrder: "距最近成交天数",
  memberDaysSinceJoin: "入会天数",
  memberBirthdayToday: "今日生日",
  memberJourneyEnabled: "接收旅程",
  orderAmount: "订单金额",
  kind: "类型",
  field: "条件字段",
  operator: "比较方式",
  valueType: "值类型",
  value: "条件值",
  actorId: "认证主体",
  active: "启用",
  after: "游标",
  aggregateId: "业务标识",
  amount: "金额",
  attempts: "失败次数",
  audienceId: "人群标识",
  available: "可售",
  availableAt: "可投递时间",
  barcode: "经营条码",
  benefitId: "权益标识",
  blocked: "售后阻拦",
  brand: "品牌",
  budget: "营销预算",
  budgetId: "预算标识",
  campaignId: "活动标识",
  cap: "预算上限",
  caseId: "售后编号",
  category: "分类",
  content: "业务内容",
  createdAt: "创建时间",
  currentNode: "待执行节点",
  deadline: "截止时间",
  debtUnits: "待补偿单位",
  definitionId: "定义标识",
  discountAmount: "优惠金额",
  displayName: "显示名称",
  dueAt: "下次执行",
  enabled: "周期刷新",
  errorCode: "失败分类",
  eventId: "事件标识",
  eventKey: "去重标识",
  eventType: "事件类型",
  expiresAt: "过期时间",
  grantId: "授权标识",
  held: "已预占",
  instanceId: "实例标识",
  issued: "已发行",
  journeyId: "旅程标识",
  lockVersion: "锁版本",
  memberCount: "人数",
  memberId: "会员标识",
  memberLevel: "会员等级",
  merchantId: "商家标识",
  minimumSpend: "消费门槛",
  name: "名称",
  netSpend: "净消费",
  orderId: "订单标识",
  payable: "应付金额",
  paymentKind: "支付方式",
  platformFunding: "平台承担",
  policyVersion: "规则版本",
  processed: "已处理",
  provider: "适配渠道",
  quota: "发行额度",
  reason: "原因",
  refundAmount: "退款金额",
  refundId: "退款编号",
  remainingUnits: "剩余单位",
  reserved: "已预留",
  resourceId: "资源标识",
  resourceType: "资源范围",
  returnRequired: "需退货",
  revision: "修订版本",
  skuId: "SKU标识",
  sold: "已售",
  source: "来源",
  spent: "已消耗",
  status: "状态",
  storeId: "店铺标识",
  tagId: "标签标识",
  title: "名称",
  trackingNo: "物流单号",
  unitPrice: "销售单价",
  units: "每份单位",
  updatedAt: "更新时间",
  validFrom: "开始时间",
  validTo: "结束时间",
  validUntil: "有效期",
  version: "版本",
  watermark: "来源水位",
};
const moneyKeys = new Set([
  "amount",
  "budget",
  "cap",
  "discountAmount",
  "held",
  "minimumSpend",
  "netReceipts",
  "netSpend",
  "payable",
  "platformFunding",
  "pointDiscount",
  "received",
  "refundAmount",
  "refunded",
  "spent",
  "unitPrice",
]);
const timeKeys = new Set([
  "availableAt",
  "createdAt",
  "deadline",
  "dueAt",
  "effectiveFrom",
  "expiresAt",
  "generatedAt",
  "startedAt",
  "updatedAt",
  "validFrom",
  "validTo",
  "validUntil",
  "watermark",
]);
export function fieldLabel(key: string) {
  return fieldNames[key] ?? key;
}
export function formatField(key: string, value: unknown): ReactNode {
  if (value == null || value === "") return "未提供";
  const enumLabels: Record<string, string> = {
    WAIT: "等待",
    DECIDE: "条件分支",
    GRANT: "授予权益",
    COUPON: "发放优惠券",
    NOTIFY: "站内通知",
    END: "结束",
    PUBLIC: "公开领取",
    SOURCE_ONLY: "定向发放",
    ORDER: "订单",
    JOURNEY: "营销旅程",
    FINISHED: "执行完成",
    CHANNEL_REQUIRED: "渠道支付",
    FREE: "无需付款",
    ZERO_AMOUNT: "无需付款",
    SANDBOX: "本地沙箱",
    SANDBOX_WMS: "沙箱物流",
    UNASSIGNED: "尚未分配",
    CNY: "人民币 · CNY",
    COMPARE: "条件比较",
    ALL: "全部满足",
    ANY: "任一满足",
    NOT: "取反",
    EQ: "等于",
    GT: "大于",
    GTE: "大于等于",
    LT: "小于",
    LTE: "小于等于",
    CONTAINS: "包含",
    TEXT: "文本",
    DECIMAL: "数值",
    UNPROCESSED: "仅处理尚未处理的事件",
    REPROCESS: "重新处理事件",
    RETRY: "重试",
    RESET_TRANSIENT: "恢复暂时故障",
    ISOLATE: "隔离停止处理",
    SKIP: "跳过",
    TRANSIENT: "暂时故障",
    CONCURRENCY_RETRYABLE: "并发冲突，可重试",
    DEPENDENCY_UNAVAILABLE: "依赖服务不可用",
    BUSINESS_REJECTED: "业务条件不满足",
    DATA_CORRUPTION: "业务数据异常",
    CONFIGURATION_ERROR: "服务配置异常",
    PERMANENT: "需要人工处理",
  };
  if (
    [
      "kind",
      "operator",
      "valueType",
      "sourceType",
      "issuanceMode",
      "paymentKind",
      "provider",
      "currency",
      "result",
      "failureClass",
      "mode",
      "action",
    ].includes(key)
  )
    return enumLabels[String(value)] ?? String(value);
  if (key.endsWith("Bps")) return `${Number(value) / 100}%`;
  if (key === "field") return fieldLabel(String(value));
  if (
    [
      "status",
      "state",
      "grantStatus",
      "eventStatus",
      "previousState",
      "newState",
    ].includes(key)
  )
    return <Status value={string(value)} />;
  if (
    moneyKeys.has(key) &&
    (typeof value === "string" ||
      (typeof value === "number" && !["held", "spent"].includes(key)))
  )
    return money(value);
  if (timeKeys.has(key)) return time(value);
  if (typeof value === "boolean") return value ? "是" : "否";
  if (typeof value === "object") return null;
  return String(value);
}
function flattenRecord(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) return {};
  const row = value as Record<string, unknown>;
  const nested = row.content;
  if (nested && typeof nested === "object" && !Array.isArray(nested)) {
    const { content: _ignored, ...rest } = row;
    return { ...(nested as Record<string, unknown>), ...rest };
  }
  return row;
}
export function RecordFields({
  value,
  grouped = true,
}: {
  value: unknown;
  grouped?: boolean;
}) {
  if (value == null) return <Blank />;
  if (Array.isArray(value)) {
    if (!value.length) return <span className="muted">—</span>;
    if (value.every((item) => typeof item !== "object"))
      return (
        <div className="record-tags">
          {value.map((item, i) => (
            <Tag key={i}>{String(item)}</Tag>
          ))}
        </div>
      );
    return (
      <div className="record-stack">
        {value.map((item, i) => (
          <div className="record-nested" key={i}>
            <RecordFields value={item} />
          </div>
        ))}
      </div>
    );
  }
  if (typeof value !== "object") {
    return <div className="record-scalar">{String(value)}</div>;
  }
  const row = flattenRecord(value);
  const scalars: [string, ReactNode][] = [];
  const nested: [string, unknown][] = [];
  for (const [key, item] of Object.entries(row)) {
    if (item == null || item === "") continue;
    if (typeof item === "object") nested.push([key, item]);
    else scalars.push([key, formatField(key, item)]);
  }
  const categories: { title: string; fields: typeof scalars }[] = [
    { title: "基本信息", fields: [] },
    { title: "金额与数量", fields: [] },
    { title: "时间与有效期", fields: [] },
    { title: "关联记录", fields: [] },
    { title: "版本与处理信息", fields: [] },
  ];
  for (const item of scalars) {
    const key = item[0];
    const index =
      moneyKeys.has(key) ||
      /^(quantity|units|remainingUnits|debtUnits|available|reserved|sold|quota|issued|memberCount)$/.test(
        key,
      )
        ? 1
        : timeKeys.has(key)
          ? 2
          : /Id$/.test(key)
            ? 3
            : /^(version|revision|lockVersion|attempts|errorCode|watermark|processed)$/.test(
                  key,
                )
              ? 4
              : 0;
    categories[index].fields.push(item);
  }
  const groups = grouped
    ? categories.filter((group) => group.fields.length)
    : [{ title: "", fields: scalars }];
  return (
    <>
      {groups.map((group) => (
        <section className="record-group" key={group.title}>
          {grouped && groups.length > 1 && (
            <h3 className="detail-section-title">{group.title}</h3>
          )}
          <dl className="record-fields">
            {group.fields.map(([key, item]) => (
              <div className="record-field" key={key}>
                <dt>{fieldLabel(key)}</dt>
                <dd>{item}</dd>
              </div>
            ))}
          </dl>
        </section>
      ))}
      {nested.map(([key, item]) => (
        <section className="detail-section" key={key}>
          <div className="detail-section-title">{fieldLabel(key)}</div>
          <RecordFields value={item} />
        </section>
      ))}
    </>
  );
}
export function Detail({ value }: { value: unknown }) {
  return (
    <div className="record-detail">
      <RecordFields value={value} />
      <details className="record-raw">
        <summary>原始记录</summary>
        <pre className="json-detail">{JSON.stringify(value, null, 2)}</pre>
      </details>
    </div>
  );
}
export function PrimaryCell({
  title,
  subtitle,
  status,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  status?: string;
}) {
  return (
    <div className="primary-cell">
      <strong>{title}</strong>
      {subtitle != null && subtitle !== "" && (
        <span className="muted">{subtitle}</span>
      )}
      {status && (
        <span className="mobile-row-status">
          <Status value={status} />
        </span>
      )}
    </div>
  );
}
export function RecordHero({
  eyebrow,
  title,
  id,
  status,
  metrics,
  extra,
}: {
  eyebrow?: string;
  title: ReactNode;
  id?: ReactNode;
  status?: string;
  metrics?: { label: string; value: ReactNode }[];
  extra?: ReactNode;
}) {
  return (
    <div className="record-hero">
      <div className="record-hero-main">
        <div>
          {eyebrow && <div className="page-eyebrow">{eyebrow}</div>}
          <div className="record-hero-title">{title}</div>
          {id != null && id !== "" && (
            <div className="record-hero-id">{id}</div>
          )}
        </div>
        <Space wrap>
          {status && <Status value={status} />}
          {extra}
        </Space>
      </div>
      {!!metrics?.length && (
        <div className="record-metrics">
          {metrics.map((item) => (
            <div className="record-metric" key={item.label}>
              <span>{item.label}</span>
              <strong>{item.value}</strong>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
export function Pager({
  after,
  count,
  pageSize = 50,
  onHome,
  onNext,
  homeLabel = "首页",
  nextLabel = "下一页",
  cursorName = "after",
  loading = false,
  error,
  initial,
}: {
  after?: unknown;
  initial?: string | number;
  cursorName?: string;
  loading?: boolean;
  error?: Error;
  count: number;
  pageSize?: number;
  onHome: () => void;
  onNext: () => void;
  homeLabel?: string;
  nextLabel?: string;
}) {
  const stringInitial = typeof initial === "string" ? initial : "";
  const numberInitial = typeof initial === "number" ? initial : 0;
  const [, setString] = useCursorState(cursorName, stringInitial);
  const [, setNumber] = useCursorState(cursorName, numberInitial);
  const atHome =
    after == null ||
    after === (typeof after === "number" ? numberInitial : stringInitial);
  const blocked = loading || !!error;
  return (
    <div className="pager">
      <span className="list-count">
        {loading
          ? "正在加载…"
          : error
            ? "列表未加载"
            : `本页 ${count} 条 · 每页最多${pageSize}条`}
      </span>
      {
        <div className="pager-navigation">
          {typeof after === "number" ? (
            <CursorBack
              name={cursorName}
              after={after}
              initial={numberInitial}
              onPrevious={setNumber}
              disabled={blocked}
            />
          ) : (
            <CursorBack
              name={cursorName}
              after={String(after ?? "")}
              initial={stringInitial}
              onPrevious={setString}
              disabled={blocked}
            />
          )}
          <Button disabled={blocked || atHome} onClick={onHome}>
            {homeLabel}
          </Button>
          <Button disabled={blocked || count < pageSize} onClick={onNext}>
            {nextLabel}
          </Button>
        </div>
      }
    </div>
  );
}
export function ListPanel({
  children,
  toolbar,
  count,
  after,
  onHome,
  onNext,
  pageSize,
  homeLabel,
  nextLabel,
  cursorName,
  loading,
  error,
}: {
  children: ReactNode;
  toolbar?: ReactNode;
  count?: number;
  after?: unknown;
  cursorName?: string;
  loading?: boolean;
  error?: Error;
  onHome?: () => void;
  onNext?: () => void;
  pageSize?: number;
  homeLabel?: string;
  nextLabel?: string;
}) {
  return (
    <Card className="list-panel">
      {toolbar && <div className="list-toolbar">{toolbar}</div>}
      <div className="list-table">{children}</div>
      <TableReadHint />
      {onHome && onNext && count != null && (
        <Pager
          after={after}
          cursorName={cursorName}
          loading={loading}
          error={error}
          count={count}
          pageSize={pageSize}
          onHome={onHome}
          onNext={onNext}
          homeLabel={homeLabel}
          nextLabel={nextLabel}
        />
      )}
    </Card>
  );
}
export function TableReadHint() {
  return (
    <Typography.Text type="secondary" className="table-read-hint">
      左右滑动表格可查看完整字段和操作
    </Typography.Text>
  );
}
export type Field = {
  name: string;
  label: string;
  type?:
    | "number"
    | "money"
    | "datetime"
    | "textarea"
    | "select"
    | "switch"
    | "password";
  required?: boolean;
  options?: { label: string; value: string | number }[];
  min?: number;
  max?: number;
  initial?: unknown;
  help?: string;
};
export type Values = Record<string, unknown>;
export function Fields({ fields }: { fields: Field[] }) {
  return (
    <>
      {fields.map((f) => (
        <Form.Item
          key={f.name}
          name={f.name}
          label={f.label}
          rules={
            f.required === false
              ? []
              : [{ required: true, message: `请输入${f.label}` }]
          }
          valuePropName={f.type === "switch" ? "checked" : "value"}
          extra={f.help}
        >
          {f.type === "number" ? (
            <InputNumber
              min={f.min ?? 0}
              max={f.max ?? (/version$/i.test(f.name) ? 2147483647 : 1000000)}
              precision={0}
              style={{ width: "100%" }}
            />
          ) : f.type === "money" ? (
            <Input inputMode="decimal" placeholder="0.00" />
          ) : f.type === "datetime" ? (
            <Input type="datetime-local" />
          ) : f.type === "textarea" ? (
            <Input.TextArea rows={3} />
          ) : f.type === "select" ? (
            <Select options={f.options} />
          ) : f.type === "switch" ? (
            <Switch />
          ) : f.type === "password" ? (
            <Input.Password autoComplete="off" />
          ) : (
            <Input maxLength={256} />
          )}
        </Form.Item>
      ))}
    </>
  );
}
export function CommandModal({
  title,
  fields,
  path,
  build,
  onDone,
  children,
  disabled = false,
  initialValues = {},
  buttonType,
  hideButton = false,
  open: openProp,
  onOpenChange,
}: {
  title: string;
  fields: Field[];
  path: string | ((v: Values) => string);
  build?: (v: Values) => unknown;
  onDone: () => void;
  children?: ReactNode;
  disabled?: boolean;
  initialValues?: Values;
  buttonType?: "primary" | "default" | "link";
  hideButton?: boolean;
  open?: boolean;
  onOpenChange?: (open: boolean) => void;
}) {
  const formId = useId();
  const rowAction = useRowAction();
  const [innerOpen, setInnerOpen] = useState(false);
  const [form] = Form.useForm();
  const command = useCommand();
  const open = openProp ?? innerOpen;
  const setOpen = (next: boolean) => {
    onOpenChange?.(next);
    if (openProp === undefined) setInnerOpen(next);
  };
  const closing = useDirtyClose(form, command.busy, () => setOpen(false));
  return (
    <>
      {closing.contextHolder}
      {!hideButton && (
        <Button
          type={rowAction ? "link" : (buttonType ?? "primary")}
          disabled={disabled}
          onClick={() => {
            command.clear();
            setOpen(true);
          }}
        >
          {title}
        </Button>
      )}
      <Modal
        title={title}
        open={open}
        afterOpenChange={(next) => {
          if (next) command.clear();
        }}
        className="command-modal"
        afterClose={() => form.resetFields()}
        onCancel={closing.requestClose}
        keyboard={!command.busy}
        mask={{ closable: !command.busy }}
        footer={
          <div className="form-actions">
            <Button disabled={command.busy} onClick={closing.requestClose}>
              取消
            </Button>
            <Button
              type="primary"
              loading={command.busy}
              onClick={() => form.submit()}
            >
              确认提交
            </Button>
          </div>
        }
        destroyOnHidden
      >
        <ErrorNotice error={command.error} />
        <Form
          form={form}
          name={`command-${formId}`}
          layout="vertical"
          initialValues={{
            ...Object.fromEntries(
              fields
                .filter((f) => f.initial !== undefined)
                .map((f) => [f.name, f.initial]),
            ),
            ...initialValues,
          }}
          onFinish={async (v) => {
            try {
              const result = await command.run(
                typeof path === "string" ? path : path(v),
                build ? build(v) : v,
              );
              if (result !== undefined) {
                form.resetFields();
                setOpen(false);
                onDone();
              }
            } catch (e) {
              form.setFields([
                {
                  name: fields[0]?.name,
                  errors: [e instanceof Error ? e.message : "参数无效"],
                },
              ]);
            }
          }}
        >
          <Fields fields={fields} />
          {children}
        </Form>
      </Modal>
    </>
  );
}
export function ActionButton({
  path,
  body,
  label,
  onDone,
  danger = false,
  disabled = false,
  confirm,
}: {
  path: string;
  body?: unknown;
  label: string;
  onDone: () => void;
  danger?: boolean;
  disabled?: boolean;
  confirm?: { title: string; description: string };
}) {
  const c = useCommand();
  const [modal, contextHolder] = Modal.useModal();
  const rowAction = useRowAction();
  return (
    <span>
      {contextHolder}
      <Button
        type={rowAction ? "link" : "default"}
        danger={danger}
        disabled={disabled}
        loading={c.busy}
        onClick={async () => {
          if (
            confirm &&
            !(await modal.confirm({
              title: confirm.title,
              content: confirm.description,
              okText: "确认取消",
              cancelText: "保留订单",
              okButtonProps: { danger: true },
            }))
          )
            return;
          if ((await c.run(path, body)) !== undefined) onDone();
        }}
      >
        {label}
      </Button>
      <ErrorNotice error={c.error} />
    </span>
  );
}
export const string = (v: unknown) => String(v ?? "");
export const number = (v: unknown) => Number(v);
export const instant = (v: unknown) => new Date(String(v)).toISOString();
export const initialDate = (seconds: number) => {
  const d = new Date(Date.now() + seconds * 1000);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
};

export const localDateTime = (value: string) => {
  const d = new Date(value);
  return new Date(d.getTime() - d.getTimezoneOffset() * 60000)
    .toISOString()
    .slice(0, 16);
};

/** 展示已登记的恢复类型；仍提交服务端原代码，不能改写任务语义。 */
export function workTypeLabel(value: string) {
  return (
    (
      {
        event: "业务事件处理",
        "order.expiry": "订单到期关闭",
        "member.points.expiry": "积分到期处理",
        "member.cycle.assessment": "会员周期考核",
      } as Record<string, string>
    )[value] ?? value
  );
}
