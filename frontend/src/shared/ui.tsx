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
import { useId, useState, type ReactNode } from "react";
import { ApiError, useCommand } from "./api";
export const time = (v: unknown) =>
  v ? new Date(String(v)).toLocaleString("zh-CN", { hour12: false }) : "—";
export const money = (v: unknown) => (v == null ? "—" : `¥${String(v)}`);
const labels: Record<string, string> = {
  ACTIVE: "可用",
  SCHEDULED: "待执行",
  CONFLICT: "版本冲突",
  REVOCATION_DONE: "撤销处理完成",
  RETIRED: "已停用",
  IDLE: "等待下一轮",
  REVOKING: "撤销处理中",
  FROZEN: "已停用",
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
export function Status({ value }: { value?: string }) {
  const v = value ?? "UNKNOWN";
  const color = [
    "PAID",
    "COMPLETED",
    "SUCCEEDED",
    "AVAILABLE",
    "APPROVED",
    "PUBLISHED",
    "ACTIVE",
    "DELIVERED",
  ].includes(v)
    ? palette.ok
    : ["ISOLATED", "COMPENSATION_REQUIRED", "REJECTED", "TIMED_OUT"].includes(v)
      ? palette.error
      : ["UNKNOWN", "CLOSING", "WAIT_RETURN", "EXPIRED"].includes(v)
        ? palette.warn
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
          ? palette.pending
          : palette.idle;
  return (
    <Tag
      color={color}
      style={{ background: color, color: "#fff", borderColor: color }}
    >
      {labels[v] ?? v}
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
        <Typography.Title level={2}>{title}</Typography.Title>
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
  if (value == null || value === "") return "—";
  if (key === "status") return <Status value={string(value)} />;
  if (moneyKeys.has(key) && (typeof value === "string" || typeof value === "number"))
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
export function RecordFields({ value }: { value: unknown }) {
  if (value == null) return <Blank />;
  if (Array.isArray(value)) {
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
  return (
    <>
      {scalars.length > 0 && (
        <dl className="record-fields">
          {scalars.map(([key, item]) => (
            <div className="record-field" key={key}>
              <dt>{fieldLabel(key)}</dt>
              <dd>{item}</dd>
            </div>
          ))}
        </dl>
      )}
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
}: {
  title: ReactNode;
  subtitle?: ReactNode;
}) {
  return (
    <div className="primary-cell">
      <strong>{title}</strong>
      {subtitle != null && subtitle !== "" && (
        <span className="muted">{subtitle}</span>
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
          {id != null && id !== "" && <div className="record-hero-id">{id}</div>}
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
}: {
  after?: unknown;
  count: number;
  pageSize?: number;
  onHome: () => void;
  onNext: () => void;
  homeLabel?: string;
  nextLabel?: string;
}) {
  return (
    <div className="pager">
      <span className="list-count">本页 {count} 条</span>
      <Space>
        <Button disabled={!after} onClick={onHome}>
          {homeLabel}
        </Button>
        <Button disabled={count < pageSize} onClick={onNext}>
          {nextLabel}
        </Button>
      </Space>
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
}: {
  children: ReactNode;
  toolbar?: ReactNode;
  count?: number;
  after?: unknown;
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
      {onHome && onNext && count != null && (
        <Pager
          after={after}
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
          help={f.help}
        >
          {f.type === "number" ? (
            <InputNumber
              min={f.min ?? 0}
              max={f.max ?? 1000000}
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
  buttonType = "primary",
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
  const [innerOpen, setInnerOpen] = useState(false);
  const [form] = Form.useForm();
  const command = useCommand();
  const open = openProp ?? innerOpen;
  const setOpen = (next: boolean) => {
    onOpenChange?.(next);
    if (openProp === undefined) setInnerOpen(next);
  };
  return (
    <>
      {!hideButton && (
        <Button
          type={buttonType}
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
        onCancel={() => {
          if (!command.busy) setOpen(false);
        }}
        footer={null}
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
          <Button type="primary" htmlType="submit" loading={command.busy} block>
            确认提交
          </Button>
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
}: {
  path: string;
  body?: unknown;
  label: string;
  onDone: () => void;
  danger?: boolean;
  disabled?: boolean;
}) {
  const c = useCommand();
  return (
    <span>
      <Button
        size="small"
        danger={danger}
        disabled={disabled}
        loading={c.busy}
        onClick={async () => {
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
