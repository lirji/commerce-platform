import { ApiError, type request } from "../shared/api";
import { CentralError, HTTP, type Context } from "./api";
export const journeyId = /^[A-Za-z0-9_.:-]{1,64}$/;
export const journeyActions = [
  "create",
  "validate",
  "preview",
  "submit",
  "approve",
  "reject",
  "publish",
  "pause",
  "pump",
] as const;
export type JourneyAction = (typeof journeyActions)[number];
const target = "[A-Za-z0-9_.:-]{1,64}";
const version = "[1-9][0-9]{0,15}";
type Json = Record<string, unknown>;
const object = (value: unknown): value is Json =>
  value !== null && typeof value === "object" && !Array.isArray(value);
const integer = (value: unknown, minimum = 0) =>
  Number.isSafeInteger(value) && Number(value) >= minimum;
const id = (value: unknown) =>
  typeof value === "string" && journeyId.test(value);
const text = (value: unknown) => typeof value === "string";
const view = (value: unknown): value is Json =>
  object(value) &&
  object(value.content) &&
  id(value.content.journeyId) &&
  integer(value.content.version, 1) &&
  Array.isArray(value.content.nodes) &&
  text(value.content.name) &&
  text(value.status) &&
  integer(value.lockVersion);
const instance = (value: unknown): value is Json =>
  object(value) &&
  id(value.instanceId) &&
  id(value.journeyId) &&
  integer(value.journeyVersion, 1) &&
  id(value.memberId) &&
  text(value.status) &&
  integer(value.version);
const scan = (value: unknown): value is Json =>
  object(value) &&
  id(value.journeyId) &&
  integer(value.journeyVersion, 1) &&
  text(value.status) &&
  integer(value.version);
/** 坏2xx或错误目标不确认业务提交，冻结原键供真实同键重试。只核对实际DTO的最小可确认字段。 */
function confirmed(
  base: string,
  method: string,
  value: unknown,
  body: unknown,
) {
  if (base.endsWith("-access"))
    return object(value) && typeof value.allowed === "boolean";
  if (method === "GET") {
    if (base === "/admin/journeys")
      return Array.isArray(value) && value.every(view);
    if (base === "/admin/journey-instances")
      return Array.isArray(value) && value.every(instance);
    if (base === "/admin/journey-scans")
      return Array.isArray(value) && value.every(scan);
    if (base.endsWith("/history"))
      return (
        object(value) &&
        instance(value.instance) &&
        object(value.definition) &&
        value.instance.instanceId === base.split("/")[3] &&
        value.definition.journeyId === value.instance.journeyId &&
        value.definition.version === value.instance.journeyVersion &&
        Array.isArray(value.steps) &&
        text(value.traceCoverage)
      );
    if (
      [
        "/admin/marketing-effects",
        "/admin/marketing-effects/journeys",
        "/admin/marketing-effects/deliveries",
      ].includes(base)
    )
      return (
        object(value) &&
        Array.isArray(value.rows) &&
        value.rows.every((row) => object(row) && text(row.seriesId)) &&
        text(value.coverage) &&
        text(value.costBasis)
      );
    return Array.isArray(value) || object(value);
  }
  const input = object(body) ? body : {};
  if (base === "/admin/journeys/validate")
    return (
      object(value) &&
      typeof value.valid === "boolean" &&
      Array.isArray(value.issues)
    );
  if (base === "/admin/journeys/pump") return integer(value);
  if (base === "/admin/marketing-effects/rebuild")
    return (
      object(value) &&
      integer(value.processed) &&
      text(value.nextAfter) &&
      typeof value.hasMore === "boolean"
    );
  if (base === "/admin/journeys")
    return (
      view(value) &&
      object(value.content) &&
      value.content.journeyId === input.journeyId &&
      value.content.version === input.version
    );
  if (base === "/admin/journey-instances")
    return (
      instance(value) &&
      value.journeyId === input.journeyId &&
      value.journeyVersion === input.version &&
      value.memberId === input.memberId
    );
  const parts = base.split("/");
  if (parts[2] === "journeys" && parts[5] === "preview")
    return (
      object(value) &&
      value.journeyId === parts[3] &&
      value.version === Number(parts[4]) &&
      Array.isArray(value.path) &&
      text(value.evaluatedAt)
    );
  if (parts[2] === "journeys")
    return (
      view(value) &&
      object(value.content) &&
      value.content.journeyId === parts[3] &&
      value.content.version === Number(parts[4])
    );
  if (parts[2] === "journey-instances")
    return instance(value) && value.instanceId === parts[3];
  if (parts[2] === "journey-scans")
    return (
      scan(value) &&
      value.journeyId === parts[3] &&
      value.journeyVersion === Number(parts[4])
    );
  return false;
}
/** 中央凭据仅进入本片已登记的精确方法与路径，无旧控制台Token回退。 */
export function journeyClient(
  context: Context,
  expired: () => void,
): typeof request {
  return async <T>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    const [base, query] = path.split("?");
    const hints =
      /^\/operations\/(journeys\/(create|validate|preview|submit|approve|reject|publish|pause|pump)|journey-instances\/(create|control)|journey-scans\/retry|marketing-effects\/rebuild)-access$/;
    const lists = new Set([
      "/admin/journeys",
      "/admin/journey-instances",
      "/admin/journey-scans",
      "/admin/marketing-executions",
      "/admin/marketing-effects",
      "/admin/marketing-effects/journeys",
      "/admin/marketing-effects/deliveries",
      "/admin/journey-effects",
    ]);
    const detail = new RegExp(
      `^/admin/(journey-instances/${target}/history|marketing-executions/${target}/${target})$`,
    );
    const command = new RegExp(
      `^/admin/(journeys/${target}/${version}/(preview|submit|approve|reject|publish|pause)|journey-instances/${target}/(cancel|retry)|journey-scans/${target}/${version}/retry)$`,
    );
    const getAllowed =
      method === "GET" &&
      (hints.test(base) || lists.has(base) || detail.test(base));
    const postAllowed =
      method === "POST" &&
      !query &&
      (command.test(base) ||
        [
          "/admin/journeys",
          "/admin/journeys/validate",
          "/admin/journeys/pump",
          "/admin/journey-instances",
          "/admin/marketing-effects/rebuild",
        ].includes(base));
    if (!getAllowed && !postAllowed)
      throw new ApiError(403, "此页面没有登记该业务入口");
    if (query) {
      const params = new URLSearchParams(query);
      if (
        [...params.keys()].some(
          (key) =>
            ![
              "after",
              "afterOrder",
              "afterVersion",
              "limit",
              "storeId",
              "from",
              "to",
            ].includes(key),
        ) ||
        (params.has("limit") && params.get("limit") !== "50")
      )
        throw new ApiError(400, "查询参数超出页面范围");
    }
    const response = await fetch(`/v1${path}`, {
      method,
      headers: {
        Authorization: `Bearer ${context.token}`,
        "X-Tenant-Id": context.tenant,
        ...(options.body !== undefined
          ? { "Content-Type": "application/json" }
          : {}),
        ...(options.key ? { "Idempotency-Key": options.key } : {}),
      },
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal ?? AbortSignal.timeout(15000),
    });
    const value = await response.json().catch(() => {
      if (response.ok) throw new Error("服务结果尚未确认");
      return {};
    });
    if (response.status === HTTP.UNAUTHORIZED) expired();
    if (!response.ok)
      throw new ApiError(
        response.status,
        new CentralError(response.status).message,
        value.traceId,
      );
    if (!confirmed(base, method, value, options.body))
      throw new Error("服务结果尚未确认");
    return value as T;
  };
}
