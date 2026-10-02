import { ApiError, type request } from "../shared/api";
import { CentralError, HTTP, type Context } from "./api";

export const AUDIENCE_PAGE_SIZE = 50;
export const audienceIdentifier = /^[A-Za-z0-9_.:-]{1,64}$/;
export type Audience = {
  audienceId: string;
  version: number;
  name: string;
  source: string;
  watermark: string;
  validUntil: string;
  memberIds: string[];
};
export type AudienceView = Omit<Audience, "memberIds"> & {
  memberCount: number;
};

/** 精确入口白名单确保中央身份只用于本片的目录、创建和独立资格提示。 */
export function audienceClient(
  context: Context,
  expired: () => void,
): typeof request {
  return async <T>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    let directory = false;
    if (path.startsWith("/admin/audiences?")) {
      const query = new URLSearchParams(path.slice(path.indexOf("?") + 1));
      directory =
        Array.from(query.keys()).length === 2 &&
        query.has("after") &&
        query.get("limit") === String(AUDIENCE_PAGE_SIZE) &&
        (query.get("after") === "" ||
          audienceIdentifier.test(query.get("after") ?? ""));
    }
    if (!(
      (method === "GET" &&
        (directory || path === "/operations/audiences/create-access")) ||
      (method === "POST" && path === "/admin/audiences")
    ))
      throw new ApiError(HTTP.FORBIDDEN, "此入口不支持该操作");
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
      throw new Error("服务连接中断，请稍后重试");
    });
    if (response.status === HTTP.UNAUTHORIZED) expired();
    if (!response.ok)
      throw new ApiError(
        response.status,
        new CentralError(response.status).message,
      );
    return (await response.json()) as T;
  };
}
