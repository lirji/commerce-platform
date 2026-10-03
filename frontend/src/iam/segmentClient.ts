import { listGuardPath } from "../shared/listFilters";
import { ApiError, type request } from "../shared/api";
import type { Rule } from "../shared/contracts";
import { CentralError, HTTP, type Context } from "./api";

export const SEGMENT_PAGE_SIZE = 50;
export const segmentIdentifier = /^[A-Za-z0-9_.:-]{1,64}$/;
export const segmentActions = [
  "create",
  "schedule",
  "refresh",
  "control",
  "pump",
] as const;
export type SegmentAction = (typeof segmentActions)[number];
export type SegmentDefinition = {
  segmentId: string;
  version: number;
  name: string;
  rule: Rule;
  ttlSeconds: number;
  refreshSeconds: number;
  maxMembers: number;
};
export type SegmentView = {
  content: SegmentDefinition;
  audienceId: string;
  enabled: boolean;
  lockVersion: number;
};
export type SegmentRun = {
  runId: string;
  segmentId: string;
  definitionVersion: number;
  audienceId: string;
  snapshotVersion: number;
  cursorMember: string;
  processed: number;
  matched: number;
  status: string;
  attempts: number;
  errorCode: string | null;
  startedAt: string;
  validUntil: string;
  availableAt: string;
  entriesAnnounced: boolean;
  entryAttempts: number;
};
export type SegmentReceipt = SegmentView | SegmentRun;
const identifier = (value: unknown) =>
  typeof value === "string" && segmentIdentifier.test(value);
const integer = (value: unknown, min = 0) =>
  typeof value === "number" && Number.isSafeInteger(value) && value >= min;
const instant = (value: unknown) =>
  typeof value === "string" && Number.isFinite(Date.parse(value));
const record = (value: unknown): value is Record<string, unknown> =>
  !!value && typeof value === "object" && !Array.isArray(value);
/** 公开会员规则协议的有限字段类型，不是会员数据或额外读取权限。 */
const textFields = new Set([
  "memberLevel",
  "memberStatus",
  "memberTags",
  "memberBirthdayToday",
  "memberJourneyEnabled",
]);
const decimalFields = new Set([
  "memberGrowth",
  "memberNetSpend",
  "memberBrowse30",
  "memberCart30",
  "memberOrders30",
  "memberSpend30",
  "memberDaysSinceOrder",
  "memberDaysSinceJoin",
]);
const ruleKinds = { COMPARE: "COMPARE", NOT: "NOT" } satisfies Record<
  string,
  Rule["kind"]
>;
export function isSegmentRule(value: unknown): value is Rule {
  let count = 0;
  function visit(node: unknown, depth: number): boolean {
    if (!record(node) || depth > 8 || ++count > 128) return false;
    if (node.kind === ruleKinds.COMPARE) {
      const text = typeof node.field === "string" && textFields.has(node.field);
      const decimal =
        typeof node.field === "string" && decimalFields.has(node.field);
      return (
        (text || decimal) &&
        node.valueType === (text ? "TEXT" : "DECIMAL") &&
        typeof node.value === "string" &&
        node.value.trim().length > 0 &&
        node.value.length <= 256 &&
        (text ||
          (node.value.length <= 32 &&
            /^[+-]?(?:[0-9]+(?:\.[0-9]*)?|\.[0-9]+)(?:[eE][+-]?[0-9]+)?$/.test(
              node.value,
            ))) &&
        (node.field === "memberTags"
          ? node.operator === "CONTAINS"
          : ["EQ", "GT", "GTE", "LT", "LTE"].includes(String(node.operator))) &&
        (node.children == null ||
          (Array.isArray(node.children) && node.children.length === 0))
      );
    }
    if (
      !["ALL", "ANY", "NOT"].includes(String(node.kind)) ||
      [node.field, node.operator, node.valueType, node.value].some(
        (field) => field != null,
      ) ||
      !Array.isArray(node.children) ||
      node.children.length < 1 ||
      node.children.length > 16 ||
      (node.kind === ruleKinds.NOT && node.children.length !== 1)
    )
      return false;
    return node.children.every((child) => visit(child, depth + 1));
  }
  return visit(value, 1);
}
export function isSegmentView(value: unknown): value is SegmentView {
  if (!record(value) || !record(value.content)) return false;
  const definition = value.content;
  return (
    identifier(definition.segmentId) &&
    integer(definition.version, 1) &&
    typeof definition.name === "string" &&
    isSegmentRule(definition.rule) &&
    integer(definition.ttlSeconds, 300) &&
    Number(definition.ttlSeconds) <= 86400 &&
    integer(definition.refreshSeconds) &&
    (definition.refreshSeconds === 0 ||
      (Number(definition.refreshSeconds) >= 60 &&
        Number(definition.refreshSeconds) <= Number(definition.ttlSeconds))) &&
    integer(definition.maxMembers, 100) &&
    Number(definition.maxMembers) <= 100000 &&
    identifier(value.audienceId) &&
    typeof value.enabled === "boolean" &&
    integer(value.lockVersion)
  );
}
export function isSegmentRun(value: unknown): value is SegmentRun {
  return (
    record(value) &&
    identifier(value.runId) &&
    identifier(value.segmentId) &&
    identifier(value.audienceId) &&
    integer(value.definitionVersion, 1) &&
    integer(value.snapshotVersion, 1) &&
    typeof value.cursorMember === "string" &&
    integer(value.processed) &&
    integer(value.matched) &&
    Number(value.matched) <= Number(value.processed) &&
    ["RUNNING", "ISOLATED", "COMPLETED", "FAILED", "CANCELLED"].includes(
      String(value.status),
    ) &&
    integer(value.attempts) &&
    (value.errorCode === null || typeof value.errorCode === "string") &&
    instant(value.startedAt) &&
    instant(value.validUntil) &&
    instant(value.availableAt) &&
    typeof value.entriesAnnounced === "boolean" &&
    integer(value.entryAttempts)
  );
}

/** 白名单限定精确方法、字面资格及有界游标，中央凭据不进入任意旧管理入口。 */
export function segmentClient(
  context: Context,
  expired: () => void,
): typeof request {
  return async <T>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    const guardedPath = listGuardPath(path);
    let directory = false,
      runs = false,
      target = false,
      viewCommand = false;
    try {
      const queryIndex = guardedPath.indexOf("?");
      const base = queryIndex < 0 ? path : path.slice(0, queryIndex);
      const runList = /^\/admin\/segments\/([^/]+)\/runs$/.exec(base);
      if (queryIndex >= 0 && (base === "/admin/segments" || runList)) {
        const query = new URLSearchParams(guardedPath.slice(queryIndex + 1));
        const bounded =
          Array.from(query.keys()).length === 2 &&
          query.has("after") &&
          query.get("limit") === String(SEGMENT_PAGE_SIZE) &&
          (query.get("after") === "" || identifier(query.get("after")));
        directory = bounded && base === "/admin/segments";
        runs =
          bounded && !!runList && identifier(decodeURIComponent(runList[1]));
      }
      const segment = /^\/admin\/segments\/([^/]+)\/(schedule|refresh)$/.exec(
        path,
      );
      const control =
        /^\/admin\/segment-runs\/([^/]+)\/(cancel|retry|retry-announcement)$/.exec(
          path,
        );
      target =
        (!!segment && identifier(decodeURIComponent(segment[1]))) ||
        (!!control && identifier(decodeURIComponent(control[1])));
      viewCommand =
        path === "/admin/segments" || (!!segment && segment[2] === "schedule");
    } catch {
      /* 编码异常拒绝，不回退旧身份。 */
    }
    const hint = segmentActions.some(
      (action) => path === `/operations/segments/${action}-access`,
    );
    const pump = path === "/admin/segments/pump";
    if (!(
      (method === "GET" && (directory || runs || hint)) ||
      (method === "POST" && (path === "/admin/segments" || target || pump))
    ))
      throw new ApiError(HTTP.FORBIDDEN, "此入口不支持该操作");
    const headers: Record<string, string> = {
      Authorization: `Bearer ${context.token}`,
      "X-Tenant-Id": context.tenant,
    };
    if (options.body !== undefined)
      headers["Content-Type"] = "application/json";
    if (options.key && !pump) headers["Idempotency-Key"] = options.key;
    const response = await fetch(`/v1${path}`, {
      method,
      headers,
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal ?? AbortSignal.timeout(15000),
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
          ? integer(result)
          : viewCommand
            ? isSegmentView(result)
            : isSegmentRun(result)
        : hint
          ? record(result) && result.allowed === true
          : Array.isArray(result) &&
            result.length <= SEGMENT_PAGE_SIZE &&
            result.every(directory ? isSegmentView : isSegmentRun);
    // 成功状态的损坏回执也属于未知结果，不能清除原意图或把任务受理当完成。
    if (!valid) throw new Error("服务未返回可确认的动态人群结果，请核对原操作");
    if (method === "POST" && !pump) {
      const body = record(options.body) ? options.body : undefined;
      const expected = viewCommand
        ? path === "/admin/segments"
          ? body?.segmentId
          : decodeURIComponent(path.split("/")[3])
        : decodeURIComponent(path.split("/")[3]);
      const matching = isSegmentView(result)
        ? result.content.segmentId === expected &&
          (path !== "/admin/segments" ||
            result.content.version === body?.version)
        : isSegmentRun(result) &&
          (path.startsWith("/admin/segment-runs/")
            ? result.runId === expected
            : result.segmentId === expected);
      if (!matching)
        throw new Error("服务回执与原目标不一致，请保留原操作核对");
    }
    return result as T;
  };
}
