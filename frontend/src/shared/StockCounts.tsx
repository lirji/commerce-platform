export function StockCounts({ row }: { row: Record<string, unknown> }) {
  return (
    <dl className="stock-facts">
      <div>
        <dt>可售</dt>
        <dd>
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
