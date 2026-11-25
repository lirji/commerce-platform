import { useContext, useEffect, useState } from "react";
import type { QueuePreset } from "../shared/listFilters";
import { CentralLinkScope } from "../shared/workspaceLinks";

const coreFields: Record<string, readonly string[]> = {
  "/admin/orders": ["orderId", "storeId", "storeName", "status", "payable"],
  "/admin/fulfillments": ["orderId", "status", "blocked", "trackingNo"],
  "/admin/aftersales": [
    "caseId",
    "orderId",
    "status",
    "refundAmount",
    "returnRequired",
  ],
  "/admin/refunds": ["refundId", "caseId", "orderId", "status", "amount"],
};
type Preferences = { hidden: string[]; preset?: QueuePreset };
type Stored = { scope: string; key: string; value: Preferences };

function load(key: string): Preferences {
  try {
    const stored = JSON.parse(localStorage.getItem(key) ?? "null");
    if (!stored || !Array.isArray(stored.hidden)) return { hidden: [] };
    const hidden = stored.hidden
      .filter(
        (id: unknown) =>
          typeof id === "string" && /^[A-Za-z0-9]{1,40}$/.test(id),
      )
      .slice(0, 30);
    if (!stored.preset || typeof stored.preset !== "object") return { hidden };
    return {
      hidden,
      preset: {
        status:
          typeof stored.preset.status === "string" &&
          /^[A-Z_]{1,40}$/.test(stored.preset.status)
            ? stored.preset.status
            : undefined,
        blocked:
          typeof stored.preset.blocked === "boolean"
            ? stored.preset.blocked
            : undefined,
        limit: [10, 25, 50, 100].includes(stored.preset.limit)
          ? stored.preset.limit
          : 50,
      },
    };
  } catch {
    return { hidden: [] };
  }
}

export function useTradeListPreferences(
  path: string,
  columns: readonly [string, string][],
) {
  const owner = useContext(CentralLinkScope);
  const core = coreFields[path];
  const scope =
    owner && core ? JSON.stringify([owner.tenant, owner.subject, path]) : "";
  const [stored, setStored] = useState<Stored>();
  const [failure, setFailure] = useState("");
  useEffect(() => {
    if (!scope) return;
    let current = true;
    // 只把主体的摘要用于同浏览器的偏好命名，避免在存档中保存账号或业务编号。
    const digest = crypto.subtle?.digest(
      "SHA-256",
      new TextEncoder().encode(scope),
    );
    void digest
      ?.then((buffer) => {
        if (!current) return;
        const key = `commerce.trade-list.v1.${Array.from(new Uint8Array(buffer), (b) => b.toString(16).padStart(2, "0")).join("")}`;
        setStored({ scope, key, value: load(key) });
        setFailure("");
      })
      .catch(() => {
        if (current) setFailure("当前浏览器无法保存偏好");
      });
    return () => {
      current = false;
    };
  }, [scope]);
  const current = stored?.scope === scope ? stored : undefined;
  const optional = core ? columns.filter(([id]) => !core.includes(id)) : [];
  const hidden = current?.value.hidden ?? [];
  return {
    ready: !!current,
    failure: current ? failure : "",
    optional,
    columns: columns.filter(
      ([id]) =>
        !hidden.includes(id) ||
        !optional.some(([candidate]) => id === candidate),
    ),
    preset: current?.value.preset,
    hidden,
    save: (next: Partial<Preferences>) => {
      if (!current) return;
      const value = { ...current.value, ...next };
      try {
        localStorage.setItem(current.key, JSON.stringify(value));
        setStored({ ...current, value });
        setFailure("");
      } catch {
        setFailure("当前浏览器无法保存偏好");
      }
    },
  };
}
