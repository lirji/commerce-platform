import {
  Alert,
  Button,
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
import { useState, type ReactNode } from "react";
import { ApiError, useCommand } from "./api";
export const time = (v: unknown) =>
  v ? new Date(String(v)).toLocaleString("zh-CN", { hour12: false }) : "—";
export const money = (v: unknown) => (v == null ? "—" : `¥${String(v)}`);
const labels: Record<string, string> = {
  ACTIVE: "可用",
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
    ? "#047857"
    : ["ISOLATED", "COMPENSATION_REQUIRED", "REJECTED", "TIMED_OUT"].includes(v)
      ? "#B91C1C"
      : ["UNKNOWN", "CLOSING", "WAIT_RETURN", "EXPIRED"].includes(v)
        ? "#B45309"
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
          ? "#4338CA"
          : "#374151";
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
export function PageHead({
  title,
  description,
  extra,
}: {
  title: string;
  description: string;
  extra?: ReactNode;
}) {
  return (
    <div className="page-head">
      <div>
        <Typography.Title level={2}>{title}</Typography.Title>
        <Typography.Text type="secondary">{description}</Typography.Text>
      </div>
      <Space wrap>{extra}</Space>
    </div>
  );
}
export function Blank({ text = "暂无记录" }: { text?: string }) {
  return <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description={text} />;
}
export function Detail({ value }: { value: unknown }) {
  return <pre className="json-detail">{JSON.stringify(value, null, 2)}</pre>;
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
}) {
  const [open, setOpen] = useState(false);
  const [form] = Form.useForm();
  const command = useCommand();
  return (
    <>
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
      <Modal
        title={title}
        open={open}
        onCancel={() => {
          if (!command.busy) setOpen(false);
        }}
        footer={null}
        destroyOnHidden
      >
        <ErrorNotice error={command.error} />
        <Form
          form={form}
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
