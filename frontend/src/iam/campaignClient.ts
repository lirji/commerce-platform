import { listGuardPath } from "../shared/listFilters";
import { ApiError, type request } from "../shared/api";
import type { Campaign } from "../shared/contracts";
import { CentralError, HTTP, type Context } from "./api";

export const CAMPAIGN_PAGE_SIZE = 50;
export const campaignIdentifier = /^[A-Za-z0-9_.:-]{1,64}$/;
export const campaignActions = [
  "create",
  "preview",
  "submit",
  "approve",
  "reject",
  "publish",
  "pause",
] as const;
export type CampaignAction = (typeof campaignActions)[number];
export type CampaignDraft = Omit<Campaign, "policy"> & {
  policy?: Omit<NonNullable<Campaign["policy"]>, "terms"> & {
    terms?: NonNullable<NonNullable<Campaign["policy"]>["terms"]> & {
      coupon?: { definitionId: string; version: number };
    };
  };
};
const campaignStatuses = new Set([
  "DRAFT",
  "IN_REVIEW",
  "APPROVED",
  "REJECTED",
  "PUBLISHED",
  "PAUSED",
]);

export type CampaignView = {
  content: CampaignDraft;
  merchantId: string;
  status: string;
  lockVersion: number;
};
export type CampaignBudget = {
  budgetId: string;
  campaignId: string;
  version: number;
  cap: string | null;
  held: string;
  spent: string;
};
export type CampaignPreview = {
  memberId: string;
  at?: string;
  items: { skuId: string; quantity: number }[];
  includePublishedCompetition: boolean;
};
export type CampaignPreviewResult = {
  gross: string;
  discount: string;
  payable: string;
  lines: { skuId: string; gross: string; discount: string; payable: string }[];
  trace: { campaignId: string; version: number; reason: string }[];
  sources: {
    audienceId: string;
    version: number;
    source: string;
    watermark: string;
    validUntil: string;
    match: string;
  }[];
  notice: string;
  selected: { campaignId: string; version: number } | null;
};

/** 只接受本片精确方法、路径和有界游标，不发送中央凭据到任意旧管理入口。 */
export function campaignClient(
  context: Context,
  expired: () => void,
): typeof request {
  return async <T>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    const guardedPath = listGuardPath(path);
    let directory = false;
    for (const base of ["/admin/campaigns", "/admin/campaign-budgets"]) {
      if (!path.startsWith(`${base}?`)) continue;
      const query = new URLSearchParams(
        guardedPath.slice(guardedPath.indexOf("?") + 1),
      );
      directory =
        Array.from(query.keys()).length === 2 &&
        query.has("after") &&
        query.get("limit") === String(CAMPAIGN_PAGE_SIZE) &&
        (query.get("after") === "" ||
          campaignIdentifier.test(query.get("after") ?? ""));
    }
    let target = false;
    try {
      const match =
        /^\/admin\/campaigns\/([^/]+)\/([1-9][0-9]*)\/(preview|submit|approve|reject|publish|pause)$/.exec(
          path,
        );
      target =
        !!match &&
        campaignIdentifier.test(decodeURIComponent(match[1])) &&
        Number.isSafeInteger(Number(match[2])) &&
        Number(match[2]) > 0;
    } catch {
      /* 畸形编码同样拒绝，不尝试旧身份回退。 */
    }
    const hint = campaignActions.some(
      (action) => path === `/operations/campaigns/${action}-access`,
    );
    if (!(
      (method === "GET" && (directory || hint)) ||
      (method === "POST" && (path === "/admin/campaigns" || target))
    ))
      throw new ApiError(HTTP.FORBIDDEN, "此入口不支持该操作");
    const headers: Record<string, string> = {
      Authorization: `Bearer ${context.token}`,
      "X-Tenant-Id": context.tenant,
    };
    if (options.body !== undefined)
      headers["Content-Type"] = "application/json";
    if (options.key) headers["Idempotency-Key"] = options.key;
    const response = await fetch(`/v1${path}`, {
      method,
      headers,
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal ?? AbortSignal.timeout(15000),
    }).catch(() => {
      throw new Error("服务连接中断，写入结果尚未确认，请保留原意图重试");
    });
    if (response.status === HTTP.UNAUTHORIZED) expired();
    if (!response.ok)
      throw new ApiError(
        response.status,
        new CentralError(response.status).message,
      );
    const result = await response.json();
    // 不能把成功状态下的空/损坏回执当成写入完成，否则原幂等意图会被错误清除。
    if (
      method === "POST" &&
      !path.endsWith("/preview") &&
      (!result?.content ||
        !campaignIdentifier.test(result.content.campaignId ?? "") ||
        !Number.isSafeInteger(result.content.version) ||
        result.content.version <= 0 ||
        !Number.isSafeInteger(result.lockVersion) ||
        result.lockVersion < 0 ||
        !campaignStatuses.has(result.status))
    )
      throw new Error("服务未返回可确认的活动回执，请保留原意图重试");
    return result as T;
  };
}
