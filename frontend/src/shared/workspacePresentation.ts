import { createContext } from "react";

type WorkspaceKind = "analysis" | "queue" | "directory" | "configuration";

// 按主要作业选择排版；旧入口与中央入口的不同名称沿用各自的菜单契约。
const analysis = new Set([
  "dashboard",
  "effects",
  "marketing-effects",
  "platform-runtime",
]);
const queues = new Set([
  "orders",
  "fulfillments",
  "aftersales",
  "refunds",
  "payments",
  "entitlements",
  "coupon-deliveries",
  "instances",
  "journey-instances",
  "journey-scans",
  "marketing-executions",
  "events",
  "recovery",
  "replay",
  "runtime",
  "platform-operation-audits",
]);
const configuration = new Set([
  "growth",
  "member-growth",
  "member-cycle-benefits",
  "segments",
  "campaigns",
  "rules",
  "journeys",
  "budgets",
  "campaign-budgets",
  "coupons",
  "coupon-definitions",
  "definitions",
  "entitlement-definitions",
  "store-grants",
  "pages",
  "ops-pages",
  "point-offers",
]);

export function workspaceKind(page: string): WorkspaceKind {
  const name = page.split("/").at(-1) ?? page;
  if (analysis.has(name)) return "analysis";
  if (queues.has(name)) return "queue";
  if (configuration.has(name)) return "configuration";
  return "directory";
}

export const WorkspacePresentation = createContext<WorkspaceKind | undefined>(
  undefined,
);
