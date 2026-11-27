export function StockCounts({ row }: { row: Record<string, unknown> }) {
  return (
    <dl className="stock-facts">
      <div>
        <dt>可售</dt>
        <dd
          className={row.available === 0 ? "inventory-unavailable" : undefined}
          title={row.available === 0 ? "当前无可售库存" : undefined}
        >
          {Number.isSafeInteger(row.available) ? String(row.available) : "—"}
        </dd>
      </div>
      <div>
        <dt>预占</dt>
        <dd>{Number.isSafeInteger(row.held) ? String(row.held) : "—"}</dd>
      </div>
      <div>
        <dt>已售</dt>
        <dd>{Number.isSafeInteger(row.sold) ? String(row.sold) : "—"}</dd>
      </div>
    </dl>
  );
}
