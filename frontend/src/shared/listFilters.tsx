import { DateTimeField, dateRangePresets } from "./DateTimeField";
import { Button, Form, Input, Select, Space, Tag, Typography } from "antd";
import { useContext, useEffect, useId, useState } from "react";
import { BusinessTableScope } from "./businessTableScope";
import { useRouteState } from "./routeState";
import { formatField, statusText, localDateTime, time } from "./ui";

const tradeIds = ["storeId", "orderId"] as const;
const exactIds = [
  ...tradeIds,
  "merchantId",
  "campaignId",
  "aggregateId",
] as const;
const eventFailureClasses = [
  "TRANSIENT",
  "CONCURRENCY_RETRYABLE",
  "DEPENDENCY_UNAVAILABLE",
  "BUSINESS_REJECTED",
  "DATA_CORRUPTION",
  "CONFIGURATION_ERROR",
  "PERMANENT",
  "UNKNOWN",
];
const identifier = /^[A-Za-z0-9_.:-]{1,64}$/;
const storeScopedLists = new Set([
  "/admin/coupon-definitions",
  "/coupon-definitions",
  "/admin/coupon-deliveries",
  "/admin/entitlement-definitions",
  "/admin/point-offers",
  "/point-offers",
  "/admin/inventory",
]);
const exactLabels = {
  storeId: "店铺编号",
  orderId: "精确订单编号",
  merchantId: "商家编号",
  campaignId: "活动编号",
  aggregateId: "精确业务编号",
};

type QueueShortcut = {
  label: string;
  status?: string;
  blocked?: boolean;
};

type FilterSpec = {
  hint: string;
  failureClass?: boolean;
  states?: string[];
  quickStates?: string[];
  queueShortcuts?: QueueShortcut[];
  time?: boolean;
  enabled?: boolean;
  exact?: readonly (typeof exactIds)[number][];
  blocked?: boolean;
};
const specs: Record<string, FilterSpec> = {
  "/admin/members": {
    hint: "会员编号、账号或姓名",
    states: ["ACTIVE", "FROZEN", "CLOSED"],
  },
  "/admin/merchants": { hint: "商家编号或名称", states: ["ACTIVE", "FROZEN"] },
  "/admin/stores": {
    quickStates: ["PENDING_OPEN", "ACTIVE", "FROZEN"],
    hint: "门店编号、商家编号或名称",
    states: ["PENDING_OPEN", "ACTIVE", "FROZEN"],
    exact: ["merchantId"],
  },
  "/admin/skus": { hint: "规格编号、商品编号或名称" },
  "/catalog": { hint: "规格编号、商品编号或名称" },
  "/operations/products": { hint: "商品编号、名称、分类或品牌" },
  "/operations/skus": {
    hint: "规格编号、商品编号或名称",
    states: ["ACTIVE", "FROZEN"],
  },
  "/admin/orders": {
    quickStates: ["PENDING_PAYMENT", "PAYMENT_IN_PROGRESS", "CLOSING"],
    exact: tradeIds,
    hint: "完整订单号、原编号、日期加尾号或会员、门店、商家编号",
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
    exact: ["storeId"],
    hint: "完整订单号、原编号、日期加尾号或门店编号",
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
    exact: tradeIds,
    blocked: true,
    hint: "订单编号、运单或仓配渠道",
    states: ["READY", "SHIPPED", "DELIVERED", "CANCELLED"],
    queueShortcuts: [
      { label: "待发货 · 未阻拦", status: "READY", blocked: false },
      { label: "售后已阻拦", blocked: true },
      { label: "已发货", status: "SHIPPED" },
    ],
  },
  "/admin/aftersales": {
    quickStates: ["REQUESTED", "WAIT_RETURN", "REFUNDING"],
    exact: tradeIds,
    hint: "售后、订单、会员或退款编号",
    states: ["REQUESTED", "WAIT_RETURN", "REFUNDING", "COMPLETED", "REJECTED"],
  },
  "/aftersales": {
    exact: ["orderId"],
    hint: "售后、订单或会员编号",
    states: ["REQUESTED", "WAIT_RETURN", "REFUNDING", "COMPLETED", "REJECTED"],
  },
  "/admin/refunds": {
    quickStates: ["UNKNOWN", "SUCCEEDED"],
    exact: tradeIds,
    hint: "退款、售后、订单编号或渠道",
    states: ["UNKNOWN", "SUCCEEDED"],
  },

  "/admin/campaigns": {
    quickStates: ["IN_REVIEW", "PUBLISHED", "PAUSED"],
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
  "/admin/marketing-executions": {
    hint: "订单编号或活动编号",
    exact: ["orderId"],
    failureClass: true,
  },
  "/admin/journey-scans": {
    hint: "旅程编号",
    states: ["RUNNING", "IDLE", "ISOLATED"],
  },
  "/admin/coupon-deliveries": {
    quickStates: ["RUNNING", "ISOLATED", "REVOKING"],
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
    quickStates: ["AVAILABLE", "COMPENSATION_REQUIRED", "REVOKED"],
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
  "/admin/campaign-budgets": { hint: "预算或活动编号", exact: ["campaignId"] },
  "/admin/store-grants": { hint: "授权、人员或资源编号", enabled: true },
  "/admin/events": {
    hint: "事件编号、类型或业务编号",
    exact: ["aggregateId"],
    states: ["PENDING", "DELIVERED", "ISOLATED", "SKIPPED"],
  },
};
type Values = Partial<Record<(typeof exactIds)[number], string>> & {
  failureClass?: string;
  q?: string;
  status?: string;
  from?: string;
  to?: string;
  enabled?: boolean;
  blocked?: boolean;
  limit?: number;
};
export type QueuePreset = Pick<Values, "status" | "blocked" | "limit">;
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
      ...Object.fromEntries(
        (spec?.exact ?? []).flatMap((key) => {
          const value = readText(r[key]);
          return value && identifier.test(value) ? [[key, value]] : [];
        }),
      ),
      failureClass:
        spec?.failureClass &&
        typeof r.failureClass === "string" &&
        eventFailureClasses.includes(r.failureClass)
          ? r.failureClass
          : undefined,
      blocked:
        spec?.blocked && typeof r.blocked === "boolean" ? r.blocked : undefined,
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
export function useListFilters(
  name: string,
  path: string,
  reset: () => void,
  compact = false,
) {
  const spec = specs[path];
  const [raw, setRaw] = useRouteState(`${name}.query`, "");
  const values = decode(raw, spec);
  const limit = values.limit ?? 50;
  const query = new URLSearchParams({ limit: String(limit) });
  if (spec)
    for (const key of [
      "q",
      "status",
      "from",
      "to",
      "enabled",
      ...exactIds,
      "blocked",
      "failureClass",
    ] as const) {
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
    failureClass: values.failureClass,
    preset: {
      status: values.status,
      blocked: values.blocked,
      limit,
    } satisfies QueuePreset,
    applyPreset: (preset: QueuePreset) =>
      apply({
        ...values,
        status:
          preset.status && spec?.states?.includes(preset.status)
            ? preset.status
            : undefined,
        blocked: spec?.blocked ? preset.blocked : undefined,
        limit: [10, 25, 50, 100].includes(preset.limit ?? 50)
          ? preset.limit
          : 50,
      }),
    active: !!(
      values.q ||
      values.status ||
      values.from ||
      values.to ||
      values.enabled !== undefined ||
      exactIds.some((key) => values[key]) ||
      values.blocked !== undefined ||
      values.failureClass
    ),
    limit,
    query: query.toString(),
    toolbar: spec ? (
      <ListFilters
        spec={spec}
        values={values}
        raw={raw}
        apply={apply}
        compact={compact}
      />
    ) : null,
  };
}
function ListFilters({
  spec,
  values,
  raw,
  apply,
  compact,
}: {
  spec: FilterSpec;
  values: Values;
  raw: string;
  apply: (v: Values) => void;
  compact: boolean;
}) {
  type FormValues = Omit<Values, "enabled" | "blocked"> & {
    enabled?: string;
    blocked?: string;
  };
  const [form] = Form.useForm<FormValues>();
  const [advanced, setAdvanced] = useState(false);
  const inWorkspace = useContext(BusinessTableScope) !== undefined;
  const secondaryId = useId();
  const hasSecondary =
    (inWorkspace || compact) && (spec.time || Boolean(spec.exact?.length));
  const hideSecondary = hasSecondary && !advanced;
  const draft = Form.useWatch<FormValues>([], form);
  const formValues = {
    ...values,
    from: values.from ? localDateTime(values.from) : undefined,
    to: values.to ? localDateTime(values.to) : undefined,
    enabled: values.enabled === undefined ? undefined : String(values.enabled),
    blocked: values.blocked === undefined ? undefined : String(values.blocked),
    limit: values.limit ?? 50,
  };
  useEffect(() => {
    form.resetFields();
    form.setFieldsValue(formValues);
  }, [raw, form]);
  const applyValues = (next: Values) => {
    apply(next);
    // 相同查询或已在默认条件下点重置，URL不会变化，仍须同步清掉未提交草稿。
    form.setFieldsValue({
      q: next.q,
      status: next.status,
      failureClass: next.failureClass,
      ...Object.fromEntries(exactIds.map((key) => [key, next[key]])),
      blocked: next.blocked === undefined ? undefined : String(next.blocked),
      from: next.from ? localDateTime(next.from) : undefined,
      to: next.to ? localDateTime(next.to) : undefined,
      enabled: next.enabled === undefined ? undefined : String(next.enabled),
      limit: next.limit ?? 50,
    });
  };
  const active = [
    { key: "q", label: "关键词", text: values.q },
    {
      key: "failureClass",
      label: "最新事件失败分类",
      text:
        values.failureClass && formatField("failureClass", values.failureClass),
    },
    ...exactIds.map((key) => ({
      key,
      label: exactLabels[key],
      text: values[key],
    })),
    {
      key: "blocked",
      label: "售后阻拦",
      text:
        values.blocked === undefined
          ? undefined
          : values.blocked
            ? "已阻拦"
            : "未阻拦",
    },
    {
      key: "status",
      label: "状态",
      text: values.status && statusText(values.status),
    },
    { key: "from", label: "创建起点", text: values.from && time(values.from) },
    { key: "to", label: "创建终点", text: values.to && time(values.to) },
    {
      key: "enabled",
      label: "启用状态",
      text:
        values.enabled === undefined
          ? undefined
          : values.enabled
            ? "已启用"
            : "已停用",
    },
  ].filter((item) => item.text);
  // 摘要始终描述已应用条件，输入草稿不应让用户误以为列表已重新查询。
  const pending =
    !!draft &&
    (
      [
        "q",
        "status",
        "from",
        "to",
        "enabled",
        "limit",
        ...exactIds,
        "blocked",
        "failureClass",
      ] as const
    ).some((key) => {
      const normalize = (v: unknown) =>
        key === "q"
          ? String(v ?? "").trim()
          : key === "limit"
            ? (v ?? 50)
            : (v ?? "");
      return normalize(draft[key]) !== normalize(formValues[key]);
    });
  const additionalFields = (
    <>
      {spec.exact?.map((key) => (
        <Form.Item
          key={key}
          name={key}
          label={exactLabels[key]}
          rules={[{ pattern: identifier, message: "请输入有效的业务编号" }]}
        >
          <Input
            allowClear
            maxLength={64}
            placeholder={
              key === "orderId"
                ? "按完整订单编号查询"
                : `按完整${exactLabels[key]}查询`
            }
          />
        </Form.Item>
      ))}
      {spec.time && (
        <>
          <Form.Item name="from" label="创建起点" className="query-wide">
            <DateTimeField />
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
            <DateTimeField />
          </Form.Item>
        </>
      )}
      {spec.time && (
        <Form.Item label="快捷时间" className="query-date-presets">
          <Select
            aria-label="快捷时间"
            virtual={false}
            placeholder="选择时间范围"
            value={null}
            options={dateRangePresets.map(({ label }) => ({
              label,
              value: label,
            }))}
            onChange={(label) => {
              const preset = dateRangePresets.find(
                (item) => item.label === label,
              );
              if (preset) form.setFieldsValue(preset.range());
            }}
          />
        </Form.Item>
      )}
      <Form.Item name="limit" label="每页">
        <Select
          virtual={false}
          options={[10, 25, 50, 100].map((value) => ({
            value,
            label: `${value}条`,
          }))}
        />
      </Form.Item>
    </>
  );
  const shortcuts: QueueShortcut[] | undefined =
    spec.queueShortcuts ??
    spec.quickStates?.map((status) => ({ label: statusText(status), status }));
  return (
    <div className="query-panel">
      {shortcuts && (
        <div
          className="query-quick-states"
          role="group"
          aria-label="状态快捷筛选"
        >
          {[{ label: "全部状态" }, ...shortcuts].map(
            ({ label, status, blocked }) => {
              const selected =
                values.status === status && values.blocked === blocked;
              return (
                <Button
                  key={label}
                  aria-pressed={selected}
                  type={selected ? "default" : "text"}
                  onClick={() => applyValues({ ...values, status, blocked })}
                >
                  {label}
                </Button>
              );
            },
          )}
        </div>
      )}
      <Form
        form={form}
        layout="inline"
        onFinish={(v) =>
          applyValues({
            ...v,
            q: v.q?.trim(),
            blocked: v.blocked === undefined ? undefined : v.blocked === "true",
            enabled: v.enabled === undefined ? undefined : v.enabled === "true",
          })
        }
        onFinishFailed={() => hideSecondary && setAdvanced(true)}
        initialValues={formValues}
        className="query-form"
      >
        <div className="query-fields">
          <Form.Item name="q" label="关键词" className="query-wide">
            <Input
              allowClear
              maxLength={64}
              placeholder={spec.hint}
              title={spec.hint}
            />
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
          {spec.failureClass && (
            <Form.Item name="failureClass" label="最新事件失败分类">
              <Select
                allowClear
                placeholder="全部分类"
                options={eventFailureClasses.map((value) => ({
                  value,
                  label: formatField("failureClass", value),
                }))}
              />
            </Form.Item>
          )}
          {spec.blocked && (
            <Form.Item name="blocked" label="售后阻拦">
              <Select
                allowClear
                placeholder="全部"
                options={[
                  { value: "true", label: "已阻拦" },
                  { value: "false", label: "未阻拦" },
                ]}
              />
            </Form.Item>
          )}
          {!hasSecondary && additionalFields}
          <Form.Item className="query-wide query-actions">
            <Space wrap>
              <Button type="primary" htmlType="submit">
                查询
              </Button>
              <Button onClick={() => applyValues({})}>重置筛选</Button>
              {hasSecondary && (
                <Button
                  aria-expanded={advanced}
                  aria-controls={secondaryId}
                  onClick={() => setAdvanced(!advanced)}
                >
                  {advanced ? "收起高级筛选" : "高级筛选"}
                </Button>
              )}
            </Space>
          </Form.Item>
        </div>
        {hasSecondary && (
          <div
            id={secondaryId}
            className="query-fields query-secondary"
            hidden={hideSecondary}
          >
            {additionalFields}
          </div>
        )}
      </Form>
      {!inWorkspace && <div className="query-scope">{spec.hint}</div>}
      <div className="query-pending" role="status" aria-live="polite">
        {pending && "筛选已修改，点击查询后生效"}
      </div>
      {(!inWorkspace || active.length > 0) && (
        <div className="query-summary">
          <Typography.Text type="secondary">
            {active.length ? "已应用" : "当前范围内的全部记录"}
          </Typography.Text>
          {active.map((item) => (
            <Tag
              key={item.key}
              closable
              closeIcon={
                <button
                  type="button"
                  className="filter-clear"
                  aria-label={`清除${item.label}筛选`}
                >
                  ×
                </button>
              }
              onClose={(event) => {
                event.preventDefault();
                applyValues({ ...values, [item.key]: undefined });
              }}
            >
              {item.label}：{item.text}
            </Tag>
          ))}
        </div>
      )}
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
  for (const key of [
    "q",
    "status",
    "from",
    "to",
    "enabled",
    ...exactIds,
    "blocked",
    "failureClass",
  ] as const) {
    if (!q.has(key)) continue;
    const value = q.get(key) ?? "";
    if (key === "storeId" && storeScopedLists.has(url.pathname)) {
      if (q.getAll(key).length !== 1 || !identifier.test(value))
        return "/unsupported-list-query";
      continue;
    }
    if (
      q.getAll(key).length !== 1 ||
      (key === "enabled" &&
        (!spec.enabled || !["true", "false"].includes(value))) ||
      (key === "blocked" &&
        (!spec.blocked || !["true", "false"].includes(value))) ||
      (key === "failureClass" &&
        (!spec.failureClass || !eventFailureClasses.includes(value))) ||
      (exactIds.some((id) => key === id) &&
        (!spec.exact?.some((id) => key === id) || !identifier.test(value))) ||
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
