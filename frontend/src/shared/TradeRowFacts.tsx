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
  const store =
    typeof row.storeName === "string" && row.storeName
      ? row.storeName
      : row.storeId;
  const amount = row.payable ?? row.refundAmount ?? row.amount;
  return (
    <div className="trade-row-facts">
      {typeof store === "string" && store && <span>门店 {store}</span>}
      {(typeof row.status === "string" || row.blocked === true) && (
        <div className="trade-row-state">
          {typeof row.status === "string" && <Status value={row.status} />}
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
