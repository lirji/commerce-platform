import type { Daily, TrendMetric } from "./DailyTrend";
import { money } from "../shared/ui";

const daysPerWindow = 7;
const dayMilliseconds = 86_400_000;
const percentage = new Intl.NumberFormat("zh-CN", {
  style: "percent",
  maximumFractionDigits: 1,
  signDisplay: "exceptZero",
});

function minorUnits(value: string) {
  if (!/^-?\d+\.\d{2}$/.test(value)) return undefined;
  return BigInt(value.replace(".", ""));
}

export function compareProjectedAmounts(left: string, right: string) {
  const a = minorUnits(left);
  const b = minorUnits(right);
  if (a === undefined) return b === undefined ? 0 : 1;
  if (b === undefined) return -1;
  return a === b ? 0 : a < b ? -1 : 1;
}

function amount(value: bigint) {
  const absolute = (value < 0n ? -value : value).toString().padStart(3, "0");
  return money(
    `${value < 0n ? "-" : ""}${absolute.slice(0, -2)}.${absolute.slice(-2)}`,
  );
}

/** 使用当前返回窗口里的连续完整 UTC 日，当日部分时段不参与对比。 */
export function DashboardComparison({
  daily,
  end,
  metric,
}: {
  daily: Daily[];
  end: string;
  metric: TrendMetric;
}) {
  const endDay = Date.parse(end.slice(0, 10));
  if (!Number.isFinite(endDay)) return null;
  const values = new Map(daily.map((day) => [day.day, day[metric]]));
  const dates = Array.from({ length: 2 * daysPerWindow }, (_, index) =>
    new Date(endDay - (2 * daysPerWindow - index) * dayMilliseconds)
      .toISOString()
      .slice(0, 10),
  );
  const amounts = dates.map((date) => {
    const value = values.get(date);
    return value === undefined ? undefined : minorUnits(value);
  });
  if (amounts.some((value) => value === undefined)) return null;
  const sum = (start: number) =>
    amounts
      .slice(start, start + daysPerWindow)
      .reduce<bigint>((total, value) => total + (value ?? 0n), 0n);
  const previous = sum(0);
  const current = sum(daysPerWindow);
  const change = current - previous;
  const changeLabel =
    previous === 0n
      ? current === 0n
        ? "两期均为零"
        : "基期为零"
      : previous < 0n
        ? "基期为负"
        : change === 0n
          ? "持平"
          : percentage.format(Number(change) / Number(previous));
  return (
    <dl className="dashboard-comparison" aria-label="完整七日环比">
      <div>
        <dt>最近 7 个完整 UTC 日</dt>
        <dd>{amount(current)}</dd>
        <dd className="dashboard-comparison-caption">
          {dates[7]} 至 {dates[13]}
        </dd>
      </div>
      <div>
        <dt>前 7 个完整 UTC 日</dt>
        <dd>{amount(previous)}</dd>
        <dd className="dashboard-comparison-caption">
          {dates[0]} 至 {dates[6]}
        </dd>
      </div>
      <div>
        <dt>七日环比</dt>
        <dd>{changeLabel}</dd>
        <dd className="dashboard-comparison-caption">
          差额 {change > 0n ? "+" : ""}
          {amount(change)}
        </dd>
      </div>
    </dl>
  );
}
