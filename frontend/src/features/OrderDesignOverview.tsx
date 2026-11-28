import { Button } from "antd";
import type { Order } from "../shared/contracts";
import { Icon } from "../shared/Icon";
import type { QueuePreset } from "../shared/listFilters";
import { statusText } from "../shared/ui";

const workQueues = [
  { label: "全部订单", purpose: "查找各店铺的订单" },
  { status: "PENDING_PAYMENT", label: "待付款", purpose: "跟进待付款订单" },
  {
    status: "PAYMENT_IN_PROGRESS",
    label: "支付处理中",
    purpose: "核对渠道支付结果",
  },
  {
    status: "CLOSING",
    label: "关单确认中",
    purpose: "核对关单与支付状态",
  },
] as const satisfies readonly {
  status?: string;
  label: string;
  purpose: string;
}[];

const orderStatuses = [
  { status: "PENDING_PAYMENT", tone: "warn" },
  { status: "PAYMENT_IN_PROGRESS", tone: "pending" },
  { status: "CLOSING", tone: "warn" },
  { status: "PAID", tone: "ok" },
  { status: "FULFILLING", tone: "pending" },
  { status: "COMPLETED", tone: "accent" },
  { status: "CANCELLED", tone: "muted" },
] as const;

type QueryChoice = {
  preset: QueuePreset;
  applyPreset: (next: QueuePreset) => void;
};

export function OrderQueueViews({ preset, applyPreset }: QueryChoice) {
  return (
    <div className="design-order-queues" role="group" aria-label="快捷队列">
      {workQueues.map((queue) => {
        const status = "status" in queue ? queue.status : undefined;
        const selected = preset.status === status;
        return (
          <Button
            key={queue.label}
            aria-pressed={selected}
            onClick={() => applyPreset({ ...preset, status })}
          >
            <span>
              <strong>{queue.label}</strong>
              <small>{queue.purpose}</small>
            </span>
            <Icon name="arrow" />
          </Button>
        );
      })}
    </div>
  );
}

export function OrderPageComposition({
  rows,
  preset,
  applyPreset,
}: QueryChoice & { rows: Order[] }) {
  const countByStatus = new Map<string, number>();
  for (const row of rows) {
    countByStatus.set(row.status, (countByStatus.get(row.status) ?? 0) + 1);
  }
  const breakdown = orderStatuses.flatMap(({ status, tone }) => {
    const count = countByStatus.get(status);
    return count ? [{ status, tone, count, label: statusText(status) }] : [];
  });
  const other =
    rows.length - breakdown.reduce((sum, item) => sum + item.count, 0);
  return (
    <section className="design-order-composition" aria-label="本页订单状态分布">
      <div className="design-order-sample">
        <h2>本页订单</h2>
        <strong>{rows.length.toLocaleString("zh-CN")}</strong>
        <span>条</span>
      </div>
      <div className="design-order-breakdown">
        <div className="design-panel-heading">
          <h2>状态分布</h2>
          <span>当前筛选与分页</span>
          {preset.status && (
            <Button
              type="link"
              onClick={() => applyPreset({ ...preset, status: undefined })}
            >
              清除状态
            </Button>
          )}
        </div>
        {rows.length ? (
          <>
            <div className="design-order-distribution" aria-hidden="true">
              {breakdown.map(({ status, count, tone }) => (
                <span
                  key={status}
                  data-tone={tone}
                  style={{ flexGrow: count }}
                />
              ))}
              {other > 0 && (
                <span data-tone="muted" style={{ flexGrow: other }} />
              )}
            </div>
            <div className="design-order-legend">
              {breakdown.map(({ status, tone, count, label }) => (
                <Button
                  key={status}
                  type="text"
                  aria-pressed={preset.status === status}
                  onClick={() =>
                    applyPreset({
                      ...preset,
                      status: preset.status === status ? undefined : status,
                    })
                  }
                >
                  <i data-tone={tone} aria-hidden="true" />
                  {label} <strong>{count}</strong>
                </Button>
              ))}
              {other > 0 && <span>其他状态 {other} 条</span>}
            </div>
          </>
        ) : (
          <p>当前批次无记录</p>
        )}
      </div>
    </section>
  );
}
