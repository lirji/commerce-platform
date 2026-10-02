import { useEffect, useRef, useState } from "react";
import { ApiError, useResource, type request } from "../shared/api";
import { HTTP } from "./api";
import type { SegmentAction, SegmentReceipt } from "./segmentClient";

type KeyedAction = Exclude<SegmentAction, "pump">;
type Intent = { key: string; path: string; body: unknown; action: KeyedAction };
/** 创建/调度/刷新/控制仅在确定失败后释放意图；pump没有幂等键，另行显式确认。 */
export function useSegmentCommand(
  client: typeof request,
  dirty: (value: boolean) => void,
) {
  const intent = useRef<Intent | null>(null),
    running = useRef(false),
    uncertain = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false);
  const [error, setError] = useState<Error>(),
    [result, setResult] = useState<SegmentReceipt>();
  async function run(path: string, body: unknown, action: KeyedAction) {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    dirty(true);
    setError(undefined);
    setResult(undefined);
    let sent = false;
    try {
      const qualification = await client<{ allowed: boolean }>(
        `/operations/segments/${intent.current?.action ?? action}-access`,
      );
      if (qualification.allowed !== true)
        throw new ApiError(HTTP.FORBIDDEN, "当前未获该动态人群操作权限");
      if (!intent.current)
        intent.current = {
          path,
          body: structuredClone(body),
          action,
          key: crypto.randomUUID(),
        };
      sent = true;
      const pending = intent.current;
      const value = await client<SegmentReceipt>(pending.path, {
        method: "POST",
        body: pending.body,
        key: pending.key,
      });
      setResult(value);
      intent.current = null;
      uncertain.current = false;
      setFrozen(false);
      dirty(false);
      return value;
    } catch (failure) {
      setError(
        failure instanceof Error ? failure : new Error("操作结果尚未确认"),
      );
      if (
        uncertain.current ||
        (sent && (!(failure instanceof ApiError) || failure.status >= 500))
      ) {
        uncertain.current = true;
        setFrozen(true);
        dirty(true);
      } else intent.current = null;
      return undefined;
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  return {
    run,
    busy,
    frozen,
    error,
    result,
    clear: () => {
      if (running.current) return;
      setError(undefined);
      if (!uncertain.current) {
        intent.current = null;
        setResult(undefined);
      }
    },
  };
}

/** 重新核验立即关闭旧资格；当前请求完成前不会用旧成功结果放开写入。 */
export function useSegmentQualification(action: SegmentAction) {
  const access = useResource<{ allowed: boolean }>(
    `/operations/segments/${action}-access`,
  );
  const [rechecking, setRechecking] = useState(false),
    observed = useRef(false);
  useEffect(() => {
    if (!rechecking) return;
    if (access.loading) observed.current = true;
    else if (observed.current) setRechecking(false);
  }, [access.loading, access.data, access.error, rechecking]);
  return {
    ...access,
    loading: access.loading || rechecking,
    refresh: () => {
      observed.current = false;
      setRechecking(true);
      access.refresh();
    },
  };
}
