import { useEffect, useRef, useState } from "react";
import { ApiError, useResource, type request } from "../shared/api";
import { HTTP } from "./api";
import type { CampaignAction, CampaignView } from "./campaignClient";

type Intent = {
  key: string;
  path: string;
  body: unknown;
  action: CampaignAction;
};
/** 只有确定失败才释放首次意图；丢响应后即使权限变化，也不换键/目标/载荷。 */
export function useCampaignCommand(
  client: typeof request,
  dirty: (value: boolean) => void,
) {
  const intent = useRef<Intent | null>(null),
    running = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false);
  const [error, setError] = useState<Error>(),
    [result, setResult] = useState<CampaignView>();
  async function run(path: string, body: unknown, action: CampaignAction) {
    if (running.current) return undefined;
    running.current = true;
    setBusy(true);
    dirty(true);
    setError(undefined);
    setResult(undefined);
    let sent = false;
    try {
      const qualification = await client<{ allowed: boolean }>(
        `/operations/campaigns/${intent.current?.action ?? action}-access`,
      );
      if (qualification.allowed !== true)
        throw new ApiError(HTTP.FORBIDDEN, "当前未获该活动操作权限");
      if (!intent.current)
        intent.current = {
          path,
          body: structuredClone(body),
          action,
          key: crypto.randomUUID(),
        };
      sent = true;
      const pending = intent.current;
      const value = await client<CampaignView>(pending.path, {
        method: "POST",
        body: pending.body,
        key: pending.key,
      });
      setResult(value);
      intent.current = null;
      setFrozen(false);
      dirty(false);
      return value;
    } catch (failure) {
      setError(
        failure instanceof Error ? failure : new Error("操作结果尚未确认"),
      );
      if (
        frozen ||
        (sent && (!(failure instanceof ApiError) || failure.status >= 500))
      ) {
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
      if (!running.current) {
        setError(undefined);
        if (!frozen) {
          intent.current = null;
          setResult(undefined);
        }
      }
    },
  };
}

/** 正在刷新期间关闭旧成功提示，只有本轮返回后才重新开放按钮。 */
export function useCampaignQualification(action: CampaignAction) {
  const access = useResource<{ allowed: boolean }>(
    `/operations/campaigns/${action}-access`,
  );
  const [rechecking, setRechecking] = useState(false);
  const observed = useRef(false);
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
