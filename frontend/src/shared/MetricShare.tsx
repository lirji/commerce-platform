import { Tooltip } from "antd";

const numberFormat = new Intl.NumberFormat("zh-CN");
const percentageFormat = new Intl.NumberFormat("zh-CN", {
  style: "percent",
  maximumFractionDigits: 1,
});

export const countText = (value: number) => numberFormat.format(value);

type Segment = {
  label: string;
  value: number;
  tone: "accent" | "success" | "warning" | "muted";
};

/** 每段均由同一对象、同一窗口的返回值计算；剩余空间保留为分母的其余部分。 */
export function MetricShare({
  total,
  segments,
  caption,
}: {
  total: number;
  segments: Segment[];
  caption: string;
}) {
  if (
    !Number.isFinite(total) ||
    total <= 0 ||
    segments.some(({ value }) => !Number.isFinite(value) || value < 0) ||
    segments.reduce((sum, { value }) => sum + value, 0) / total >
      1 + Number.EPSILON
  )
    return null;
  return (
    <span className="metric-share">
      <span className="metric-share-track" role="img" aria-label={caption}>
        {segments.map(({ label, tone, value }) => (
          <span
            key={label}
            className={`metric-share-${tone}`}
            style={{ flexBasis: `${(value / total) * 100}%` }}
          />
        ))}
      </span>
    </span>
  );
}

export function PaidOrderShare({
  paid,
  orders,
}: {
  paid: unknown;
  orders: unknown;
}) {
  if (
    typeof paid !== "number" ||
    typeof orders !== "number" ||
    !Number.isSafeInteger(paid) ||
    !Number.isSafeInteger(orders) ||
    orders <= 0 ||
    paid < 0 ||
    paid > orders
  )
    return "—";
  const share = percentageFormat.format(paid / orders);
  return (
    <Tooltip
      title={`同一统计范围内 ${countText(paid)} 笔已付 / ${countText(orders)} 笔下单`}
    >
      <span className="metric-share-cell">
        {share}
        <MetricShare
          total={orders}
          segments={[{ label: "已付", tone: "accent", value: paid }]}
          caption={`下单后已付比例 ${share}`}
        />
      </span>
    </Tooltip>
  );
}

export function BudgetUsage({
  cap,
  spent,
  held,
}: {
  cap: unknown;
  spent: unknown;
  held: unknown;
}) {
  if (cap === null) return "未设上限";
  const amount = (value: unknown) =>
    typeof value === "string" && /^\d+(\.\d+)?$/.test(value)
      ? Number(value)
      : NaN;
  const total = amount(cap);
  const consumed = amount(spent);
  const reserved = amount(held);
  if (![total, consumed, reserved].every(Number.isFinite)) return "—";
  if (total === 0) return "上限为零";
  const utilization = (consumed + reserved) / total;
  const caption = `已支出及预占 ${percentageFormat.format(utilization)}`;
  return (
    <span
      className={`metric-share-cell${utilization >= 1 ? " metric-share-exhausted" : ""}`}
    >
      {utilization >= 1 ? "额度已占满" : percentageFormat.format(utilization)}
      <MetricShare
        total={total}
        segments={[
          { label: "已支出", tone: "accent", value: consumed },
          { label: "已预占", tone: "warning", value: reserved },
        ]}
        caption={caption}
      />
    </span>
  );
}

export function CouponIssueFacts({
  processed,
  issued,
  skipped,
}: {
  processed: number;
  issued: number;
  skipped: number;
}) {
  const finished = `已发放 ${countText(issued)} · 已跳过 ${countText(skipped)}`;
  return (
    <div className="coupon-issue-facts">
      <strong>{finished}</strong>
      <span>
        已处理 {countText(processed)}
        {processed > 0 &&
          issued >= 0 &&
          issued <= processed &&
          ` · 发放占比 ${percentageFormat.format(issued / processed)}`}
      </span>
      <MetricShare
        total={processed}
        segments={[
          { label: "已发放", tone: "success", value: issued },
          { label: "已跳过", tone: "warning", value: skipped },
        ]}
        caption={`已处理 ${countText(processed)} 人，其中${finished}`}
      />
    </div>
  );
}
