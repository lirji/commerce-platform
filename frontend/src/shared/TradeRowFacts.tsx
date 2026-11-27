import { formatField, money, Status } from "./ui";

export const compactTradeFields = new Set([
  "storeId",
  "storeName",
  "status",
  "payable",
  "refundAmount",
  "amount",
  "blocked",
  "returnRequired",
  "trackingNo",
  "paymentKind",
]);

export function TradeRowFacts({
  row,
  workflow = true,
}: {
  row: Record<string, unknown>;
  workflow?: boolean;
}) {
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
      {workflow && typeof row.returnRequired === "boolean" && (
        <span>{row.returnRequired ? "需要退货" : "无需退货"}</span>
      )}
      {workflow && typeof row.paymentKind === "string" && (
        <span>支付方式 {formatField("paymentKind", row.paymentKind)}</span>
      )}
      {workflow && typeof row.trackingNo === "string" && row.trackingNo && (
        <span>运单 {row.trackingNo}</span>
      )}
    </div>
  );
}
