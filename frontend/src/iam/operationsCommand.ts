import { useEffect, useRef, useState } from "react";
import { ApiError, type request } from "../shared/api";
import type { OperationFamily } from "./operationsClient";

type Intent = { path: string; body: unknown; action: string; key?: string };
/** 未知结果冻结原目标/输入/键；撤权或短资格失败也不释放已发送意图。 */
export function useOperationsCommand(
  client: typeof request,
  family: OperationFamily,
  dirty: (v: boolean) => void,
) {
  const intent = useRef<Intent | null>(null),
    running = useRef(false),
    uncertain = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false),
    [error, setError] = useState<Error>(),
    [result, setResult] = useState<unknown>();
  async function run<T>(
    path: string,
    body: unknown,
    action: string,
    keyed = true,
  ): Promise<T | undefined> {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    setResult(undefined);
    dirty(true);
    let sent = false;
    try {
      const allowed = await client<{ allowed: boolean }>(
        `/operations/${family}/${intent.current?.action ?? action}-access`,
      );
      if (allowed.allowed !== true) throw new ApiError(403, "当前操作未授权");
      if (!intent.current)
        intent.current = {
          path,
          body: structuredClone(body),
          action,
          key: keyed ? crypto.randomUUID() : undefined,
        };
      const pending = intent.current;
      sent = true;
      const value = await client<T>(pending.path, {
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
      setError(failure instanceof Error ? failure : new Error("提交失败"));
      if (
        uncertain.current ||
        (sent && (!(failure instanceof ApiError) || failure.status >= 500))
      ) {
        uncertain.current = true;
        setFrozen(true);
        dirty(true);
      } else {
        intent.current = null;
        dirty(false);
      }
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
      if (!running.current && !uncertain.current) {
        intent.current = null;
        setError(undefined);
        setResult(undefined);
      }
    },
  };
}
/** 页面内的未确认工作阻止关闭/离开，凭据和命令意图不写浏览器持久存储。 */
export function useOperationsLeave(dirty: boolean) {
  useEffect(() => {
    const leave = (event: BeforeUnloadEvent) => {
      if (dirty) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", leave);
    return () => window.removeEventListener("beforeunload", leave);
  }, [dirty]);
}
