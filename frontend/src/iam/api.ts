import { useEffect, useState } from "react";
export const HTTP = {
  UNAUTHORIZED: 401,
  FORBIDDEN: 403,
  CONFLICT: 409,
  UNAVAILABLE: 503,
} as const;
export class CentralError extends Error {
  constructor(
    public status: number,
    public traceId?: string,
  ) {
    super(
      status === HTTP.UNAUTHORIZED
        ? "登录已失效，请重新登录"
        : status === HTTP.FORBIDDEN
          ? "当前成员没有此操作或资源的权限"
          : status === HTTP.CONFLICT
            ? "资料或权限范围已变化，请刷新后重试"
            : status >= 500
              ? "授权或业务服务暂不可用，请稍后重试"
              : "请求未完成，请检查输入",
    );
  }
}
export type Context = { token: string; tenant: string };
export const sessionInvalidatedEvent = "central-session-invalidated";
/** 商品客户端与导航共同通知会话边界，401不能保留旧页面数据。 */
export function invalidateSession(status: number) {
  if (status === HTTP.UNAUTHORIZED)
    dispatchEvent(new Event(sessionInvalidatedEvent));
}
export async function central<T>(
  context: Context,
  path: string,
  body?: unknown,
  key?: string,
  signal?: AbortSignal,
): Promise<T> {
  const headers: Record<string, string> = {
    Authorization: `Bearer ${context.token}`,
    "X-Tenant-Id": context.tenant,
  };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (key) headers["Idempotency-Key"] = key;
  const response = await fetch(`/v1/operations/scoped/product${path}`, {
    method: body === undefined ? "GET" : "POST",
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: signal ?? AbortSignal.timeout(15000),
  });
  const result = await response.json();
  if (!response.ok) {
    invalidateSession(response.status);
    throw new CentralError(response.status, result.traceId);
  }
  return result;
}
/** 路由、租户或身份变化立即清空数据，旧响应不能覆盖新上下文。 */
export function useCentral<T>(
  context: Context,
  path: string | null,
  revision = 0,
) {
  const [state, setState] = useState<{
    data?: T;
    error?: Error;
    loading: boolean;
  }>({ loading: true });
  useEffect(() => {
    const controller = new AbortController();
    setState({ loading: !!path });
    if (path !== null)
      // StrictMode会立即撤销第一次effect；在发请求前检查，避免废弃请求占用实时鉴权并发槽。
      Promise.resolve()
        .then(() =>
          controller.signal.aborted
            ? undefined
            : central<T>(
                context,
                path,
                undefined,
                undefined,
                controller.signal,
              ),
        )
        .then((data) => {
          if (!controller.signal.aborted) setState({ data, loading: false });
        })
        .catch((error) => {
          if (!controller.signal.aborted) setState({ error, loading: false });
        });
    return () => controller.abort();
  }, [context.token, context.tenant, path, revision]);
  return state;
}
