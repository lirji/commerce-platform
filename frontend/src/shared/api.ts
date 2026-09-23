import { useCallback, useEffect, useRef, useState } from "react";
let accessToken = "";
export function setAccessToken(value: string) {
  accessToken = value;
}
export class ApiError extends Error {
  constructor(
    public status: number,
    message: string,
    public traceId?: string,
  ) {
    super(message);
  }
}
/** 超时意味着结果未知，命令调用方保留幂等键以便安全重试。 */
export async function request<T>(
  path: string,
  options: {
    method?: string;
    body?: unknown;
    key?: string;
    signal?: AbortSignal;
  } = {},
): Promise<T> {
  const headers: Record<string, string> = {};
  if (accessToken) headers.Authorization = `Bearer ${accessToken}`;
  if (options.body !== undefined) headers["Content-Type"] = "application/json";
  if (options.key) headers["Idempotency-Key"] = options.key;
  const response = await fetch(`/v1${path}`, {
    method: options.method ?? "GET",
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body),
    signal: options.signal ?? AbortSignal.timeout(15000),
  });
  const result = await response.json();
  if (!response.ok)
    throw new ApiError(
      response.status,
      result.message ?? "请求未能完成",
      result.traceId,
    );
  return result as T;
}
export const post = <T>(path: string, body?: unknown, key?: string) =>
  request<T>(path, { method: "POST", body, key });
/** 页面读取隔离生命周期，旧请求不能覆盖新的路由数据。 */
export function useResource<T>(path: string | null) {
  const [data, setData] = useState<T>();
  const [error, setError] = useState<Error>();
  const [loading, setLoading] = useState(false);
  const [revision, setRevision] = useState(0);
  const refresh = useCallback(() => setRevision((v) => v + 1), []);
  useEffect(() => {
    const controller = new AbortController();
    setData(undefined);
    setError(undefined);
    if (!path) {
      setLoading(false);
      return;
    }
    setLoading(true);
    request<T>(path, { signal: controller.signal })
      .then((value) => {
        if (!controller.signal.aborted) setData(value);
      })
      .catch((e) => {
        if (!controller.signal.aborted) setError(e);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [path, revision]);
  return { data, error, loading, refresh };
}
/** 相同未完成意图复用键；输入改变或成功后才生成下一命令键。 */
export function useCommand() {
  const prior = useRef<{ fingerprint: string; key: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error>();
  const running = useRef(false);
  async function run<T>(path: string, body?: unknown): Promise<T | undefined> {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    const fingerprint = JSON.stringify([path, body]);
    if (prior.current?.fingerprint !== fingerprint)
      prior.current = { fingerprint, key: crypto.randomUUID() };
    try {
      const result = await post<T>(path, body, prior.current.key);
      prior.current = null;
      return result;
    } catch (e) {
      setError(e instanceof Error ? e : new Error("请求失败"));
      return undefined;
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  return { run, busy, error, clear: () => setError(undefined) };
}
export const encode = encodeURIComponent;
