import { Button, Empty } from "antd";
import { useRef, useState, type KeyboardEvent } from "react";
import { money } from "../shared/ui";

export type Daily = {
  day: string;
  orders: number;
  paidOrders: number;
  received: string;
  refunded: string;
  netReceipts: string;
  discountGranted: string;
  platformFunding: string;
  merchantFunding: string;
  updatedAt?: string;
};
export type TrendMetric = "netReceipts" | "received" | "refunded";
export const trendLabels: Record<TrendMetric, string> = {
  netReceipts: "净收",
  received: "实收",
  refunded: "退款",
};

/** 金额字符串仍是展示权威；Number 只用于图形坐标，不用于账务计算。 */
export function DailyTrend({
  daily,
  metric,
}: {
  daily: Daily[];
  metric: TrendMetric;
}) {
  const [selectedDay, setSelectedDay] = useState<string>();
  const buttons = useRef<(HTMLButtonElement | null)[]>([]);
  const rememberedIndex = daily.findIndex((day) => day.day === selectedDay);
  const selectedIndex =
    rememberedIndex >= 0 ? rememberedIndex : Math.max(0, daily.length - 1);
  const selected = daily[selectedIndex];
  const values = daily.map((day) => Number(day[metric]));
  const lower = Math.min(0, ...values);
  const highest = Math.max(0, ...values);
  const upper = highest || (lower < 0 ? 0 : 1);
  const extent = upper - lower;
  const zero = (upper / extent) * 100;
  const select = (index: number, focus = false) => {
    setSelectedDay(daily[index]?.day);
    if (focus) buttons.current[index]?.focus();
  };
  const keydown = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const next =
      event.key === "ArrowLeft"
        ? Math.max(0, index - 1)
        : event.key === "ArrowRight"
          ? Math.min(daily.length - 1, index + 1)
          : event.key === "Home"
            ? 0
            : event.key === "End"
              ? daily.length - 1
              : null;
    if (next !== null) {
      event.preventDefault();
      select(next, true);
    }
  };
  if (!daily.length)
    return (
      <Empty
        description="此窗口暂无每日汇总"
        image={Empty.PRESENTED_IMAGE_SIMPLE}
      />
    );
  return (
    <section className="daily-explorer" aria-label="每日成交探索">
      <div className="trend-readout" aria-live="polite" aria-atomic="true">
        <div>
          <span>
            {selected.day} <small>UTC</small>
          </span>
          <strong>
            {money(selected[metric])}
            <small>{trendLabels[metric]}</small>
          </strong>
        </div>
        <div className="trend-day-orders">
          <b>{selected.paidOrders}</b>
          <span>已付订单</span>
        </div>
      </div>
      <div className="trend-scale">
        <span>{money(upper.toFixed(2))}</span>
        <div
          className="trend-plot"
          role="group"
          aria-label={`${trendLabels[metric]}每日柱状图，左右键选择日期`}
        >
          <div
            className="trend-zero"
            style={{ top: `${zero}%` }}
            aria-hidden="true"
          />
          <div className="trend-bars">
            {daily.map((day, index) => {
              const value = values[index];
              return (
                <button
                  key={day.day}
                  ref={(element) => {
                    buttons.current[index] = element;
                  }}
                  type="button"
                  className={`trend-column${index === selectedIndex ? " is-selected" : ""}`}
                  tabIndex={index === selectedIndex ? 0 : -1}
                  aria-pressed={index === selectedIndex}
                  aria-label={`${day.day} UTC · ${trendLabels[metric]} ${money(day[metric])} · ${day.paidOrders}笔已付订单`}
                  onClick={() => select(index)}
                  onFocus={() => select(index)}
                  onKeyDown={(event) => keydown(event, index)}
                >
                  <span
                    className={`trend-bar${value < 0 ? " is-negative" : ""}`}
                    data-value={day[metric]}
                    style={{
                      top: `${value > 0 ? zero - (value / extent) * 100 : zero}%`,
                      height: `${(Math.abs(value) / extent) * 100}%`,
                    }}
                  />
                  <span className="trend-day-mark" />
                </button>
              );
            })}
          </div>
          {!daily.some((day) => day.orders > 0) && (
            <div className="trend-empty">
              <span>此窗口暂无已投影订单</span>
              <small>零值保留，成交后会显示实际趋势</small>
            </div>
          )}
        </div>
        <span>{money(lower.toFixed(2))}</span>
      </div>
      <div className="trend-axis">
        <span>{daily[0]?.day}</span>
        <span>UTC 下单日期</span>
        <span>{daily.at(-1)?.day}</span>
      </div>
      <div className="trend-controls">
        <Button
          type="text"
          aria-label="前一天"
          disabled={selectedIndex === 0}
          onClick={() => select(selectedIndex - 1)}
        >
          ←
        </Button>
        <label className="trend-slider">
          <span className="sr-only">选择趋势日期</span>
          <input
            type="range"
            min={0}
            max={daily.length - 1}
            value={selectedIndex}
            onChange={(event) => select(Number(event.target.value))}
            aria-valuetext={`${selected.day} UTC · ${trendLabels[metric]} ${money(selected[metric])}`}
          />
        </label>
        <Button
          type="text"
          aria-label="后一天"
          disabled={selectedIndex === daily.length - 1}
          onClick={() => select(selectedIndex + 1)}
        >
          →
        </Button>
        <span className="trend-help">拖动或用左右键查看每日数据</span>
      </div>
    </section>
  );
}
