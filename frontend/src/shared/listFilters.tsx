import { Button, Form, Input, Select, Space, Tag, Typography } from "antd";
import { useEffect } from "react";
import { useRouteState } from "./routeState";
import { statusText, localDateTime, time } from "./ui";

type FilterSpec = {
  hint: string;
  states?: string[];
  time?: boolean;
  enabled?: boolean;
};
const specs: Record<string, FilterSpec> = {
  "/admin/members": {
    hint: "会员编号、账号或姓名",
    states: ["ACTIVE", "FROZEN", "CLOSED"],
  },
  "/admin/merchants": { hint: "商家编号或名称", states: ["ACTIVE", "FROZEN"] },
  "/admin/stores": {
    hint: "门店编号、商家编号或名称",
    states: ["ACTIVE", "FROZEN"],
  },
  "/admin/skus": { hint: "规格编号、商品编号或名称" },
  "/catalog": { hint: "规格编号、商品编号或名称" },
  "/operations/products": { hint: "商品编号、名称、分类或品牌" },
  "/operations/skus": {
    hint: "规格编号、商品编号或名称",
    states: ["ACTIVE", "FROZEN"],
  },
  "/admin/orders": {
    hint: "订单、会员、门店或商家编号",
    states: [
      "PENDING_PAYMENT",
      "PAYMENT_IN_PROGRESS",
      "CLOSING",
      "PAID",
      "FULFILLING",
      "COMPLETED",
      "CANCELLED",
    ],
    time: true,
  },
  "/orders": {
    hint: "订单或门店编号",
    states: [
      "PENDING_PAYMENT",
      "PAYMENT_IN_PROGRESS",
      "CLOSING",
      "PAID",
      "FULFILLING",
      "COMPLETED",
      "CANCELLED",
    ],
    time: true,
  },
  "/admin/fulfillments": {
    hint: "订单编号、运单或仓配渠道",
    states: ["READY", "SHIPPED", "DELIVERED", "CANCELLED"],
  },
  "/admin/aftersales": {
    hint: "售后、订单、会员或退款编号",
    states: ["REQUESTED", "WAIT_RETURN", "REFUNDING", "COMPLETED", "REJECTED"],
  },
  "/aftersales": {
    hint: "售后、订单或会员编号",
    states: ["REQUESTED", "WAIT_RETURN", "REFUNDING", "COMPLETED", "REJECTED"],
  },
  "/admin/refunds": {
    hint: "退款、售后、订单编号或渠道",
    states: ["UNKNOWN", "SUCCEEDED"],
  },

  "/admin/campaigns": {
    hint: "活动编号、名称或门店编号",
    states: [
      "DRAFT",
      "IN_REVIEW",
      "APPROVED",
      "REJECTED",
      "PUBLISHED",
      "PAUSED",
    ],
  },
  "/admin/rules": { hint: "规则编号或名称", states: ["DRAFT", "PUBLISHED"] },
  "/admin/audiences": { hint: "人群编号、名称或来源" },
  "/admin/segments": { hint: "人群定义编号或名称", enabled: true },
  "/admin/journeys": {
    hint: "旅程编号、名称或门店编号",
    states: [
      "DRAFT",
      "IN_REVIEW",
      "APPROVED",
      "REJECTED",
      "PUBLISHED",
      "PAUSED",
    ],
  },
  "/admin/journey-instances": {
    hint: "实例、旅程或会员编号",
    states: [
      "RUNNING",
      "WAITING",
      "COMPLETED",
      "CANCELLED",
      "ISOLATED",
      "TIMED_OUT",
    ],
  },
  "/admin/journey-scans": {
    hint: "旅程编号",
    states: ["RUNNING", "IDLE", "ISOLATED"],
  },
  "/admin/coupon-deliveries": {
    hint: "发券批次编号或名称",
    states: [
      "RUNNING",
      "COMPLETED",
      "CANCELLED",
      "EXPIRED",
      "REVOKING",
      "REVOCATION_DONE",
      "ISOLATED",
    ],
  },
  "/admin/ops-pages": {
    hint: "页面编号、标题或门店编号",
    states: [
      "DRAFT",
      "IN_REVIEW",
      "APPROVED",
      "REJECTED",
      "PUBLISHED",
      "PAUSED",
    ],
  },
  "/admin/coupon-definitions": { hint: "优惠券定义编号或名称" },
  "/coupon-definitions": { hint: "优惠券定义编号或名称" },
  "/admin/entitlement-definitions": { hint: "权益定义编号或名称" },
  "/admin/point-offers": {
    hint: "兑换项目编号或名称",
    states: ["ACTIVE", "INACTIVE"],
  },
  "/point-offers": { hint: "兑换项目编号或名称" },
  "/operations/catalog-jobs": {
    hint: "批量计划编号或名称",
    states: ["SCHEDULED", "RUNNING", "COMPLETED", "CANCELLED", "ISOLATED"],
  },

  "/admin/entitlements": {
    hint: "权益、订单、会员编号或名称",
    states: [
      "RESERVED",
      "REQUESTED",
      "AVAILABLE",
      "CONSUMED",
      "CANCELLED",
      "REVOKED",
      "COMPENSATION_REQUIRED",
      "COMPENSATED",
    ],
  },
  "/entitlements": {
    hint: "权益编号、名称或订单编号",
    states: [
      "RESERVED",
      "REQUESTED",
      "AVAILABLE",
      "CONSUMED",
      "CANCELLED",
      "REVOKED",
      "COMPENSATION_REQUIRED",
      "COMPENSATED",
    ],
  },
  "/coupons": {
    hint: "优惠券编号、定义编号或名称",
    states: ["AVAILABLE", "HELD", "USED", "EXPIRED", "REVOKED"],
  },
  "/admin/inventory": { hint: "商品规格编号" },

  "/admin/member-tags": { hint: "标签编号或名称" },
  "/admin/campaign-budgets": { hint: "预算或活动编号" },
  "/admin/store-grants": { hint: "授权、人员或资源编号", enabled: true },
  "/admin/events": {
    hint: "事件编号、类型或业务编号",
    states: ["PENDING", "DELIVERED", "ISOLATED", "SKIPPED"],
  },
};
type Values = {
  q?: string;
  status?: string;
  from?: string;
  to?: string;
  enabled?: boolean;
  limit?: number;
};
const readText = (value: unknown) =>
  typeof value === "string" ? value : undefined;
function decode(raw: string, spec?: FilterSpec): Values {
  try {
    const v: unknown = JSON.parse(raw);
    if (!v || typeof v !== "object") return {};
    const r = v as Record<string, unknown>;
    const q = readText(r.q),
      code = readText(r.status);
    return {
      q: q?.slice(0, 64),
      status: code && spec?.states?.includes(code) ? code : undefined,
      from:
        spec?.time &&
        typeof r.from === "string" &&
        Number.isFinite(Date.parse(r.from))
          ? r.from
          : undefined,
      to:
        spec?.time &&
        typeof r.to === "string" &&
        Number.isFinite(Date.parse(r.to))
          ? r.to
          : undefined,
      enabled:
        spec?.enabled && typeof r.enabled === "boolean" ? r.enabled : undefined,
      limit: [10, 25, 50, 100].includes(Number(r.limit)) ? Number(r.limit) : 50,
    };
  } catch {
    return {};
  }
}

/** 条件明确绑定到 Owner 接口，不对当前页数据做假筛选。 */
export function useListFilters(name: string, path: string, reset: () => void) {
  const spec = specs[path];
  const [raw, setRaw] = useRouteState(`${name}.query`, "");
  const values = decode(raw, spec);
  const limit = values.limit ?? 50;
  const query = new URLSearchParams({ limit: String(limit) });
  if (spec)
    for (const key of ["q", "status", "from", "to", "enabled"] as const) {
      const value = values[key];
      if (value !== undefined && value !== "")
        query.set(
          key,
          key === "from" || key === "to"
            ? new Date(String(value)).toISOString()
            : String(value),
        );
    }
  const apply = (next: Values) => {
    const canonical = {
      ...next,
      from: next.from ? new Date(next.from).toISOString() : undefined,
      to: next.to ? new Date(next.to).toISOString() : undefined,
    };
    reset();
    setRaw(
      Object.values(canonical).some((v) => v != null && v !== "" && v !== 50)
        ? JSON.stringify(canonical)
        : "",
    );
  };
  return {
    active: !!(
      values.q ||
      values.status ||
      values.from ||
      values.to ||
      values.enabled !== undefined
    ),
    limit,
    query: query.toString(),
    toolbar: spec ? (
      <ListFilters spec={spec} values={values} raw={raw} apply={apply} />
    ) : null,
  };
}
function ListFilters({
  spec,
  values,
  raw,
  apply,
}: {
  spec: FilterSpec;
  values: Values;
  raw: string;
  apply: (v: Values) => void;
}) {
  type FormValues = Omit<Values, "enabled"> & { enabled?: string };
  const [form] = Form.useForm<FormValues>();
  const formValues = {
    ...values,
    from: values.from ? localDateTime(values.from) : undefined,
    to: values.to ? localDateTime(values.to) : undefined,
    enabled: values.enabled === undefined ? undefined : String(values.enabled),
    limit: values.limit ?? 50,
  };
  useEffect(() => {
    form.resetFields();
    form.setFieldsValue(formValues);
  }, [raw, form]);
  const active = [
    values.q && `关键词：${values.q}`,
    values.status && `状态：${statusText(values.status)}`,
    values.from && `创建起点：${time(values.from)}`,
    values.to && `创建终点：${time(values.to)}`,
    values.enabled !== undefined &&
      `启用状态：${values.enabled ? "已启用" : "已停用"}`,
  ].filter(Boolean);
  return (
    <div className="query-panel">
      <Form
        form={form}
        layout="inline"
        onFinish={(v) =>
          apply({
            ...v,
            q: v.q?.trim(),
            enabled: v.enabled === undefined ? undefined : v.enabled === "true",
          })
        }
        initialValues={formValues}
        className="query-form"
      >
        <Form.Item name="q" label="关键词" className="query-wide">
          <Input allowClear maxLength={64} placeholder={spec.hint} />
        </Form.Item>
        {spec.states && (
          <Form.Item name="status" label="状态">
            <Select
              allowClear
              placeholder="全部状态"
              options={spec.states.map((value) => ({
                value,
                label: statusText(value),
              }))}
            />
          </Form.Item>
        )}
        {spec.enabled && (
          <Form.Item name="enabled" label="启用状态">
            <Select
              allowClear
              placeholder="全部"
              options={[
                { value: "true", label: "已启用" },
                { value: "false", label: "已停用" },
              ]}
            />
          </Form.Item>
        )}
        {spec.time && (
          <>
            <Form.Item name="from" label="创建起点" className="query-wide">
              <Input type="datetime-local" />
            </Form.Item>
            <Form.Item
              name="to"
              label="创建终点"
              className="query-wide"
              dependencies={["from"]}
              rules={[
                ({ getFieldValue }) => ({
                  validator: (_, v) =>
                    !v ||
                    !getFieldValue("from") ||
                    Date.parse(getFieldValue("from")) < Date.parse(v)
                      ? Promise.resolve()
                      : Promise.reject(new Error("结束时间须晚于开始时间")),
                }),
              ]}
            >
              <Input type="datetime-local" />
            </Form.Item>
          </>
        )}
        <Form.Item name="limit" label="每页">
          <Select
            options={[10, 25, 50, 100].map((value) => ({
              value,
              label: `${value}条`,
            }))}
          />
        </Form.Item>
        <Form.Item className="query-wide">
          <Space wrap>
            <Button type="primary" htmlType="submit">
              查询
            </Button>
            <Button onClick={() => apply({})}>重置筛选</Button>
          </Space>
        </Form.Item>
      </Form>
      <div className="query-summary">
        <Typography.Text type="secondary">
          {active.length ? "已应用" : "全部符合当前范围的记录"}
        </Typography.Text>
        {active.map((v) => (
          <Tag key={String(v)}>{v}</Tag>
        ))}
      </div>
    </div>
  );
}

/** 专项客户端仍执行原路径允许列表，仅归一化已批准列表的合法查询条件。 */
export function listGuardPath(path: string): string {
  if (!path.startsWith("/") || path.startsWith("//") || path.includes("#"))
    return "/unsupported-list-query";
  const url = new URL(path, "https://list.invalid");
  const spec = specs[url.pathname];
  if (!spec) return path;
  const q = url.searchParams;
  for (const key of ["q", "status", "from", "to", "enabled"]) {
    if (!q.has(key)) continue;
    const value = q.get(key) ?? "";
    if (
      q.getAll(key).length !== 1 ||
      (key === "enabled" &&
        (!spec.enabled || !["true", "false"].includes(value))) ||
      (key === "q" && value.length > 64) ||
      (key === "status" && !spec.states?.includes(value)) ||
      (["from", "to"].includes(key) &&
        (!spec.time || !Number.isFinite(Date.parse(value))))
    )
      return "/unsupported-list-query";
    q.delete(key);
  }
  if (
    ["10", "25", "50", "100"].includes(q.get("limit") ?? "") &&
    q.getAll("limit").length === 1
  )
    q.set("limit", "50");
  return `${url.pathname}${q.size ? "?" + q : ""}${url.hash}`;
}
