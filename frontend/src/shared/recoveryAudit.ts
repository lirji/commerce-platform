import { useCursorState, useRouteState } from "./routeState";

type WorkIdentity = { workType: string; workId: string };
const identifier = /^[A-Za-z0-9_.:-]{1,64}$/;

export function recoveryTarget(
  workType: unknown,
  workId: unknown,
): WorkIdentity | undefined {
  if (
    typeof workType === "string" &&
    typeof workId === "string" &&
    identifier.test(workType) &&
    identifier.test(workId)
  ) {
    return { workType, workId };
  }
}

export function useRecoveryAudit(targetName: string, cursorName: string) {
  const [raw, setRaw] = useRouteState(targetName, "");
  const [after, setAfter] = useCursorState(cursorName, 0);
  let target: WorkIdentity | undefined;
  try {
    const decoded = raw && raw.length <= 180 ? JSON.parse(raw) : undefined;
    target = recoveryTarget(decoded?.workType, decoded?.workId);
  } catch {
    // 无效书签不退化为读取整个租户的历史。
  }
  const invalid = !!raw && !target;
  const query = new URLSearchParams({
    after: String(after),
    limit: "50",
    ...target,
  });
  return {
    target,
    invalid,
    after,
    setAfter,
    path: invalid ? null : `/admin/runtime/recoveries?${query}`,
    select: (value?: WorkIdentity) => {
      setAfter(0);
      setRaw(value ? JSON.stringify(value) : "");
    },
  };
}
