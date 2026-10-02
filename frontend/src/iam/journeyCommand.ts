import { useEffect, useRef, useState } from "react";
import { HTTP } from "./api";
import { ApiError, type request } from "../shared/api";
type Intent = { path: string; body: unknown; hint: string; key: string };
/** 未知结果冻结完整意图，重新发送必须仍是原目标/输入/键，不能误把到期引用续为新任务。 */
export function useJourneyCommand(client: typeof request) {
  const intent = useRef<Intent | null>(null),
    running = useRef(false),
    dirty = useRef(false),
    uncertain = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false),
    [error, setError] = useState<Error>(),
    [result, setResult] = useState<unknown>();
  useEffect(() => {
    const protect = (event: BeforeUnloadEvent) => {
      if (dirty.current) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", protect);
    return () => removeEventListener("beforeunload", protect);
  }, []);
  async function run(path: string, body: unknown, hint: string, keyed = true) {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    let sent = false;
    try {
      const target = intent.current ?? {
        path,
        body: structuredClone(body),
        hint,
        key: crypto.randomUUID(),
      };
      const qualification = await client<{ allowed: boolean }>(target.hint);
      if (qualification.allowed !== true)
        throw new ApiError(403, "当前未获该操作权限");
      if (keyed) {
        intent.current = target;
        dirty.current = true;
      }
      sent = true;
      const value = await client<unknown>(target.path, {
        method: "POST",
        body: target.body,
        key: keyed ? target.key : undefined,
      });
      setResult(value);
      intent.current = null;
      uncertain.current = false;
      setFrozen(false);
      dirty.current = false;
      return value;
    } catch (failure) {
      setError(failure instanceof Error ? failure : new Error("结果尚未确认"));
      if (
        uncertain.current ||
        (keyed &&
          sent &&
          (!(failure instanceof ApiError) || failure.status >= 500))
      ) {
        uncertain.current = true;
        setFrozen(true);
        dirty.current = true;
      } else if (!uncertain.current) {
        intent.current = null;
        dirty.current = false;
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
    clearResult: () => {
      if (!uncertain.current) {
        setResult(undefined);
        setError(undefined);
      }
    },
    markDirty: (value: boolean) => {
      dirty.current = value || uncertain.current;
    },
    retry: () =>
      intent.current
        ? run(intent.current.path, intent.current.body, intent.current.hint)
        : Promise.resolve(undefined),
  };
}
