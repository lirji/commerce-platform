import { useEffect, useRef, useState } from "react";
import { ApiError, useResource, type request } from "../shared/api";
import { HTTP } from "./api";
import type { DeliveryAction, DeliveryView } from "./couponDeliveryClient";

type KeyedAction = Exclude<DeliveryAction, "pump">;
type Intent = { key: string; path: string; body: unknown; action: KeyedAction };

/** 未知回执不能释放原意图；每次重试先核验当前资格，再发送原目标、正文与键。 */
export function useDeliveryCommand(
  client: typeof request,
  dirty: (value: boolean) => void,
) {
  const intent = useRef<Intent | null>(null);
  const running = useRef(false),
    uncertain = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false);
  const [error, setError] = useState<Error>(),
    [result, setResult] = useState<DeliveryView>();
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
        `/operations/coupon-deliveries/${intent.current?.action ?? action}-access`,
      );
      if (qualification.allowed !== true)
        throw new ApiError(HTTP.FORBIDDEN, "当前未获该发券操作权限");
      if (!intent.current)
        intent.current = {
          path,
          body: structuredClone(body),
          action,
          key: crypto.randomUUID(),
        };
      sent = true;
      const pending = intent.current;
      const value = await client<DeliveryView>(pending.path, {
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
    original:
      frozen && intent.current
        ? { path: intent.current.path, body: intent.current.body }
        : undefined,
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

/** 重新核验立即使旧资格失效，完成新的GET之前不会放开写入。 */
export function useDeliveryQualification(action: DeliveryAction) {
  const access = useResource<{ allowed: boolean }>(
    `/operations/coupon-deliveries/${action}-access`,
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
