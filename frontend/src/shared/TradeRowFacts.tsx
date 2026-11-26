import { money, Status } from "./ui";

export const compactTradeFields = new Set([
  "storeId",
  "storeName",
  "status",
  "payable",
  "refundAmount",
  "amount",
  "blocked",
]);

export function TradeRowFacts({ row }: { row: Record<string, unknown> }) {
  const storeName = typeof row.storeName === "string" ? row.storeName : "";
  const storeId = typeof row.storeId === "string" ? row.storeId : "";
  const value = row.status;
  const status = typeof value === "string" ? value : undefined;
  const amount = row.payable ?? row.refundAmount ?? row.amount;
  return (
    <div className="trade-row-facts">
      {(storeName || storeId) && (
        <div className="trade-row-store">
          <span>门店</span>
          <span>{storeName || storeId}</span>
          {storeName && storeId && storeName !== storeId && (
            <span>{storeId}</span>
          )}
        </div>
      )}
      {(status || row.blocked === true) && (
        <div className="trade-row-state">
          {status && <Status value={status} />}
          {row.blocked === true && <span>售后阻拦</span>}
        </div>
      )}
      {amount != null && (
        <span>
          {row.payable != null ? "应付金额" : "退款金额"} {money(amount)}
        </span>
      )}
    </div>
  );
}
