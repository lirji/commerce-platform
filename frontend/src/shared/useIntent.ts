import { useContext, useRef, useState } from "react";
import { ApiError, RequestContext } from "./api";
import { useEffect } from "react";
const pendingCommands = new Set<object>();
export const hasUnconfirmedCommand = () => pendingCommands.size > 0;

/** 未知写结果冻结原意图，后续拒绝也不能证明此前未生效；只有确认回执才解除。 */
export function useIntent<T>() {
  const client = useContext(RequestContext);
  const intent = useRef<{ path: string; body: unknown; key: string } | null>(
    null,
  );
  const registration = useRef({});
  useEffect(
    () => () => {
      pendingCommands.delete(registration.current);
    },
    [],
  );
  const running = useRef(false);
  const unknown = useRef(false);
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState<Error>();
  const [result, setResult] = useState<T>();
  async function execute(path: string, body: unknown): Promise<T | undefined> {
    if (running.current) return;
    running.current = true;
    // 请求在途同样禁止卸载页面，否则尚未返回的写入会失去原幂等意图。
    pendingCommands.add(registration.current);
    if (!intent.current)
      intent.current = { path, body, key: crypto.randomUUID() };
    setBusy(true);
    setError(undefined);
    setResult(undefined);
    try {
      const command = intent.current;
      const value = await client<T>(command.path, {
        method: "POST",
        body: command.body,
        key: command.key,
      });
      intent.current = null;
      unknown.current = false;
      pendingCommands.delete(registration.current);
      setPending(false);
      setResult(value);
      return value;
    } catch (failure) {
      if (
        unknown.current ||
        !(failure instanceof ApiError) ||
        failure.status >= 500
      ) {
        unknown.current = true;
        pendingCommands.add(registration.current);
        setPending(true);
      } else {
        intent.current = null;
        pendingCommands.delete(registration.current);
      }
      setError(
        failure instanceof Error ? failure : new Error("请求结果待确认"),
      );
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  return {
    busy,
    pending,
    error,
    result,
    execute,
    clearResult: () => setResult(undefined),
  };
}
