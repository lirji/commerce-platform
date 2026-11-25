import { Space } from "antd";
import { createContext, useContext } from "react";

type Query = Record<string, string>;
type TradePage =
  "orders" | "payments" | "fulfillments" | "aftersales" | "refunds";

export const CentralLinkScope = createContext<{
  tenant: string;
  subject: string;
  routes: ReadonlyMap<string, string>;
} | null>(null);

export function useWorkspaceHref() {
  const central = useContext(CentralLinkScope);
  return (page: string, query: Query = {}) => {
    const params = new URLSearchParams(query);
    if (!central) {
      const store = new URLSearchParams(location.hash.split("?")[1]).get(
        "store",
      );
      if (store && !params.has("store")) params.set("store", store);
      return `#${page}${params.size ? `?${params}` : ""}`;
    }
    const path = `/operations/${page}`;
    if (!central.routes.has(path)) return;
    params.set("tenant_id", central.tenant);
    const environment = new URLSearchParams(location.search).get("environment");
    if (environment) params.set("environment", environment);
    return `${path}?${params}`;
  };
}

export function tradeQuery(
  page: TradePage,
  central: boolean,
  criteria: {
    orderId?: string;
    storeId?: string;
    status?: string;
    blocked?: boolean;
  },
): Query {
  const name = central
    ? `OperationsList./admin/${page}`
    : page === "orders"
      ? "Orders"
      : `AdminData.${page}`;
  return { [`${name}.query`]: JSON.stringify(criteria) };
}

const labels: Record<TradePage, string> = {
  orders: "查看原订单",
  payments: "查看原支付",
  fulfillments: "查看履约",
  aftersales: "查看售后",
  refunds: "查看退款",
};
const businessId = /^[A-Za-z0-9_.:-]{1,64}$/;
const orderEventTypes = new Set([
  "order.created.v1",
  "order.ready.v1",
  "order.closing.v1",
  "order.paid.v1",
  "order.cancelled.v1",
  "order.fulfilling.v1",
  "order.completed.v1",
]);

export function OrderEventLink({
  eventType,
  aggregateId,
}: {
  eventType: unknown;
  aggregateId: unknown;
}) {
  return typeof eventType === "string" && orderEventTypes.has(eventType) ? (
    <TradeLinks orderId={aggregateId} pages={["orders"]} />
  ) : null;
}

export function TradeLinks({
  orderId,
  pages,
  member = false,
}: {
  orderId: unknown;
  pages: readonly TradePage[];
  member?: boolean;
}) {
  const isCentral = !!useContext(CentralLinkScope);
  const href = useWorkspaceHref();
  if (typeof orderId !== "string" || !businessId.test(orderId)) return null;
  const links = pages.flatMap((page) => {
    const query =
      page === "payments"
        ? { orderId }
        : page === "orders" && !isCentral
          ? { order: orderId }
          : page === "aftersales" && member
            ? { "MemberWallet.aftersales.query": JSON.stringify({ orderId }) }
            : tradeQuery(page, isCentral, { orderId });
    const url = href(page, query);
    return url
      ? [
          <a key={page} href={url}>
            {labels[page]}
          </a>,
        ]
      : [];
  });
  return links.length ? (
    <Space wrap aria-label="关联交易">
      {links}
    </Space>
  ) : null;
}
