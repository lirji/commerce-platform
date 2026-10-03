import { listGuardPath } from "../shared/listFilters";
import { ApiError, type request } from "../shared/api";
import { CentralError, HTTP, type Context } from "./api";

export const operationFamilies = [
  "orders",
  "payments",
  "fulfillments",
  "aftersales",
  "refunds",
  "ops-pages",
  "events",
  "runtime",
  "dashboard",
] as const;
export type OperationFamily = (typeof operationFamilies)[number];
export const identifier = /^[A-Za-z0-9_.:-]{1,64}$/;
const target = "[A-Za-z0-9_.:-]{1,64}";
const actions: Record<OperationFamily, readonly string[]> = {
  orders: ["read", "expire", "expiry-retry"],
  payments: ["read", "reconcile"],
  fulfillments: ["read", "ship", "deliver"],
  aftersales: ["read", "approve", "reject", "receive-return"],
  refunds: ["read", "reconcile"],
  "ops-pages": [
    "read",
    "create",
    "preview",
    "submit",
    "approve",
    "reject",
    "publish",
    "pause",
    "rollback",
    "execute",
  ],
  events: ["read", "pump", "retry"],
  runtime: [
    "read",
    "recover",
    "replay-preview",
    "replay-create",
    "replay-control",
  ],
  dashboard: ["read"],
};
const record = (v: unknown): v is Record<string, unknown> =>
  !!v && typeof v === "object" && !Array.isArray(v);
const integer = (v: unknown) =>
  typeof v === "number" && Number.isSafeInteger(v) && v >= 0;
const row = (v: unknown) =>
  record(v) &&
  ["orderId", "caseId", "refundId", "eventId", "jobId", "workId"].some(
    (k) => typeof v[k] === "string",
  ) &&
  (typeof v.status === "string" || typeof v.state === "string");
const page = (v: unknown) =>
  record(v) &&
  record(v.content) &&
  typeof v.content.pageId === "string" &&
  integer(v.content.version) &&
  (v.content.version as number) > 0 &&
  integer(v.lockVersion) &&
  [
    "DRAFT",
    "PREVIEW",
    "IN_REVIEW",
    "APPROVED",
    "REJECTED",
    "PUBLISHED",
    "PAUSED",
  ].includes(String(v.status)) &&
  typeof v.content.title === "string" &&
  identifier.test(String(v.content.storeId)) &&
  Array.isArray(v.content.sections) &&
  v.content.sections.length <= 8 &&
  Array.isArray(v.content.actions) &&
  v.content.actions.length <= 8;
const id = (v: unknown) => typeof v === "string" && identifier.test(v);
const oneOf = (v: unknown, values: readonly string[]) =>
  typeof v === "string" && values.includes(v);
const amount = (v: unknown) =>
  typeof v === "string" && /^\d{1,12}\.\d{2}$/.test(v);
const payment = (v: unknown, order: string) =>
  record(v) &&
  id(v.paymentId) &&
  v.orderId === order &&
  amount(v.amount) &&
  v.currency === "CNY" &&
  typeof v.provider === "string" &&
  oneOf(v.status, ["UNKNOWN", "OPEN", "PAID", "CLOSED"]) &&
  integer(v.version);
const fulfillment = (v: unknown, order: string, tracking?: unknown) =>
  record(v) &&
  v.orderId === order &&
  oneOf(v.status, ["READY", "SHIPPED", "DELIVERED", "CANCELLED"]) &&
  typeof v.provider === "string" &&
  typeof v.blocked === "boolean" &&
  integer(v.version) &&
  (v.trackingNo == null || typeof v.trackingNo === "string") &&
  (tracking === undefined || v.trackingNo === tracking);
const aftersale = (v: unknown, caseId: string) =>
  record(v) &&
  v.caseId === caseId &&
  id(v.orderId) &&
  id(v.memberId) &&
  oneOf(v.status, [
    "REQUESTED",
    "WAIT_RETURN",
    "REFUNDING",
    "COMPLETED",
    "REJECTED",
  ]) &&
  typeof v.returnRequired === "boolean" &&
  amount(v.refundAmount) &&
  integer(v.version) &&
  Array.isArray(v.items);
const refund = (v: unknown, refundId: string) =>
  record(v) &&
  v.refundId === refundId &&
  id(v.caseId) &&
  id(v.orderId) &&
  amount(v.amount) &&
  v.currency === "CNY" &&
  typeof v.provider === "string" &&
  oneOf(v.status, ["UNKNOWN", "SUCCEEDED"]) &&
  integer(v.version);
const replay = (v: unknown, job: unknown) =>
  record(v) &&
  v.jobId === job &&
  id(v.jobId) &&
  id(v.consumerId) &&
  typeof v.eventTypes === "string" &&
  oneOf(v.mode, ["UNPROCESSED", "REPROCESS"]) &&
  oneOf(v.status, ["RUNNING", "PAUSED", "COMPLETED", "CANCELLED", "FAILED"]) &&
  integer(v.version) &&
  integer(v.examined) &&
  integer(v.executed) &&
  integer(v.failed);
// 收据必须对应实际接口及原目标；其他业务的合法形状也不能清除当前未知意图。
/** 每个页面只发本域精确协议；浏览器输入不能指定权限代码或改用旧管理员客户端。 */
export function operationsClient(
  context: Context,
  family: OperationFamily,
  expired: () => void,
): typeof request {
  return async <T>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ): Promise<T> => {
    const guardedPath = listGuardPath(path);
    const method = options.method ?? "GET",
      url = new URL(guardedPath, "https://closed.invalid");
    const base = url.pathname,
      query = url.searchParams;
    const match = (pattern: string) => new RegExp(`^${pattern}$`).test(base);
    const hint = actions[family].some(
      (a) => path === `/operations/${family}/${a}-access`,
    );
    let get = false,
      post = false;
    const bounded =
      query.size === 2 &&
      query.has("after") &&
      query.get("limit") === "50" &&
      (query.get("after") === "" || identifier.test(query.get("after") ?? ""));
    if (family === "orders") {
      get =
        (base === "/admin/orders" && bounded) ||
        (query.size === 0 && match(`/admin/orders/${target}`));
      post =
        query.size === 0 &&
        (base === "/admin/orders/expire" ||
          match(`/admin/orders/${target}/expiry/retry`));
    }
    if (family === "payments") {
      get = query.size === 0 && match(`/admin/orders/${target}/payment`);
      post =
        query.size === 0 && match(`/admin/orders/${target}/payment/reconcile`);
    }
    if (["fulfillments", "aftersales", "refunds", "events"].includes(family)) {
      get =
        (base === `/admin/${family}` && bounded) ||
        (family === "events" && path === "/admin/events/health");
      post =
        query.size === 0 &&
        ((family === "fulfillments" &&
          match(`/admin/fulfillments/${target}/(ship|deliver)`)) ||
          (family === "aftersales" &&
            match(
              `/admin/aftersales/${target}/(approve|reject|receive-return)`,
            )) ||
          (family === "refunds" &&
            match(`/admin/refunds/${target}/reconcile`)) ||
          (family === "events" &&
            (path === "/admin/events/pump" ||
              match(`/admin/events/${target}/retry`))));
    }
    if (family === "ops-pages") {
      get =
        (base === "/admin/ops-pages" && bounded) ||
        (query.size === 0 &&
          match(`/admin/ops-pages/${target}/(versions|render)`));
      post =
        query.size === 0 &&
        (base === "/admin/ops-pages" ||
          base === "/admin/ops-pages/preview" ||
          match(
            `/admin/ops-pages/${target}/[1-9][0-9]*/(submit|approve|reject|publish|pause|rollback)`,
          ) ||
          match(`/admin/ops-pages/${target}/[1-9][0-9]*/actions/${target}`));
    }
    if (family === "runtime") {
      get =
        (base === "/admin/runtime/replays" && bounded) ||
        (query.size === 0 &&
          (base === "/admin/runtime/work-types" ||
            base === "/admin/runtime/replay/classifications" ||
            match(`/admin/runtime/replays/${target}`))) ||
        (base === "/admin/runtime/stopped" &&
          query.size >= 3 &&
          query.size <= 4 &&
          query.get("limit") === "50" &&
          identifier.test(query.get("workType") ?? "") &&
          query.has("after") &&
          Array.from(query.keys()).every((k) =>
            ["workType", "after", "limit", "failureClass"].includes(k),
          )) ||
        (base === "/admin/runtime/recoveries" &&
          query.get("limit") === "50" &&
          /^[0-9]+$/.test(query.get("after") ?? "") &&
          Array.from(query.keys()).every((k) =>
            ["workType", "workId", "after", "limit"].includes(k),
          ));
      post =
        query.size === 0 &&
        ([
          "/admin/runtime/recoveries",
          "/admin/runtime/replay/dry-run",
          "/admin/runtime/replays",
        ].includes(base) ||
          match(`/admin/runtime/replays/${target}/control`));
    }
    if (family === "dashboard")
      get =
        base === "/admin/dashboard" &&
        query.size === 1 &&
        identifier.test(query.get("storeId") ?? "");
    if (!(method === "GET" && (get || hint)) && !(method === "POST" && post))
      throw new ApiError(HTTP.FORBIDDEN, "此页面不支持该操作");
    const headers: Record<string, string> = {
      Authorization: `Bearer ${context.token}`,
      "X-Tenant-Id": context.tenant,
    };
    if (options.body !== undefined)
      headers["Content-Type"] = "application/json";
    if (options.key) headers["Idempotency-Key"] = options.key;
    const response = await fetch(`/v1${path}`, {
      method,
      headers,
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal ?? AbortSignal.timeout(15000),
    }).catch(() => {
      throw new Error("连接中断，提交结果尚未确认");
    });
    if (response.status === 401) expired();
    if (!response.ok)
      throw new ApiError(
        response.status,
        new CentralError(response.status).message,
      );
    const value: unknown = await response.json();
    let valid = hint ? record(value) && value.allowed === true : true;
    if (!hint && Array.isArray(value))
      valid =
        value.length <= 50 &&
        value.every(
          family === "ops-pages" ? page : family === "runtime" ? record : row,
        );
    if (method === "POST") {
      const body = record(options.body) ? options.body : {};
      const parts = base.split("/");
      const objectId = parts[3];
      if (family === "orders" || family === "events") valid = integer(value);
      else if (family === "payments") valid = payment(value, objectId);
      else if (family === "fulfillments")
        valid = fulfillment(
          value,
          objectId,
          base.endsWith("/ship") ? body.trackingNo : undefined,
        );
      else if (family === "aftersales") valid = aftersale(value, objectId);
      else if (family === "refunds") valid = refund(value, objectId);
      else if (family === "ops-pages") {
        if (base.endsWith("/preview"))
          valid =
            record(value) &&
            page(value.page) &&
            record(value.page) &&
            record(value.page.content) &&
            value.page.content.pageId === body.pageId &&
            value.page.content.version === body.version &&
            value.page.content.storeId === body.storeId &&
            value.preview === true &&
            Array.isArray(value.data) &&
            value.data.length <= 8;
        else if (base.includes("/actions/")) {
          const campaign = record(body.campaign) ? body.campaign : undefined;
          const coupon = record(body.coupon) ? body.coupon : undefined;
          const enrollment = record(body.enrollment)
            ? body.enrollment
            : undefined;
          const kind = campaign
            ? "CREATE_CAMPAIGN"
            : coupon
              ? "CREATE_COUPON"
              : enrollment
                ? "ENROLL_JOURNEY"
                : undefined;
          const expected = campaign?.campaignId ?? coupon?.definitionId;
          valid =
            record(value) &&
            value.kind === kind &&
            id(value.resourceId) &&
            typeof value.status === "string" &&
            (expected === undefined || value.resourceId === expected) &&
            (kind === "CREATE_COUPON"
              ? value.status === "CREATED"
              : kind === "CREATE_CAMPAIGN"
                ? oneOf(value.status, [
                    "DRAFT",
                    "IN_REVIEW",
                    "APPROVED",
                    "REJECTED",
                    "PUBLISHED",
                    "PAUSED",
                  ])
                : oneOf(value.status, [
                    "RUNNING",
                    "COMPLETED",
                    "CANCELLED",
                    "FAILED",
                  ]));
        } else
          valid =
            page(value) &&
            record(value) &&
            record(value.content) &&
            value.content.pageId ===
              (base === "/admin/ops-pages" ? body.pageId : objectId) &&
            value.content.version ===
              (base === "/admin/ops-pages" ? body.version : Number(parts[4])) &&
            (base !== "/admin/ops-pages" ||
              value.content.storeId === body.storeId);
      } else if (family === "runtime") {
        if (base.endsWith("/dry-run"))
          valid =
            record(value) &&
            record(value.gate) &&
            value.consumer === body.consumer &&
            value.mode === body.mode &&
            typeof value.gate.allowed === "boolean" &&
            Array.isArray(value.byType) &&
            integer(value.events) &&
            integer(value.alreadyProcessed) &&
            integer(value.wouldExecute);
        else if (base.endsWith("/recoveries"))
          valid =
            record(value) &&
            value.workType === body.workType &&
            value.action === body.action &&
            integer(value.applied) &&
            integer(value.rejected) &&
            Array.isArray(value.outcomes) &&
            Array.isArray(body.workIds) &&
            value.outcomes.length === body.workIds.length &&
            value.outcomes.every(
              (v: unknown) =>
                record(v) &&
                (body.workIds as unknown[]).includes(v.workId) &&
                oneOf(v.result, ["APPLIED", "REJECTED"]),
            );
        else
          valid = replay(
            value,
            base.endsWith("/control") ? parts[4] : body.jobId,
          );
      }
    }
    if (!valid)
      throw new Error("回执不完整，操作结果尚未确认，请保留原目标重试");
    return value as T;
  };
}
