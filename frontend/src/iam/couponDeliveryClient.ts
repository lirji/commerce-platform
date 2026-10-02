import { ApiError, type request } from "../shared/api";
import { CentralError, HTTP, type Context } from "./api";

export const DELIVERY_PAGE_SIZE = 50;
export const DELIVERY_QUANTUM = 20;
export const deliveryIdentifier = /^[A-Za-z0-9_.:-]{1,64}$/;
const memberIdentifier = /^[A-Za-z0-9_.:-]{1,100}$/;
export const deliveryActions = ["create", "control", "pump"] as const;
export type DeliveryAction = (typeof deliveryActions)[number];
export const deliveryControls = ["CANCEL", "RETRY", "REVOKE"] as const;
export type DeliveryControl = (typeof deliveryControls)[number];
export type DeliveryCreate = {
  batchId: string;
  storeId: string;
  name: string;
  definitionId: string;
  definitionVersion: number;
  audience: { id: string; version: number };
  deadline: string;
  minIntervalHours: number;
};
export const deliveryStatuses = [
  "RUNNING",
  "COMPLETED",
  "CANCELLED",
  "EXPIRED",
  "ISOLATED",
  "REVOKING",
  "REVOCATION_DONE",
] as const;
export type DeliveryView = {
  content: DeliveryCreate;
  status: (typeof deliveryStatuses)[number];
  mode: "ISSUE" | "REVOKE";
  processed: number;
  issued: number;
  skipped: number;
  revoked: number;
  kept: number;
  cursorMember: string;
  revokeCursor: string;
  attempts: number;
  errorCode: string | null;
  version: number;
};
export type DeliveryRecipient = {
  memberId: string;
  status: "ISSUED" | "SKIPPED" | "REVOKED" | "KEPT";
  couponId: string | null;
  errorCode: string | null;
  createdAt: string;
};
export type DeliveryControlInput = {
  expectedVersion: number;
  action: DeliveryControl;
  reason: string;
};

const base = "/admin/coupon-deliveries";
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);
const integer = (value: unknown, minimum = 0) =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= minimum;
const identifier = (value: unknown) =>
  typeof value === "string" && deliveryIdentifier.test(value);
const member = (value: unknown) =>
  typeof value === "string" && memberIdentifier.test(value);
const cursor = (value: unknown) => value === "" || member(value);
const instant = (value: unknown) =>
  typeof value === "string" && Number.isFinite(Date.parse(value));
const nullableText = (value: unknown) =>
  value === null || typeof value === "string";

/** 公开内容仅包含真实固定来源，不伪造内部授权内容版本或来源元数据。 */
export function isDeliveryCreate(value: unknown): value is DeliveryCreate {
  return (
    record(value) &&
    identifier(value.batchId) &&
    identifier(value.storeId) &&
    typeof value.name === "string" &&
    value.name.trim().length > 0 &&
    value.name.length <= 128 &&
    identifier(value.definitionId) &&
    integer(value.definitionVersion, 1) &&
    record(value.audience) &&
    identifier(value.audience.id) &&
    integer(value.audience.version, 1) &&
    instant(value.deadline) &&
    integer(value.minIntervalHours, 1) &&
    Number(value.minIntervalHours) <= 720
  );
}

export function isDeliveryControl(
  value: unknown,
): value is DeliveryControlInput {
  return (
    record(value) &&
    integer(value.expectedVersion) &&
    deliveryControls.some((action) => action === value.action) &&
    typeof value.reason === "string" &&
    value.reason.trim().length > 0 &&
    value.reason.length <= 256
  );
}

/** 进度CAS与真实效果分别校验；已发放不推断为券当前可用。 */
export function isDeliveryView(value: unknown): value is DeliveryView {
  return (
    record(value) &&
    isDeliveryCreate(value.content) &&
    deliveryStatuses.some((status) => status === value.status) &&
    ["ISSUE", "REVOKE"].includes(String(value.mode)) &&
    [
      value.processed,
      value.issued,
      value.skipped,
      value.revoked,
      value.kept,
      value.version,
    ].every((field) => integer(field)) &&
    value.processed === Number(value.issued) + Number(value.skipped) &&
    Number(value.revoked) + Number(value.kept) <= Number(value.issued) &&
    cursor(value.cursorMember) &&
    cursor(value.revokeCursor) &&
    integer(value.attempts) &&
    nullableText(value.errorCode)
  );
}

export function isDeliveryRecipient(
  value: unknown,
): value is DeliveryRecipient {
  return (
    record(value) &&
    member(value.memberId) &&
    ["ISSUED", "SKIPPED", "REVOKED", "KEPT"].includes(String(value.status)) &&
    nullableText(value.couponId) &&
    nullableText(value.errorCode) &&
    instant(value.createdAt)
  );
}

/** 只允许本页准确方法与有界游标，中央凭据不能流入其他管理入口。 */
export function couponDeliveryClient(
  context: Context,
  expired: () => void,
): typeof request {
  return async <T>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    let directory = false,
      recipients = false,
      control = false;
    let target: string | undefined;
    try {
      const [route, queryText, extra] = path.split("?");
      if (extra !== undefined || path.includes("#"))
        throw new Error("无效路径");
      const item =
        /^\/admin\/coupon-deliveries\/([^/]+)\/(recipients|control)$/.exec(
          route,
        );
      if (item) target = decodeURIComponent(item[1]);
      if (method === "GET" && queryText !== undefined) {
        const query = new URLSearchParams(queryText);
        const bounded =
          query.get("limit") === String(DELIVERY_PAGE_SIZE) &&
          (query.get("after") === "" ||
            (route === base
              ? identifier(query.get("after"))
              : member(query.get("after"))));
        directory =
          route === base &&
          bounded &&
          query.size === 3 &&
          [...query.keys()].every((key) =>
            ["storeId", "after", "limit"].includes(key),
          ) &&
          identifier(query.get("storeId"));
        recipients =
          item?.[2] === "recipients" &&
          identifier(target) &&
          bounded &&
          query.size === 2 &&
          [...query.keys()].every((key) => ["after", "limit"].includes(key));
      }
      control =
        method === "POST" &&
        queryText === undefined &&
        item?.[2] === "control" &&
        identifier(target);
    } catch {
      /* 编码或查询异常不回退旧身份，也不把路径直接交给通用客户端。 */
    }
    const hint = deliveryActions.some(
      (action) => path === `/operations/coupon-deliveries/${action}-access`,
    );
    const pump = path === `${base}/pump`;
    const create = path === base;
    if (!(
      (method === "GET" &&
        options.body === undefined &&
        (directory || recipients || hint)) ||
      (method === "POST" && (create || control || pump))
    ))
      throw new ApiError(HTTP.FORBIDDEN, "此发券入口不支持该操作");
    if (method === "POST" && !pump && !options.key)
      throw new Error("创建或控制须保留原幂等键");

    if (
      method === "POST" &&
      ((create && !isDeliveryCreate(options.body)) ||
        (control && !isDeliveryControl(options.body)) ||
        (pump && options.body !== undefined))
    )
      throw new ApiError(400, "发券操作参数无效");

    const headers: Record<string, string> = {
      Authorization: `Bearer ${context.token}`,
      "X-Tenant-Id": context.tenant,
    };
    if (options.body !== undefined)
      headers["Content-Type"] = "application/json";
    if (method === "POST" && options.key && !pump)
      headers["Idempotency-Key"] = options.key;
    const response = await fetch(`/v1${path}`, {
      method,
      headers,
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal
        ? AbortSignal.any([options.signal, AbortSignal.timeout(15000)])
        : AbortSignal.timeout(15000),
    }).catch(() => {
      throw new Error("服务连接中断，操作结果尚未确认");
    });
    if (response.status === HTTP.UNAUTHORIZED) expired();
    if (!response.ok)
      throw new ApiError(
        response.status,
        new CentralError(response.status).message,
      );
    const result: unknown = await response.json();
    const valid =
      method === "POST"
        ? pump
          ? integer(result) && Number(result) <= DELIVERY_QUANTUM
          : isDeliveryView(result)
        : hint
          ? record(result) && result.allowed === true
          : Array.isArray(result) &&
            result.length <= DELIVERY_PAGE_SIZE &&
            result.every(directory ? isDeliveryView : isDeliveryRecipient);
    if (!valid) throw new Error("服务未返回可确认的发券结果，请保留原操作核对");
    if (method === "POST" && !pump) {
      const body = record(options.body) ? options.body : undefined;
      if (
        !isDeliveryView(result) ||
        result.content.batchId !== (create ? body?.batchId : target)
      )
        throw new Error("发券回执与原目标不一致，请保留原操作核对");
      if (
        create &&
        (!isDeliveryCreate(body) ||
          result.content.storeId !== body.storeId ||
          result.content.name !== body.name ||
          result.content.definitionId !== body.definitionId ||
          result.content.definitionVersion !== body.definitionVersion ||
          result.content.audience.id !== body.audience.id ||
          result.content.audience.version !== body.audience.version ||
          Date.parse(result.content.deadline) !== Date.parse(body.deadline) ||
          result.content.minIntervalHours !== body.minIntervalHours)
      )
        throw new Error("发券回执与原固定来源不一致，请保留原操作核对");
    }
    return result as T;
  };
}
