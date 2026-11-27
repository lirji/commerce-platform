import { Tag, Tooltip } from "antd";
import { countText, MetricShare } from "./MetricShare";
import { time } from "./ui";

const hour = 3_600_000;
const deadlineWarningHours = 72;

export function DeadlineFact({
  value,
  watch = false,
}: {
  value: unknown;
  watch?: boolean;
}) {
  const timestamp = typeof value === "string" ? Date.parse(value) : NaN;
  const remaining = timestamp - Date.now();
  return (
    <span className="business-deadline">
      <span>{time(value)}</span>
      {watch && remaining > 0 && remaining <= deadlineWarningHours * hour && (
        <Tag color="warning">{deadlineWarningHours} 小时内结束</Tag>
      )}
    </span>
  );
}

export function StockAvailable({ value }: { value: unknown }) {
  if (typeof value !== "number" || !Number.isSafeInteger(value)) return "—";
  return value === 0 ? <Tag color="error">0 · 无可售</Tag> : countText(value);
}

export function AllocationFact({
  held,
  available,
}: {
  held: unknown;
  available: unknown;
}) {
  if (typeof held !== "number" || !Number.isSafeInteger(held)) return "—";
  const hasAllocation =
    held > 0 &&
    typeof available === "number" &&
    Number.isSafeInteger(available) &&
    available >= 0;
  return (
    <div className="allocation-fact">
      {countText(held)}
      {hasAllocation && (
        <Tooltip title="预占 ÷（预占 + 可售）">
          <div>
            <MetricShare
              total={held + available}
              segments={[{ label: "已预占", value: held, tone: "warning" }]}
              caption={`剩余额度中，已预占 ${countText(held)}，仍可售 ${countText(available)}`}
            />
          </div>
        </Tooltip>
      )}
    </div>
  );
}

export function CompensationFact({ value }: { value: unknown }) {
  if (typeof value !== "number" || !Number.isSafeInteger(value)) return "—";
  return value > 0 ? (
    <Tag color="warning">{countText(value)} · 待补偿</Tag>
  ) : (
    countText(value)
  );
}
