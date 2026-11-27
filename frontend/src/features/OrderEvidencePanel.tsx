import { Alert, Button, Descriptions, Form, Input, Space, Table } from "antd";
import { useContext, useEffect, useRef, useState } from "react";
import { RequestContext } from "../shared/api";
import { ErrorNotice, Status, time } from "../shared/ui";

type Source = "ALL" | "RECEIPTS" | "REFUNDS" | "MARKETING";
type Result<T> = {
  status: "AVAILABLE" | "UNAVAILABLE" | "NOT_LINKED" | "RESTRICTED";
  data?: T;
};
type MoneyRow = { businessNo: string; amountMinor: string; currency: string };
type Receipt = MoneyRow & { disposition: string; confirmedAt: string };
type Refund = MoneyRow & { status: string; createdAt: string };
type Effect = {
  businessNo: string;
  action: string;
  result: string;
  beforeState?: string;
  afterState?: string;
  occurredAt: string;
  amountMinor?: string;
  currency?: string;
};
type Evidence = {
  commerce: Result<{ businessNo: string; storeId: string; createdAt: string }>;
  trade: Result<{
    financialStatus: string;
    receipts: Receipt[];
    refunds: Refund[];
    receiptNextAfter?: string;
    refundNextAfter?: string;
  }>;
  marketing: Result<{
    applicationNo: string;
    campaignId?: string;
    generation: number;
    history: { items: Effect[]; nextCursor?: string };
  }>;
};
const sourceFeedback = {
  UNAVAILABLE: "来源暂不可用",
  NOT_LINKED: "未核实该来源与订单的关联",
  RESTRICTED: "当前账号没有营销证据读取权限",
};
const fundingLabels: Record<string, string> = {
  "funding.confirm": "金额确认",
  "funding.release": "预留释放",
  "funding.refund": "补贴退款",
  "funding.reverse": "补贴冲回",
};

export function OrderEvidencePanel() {
  const client = useContext(RequestContext);
  const [expanded, setExpanded] = useState(false);
  const [orderNo, setOrderNo] = useState("");
  const [evidence, setEvidence] = useState<Evidence>();
  const [loading, setLoading] = useState<Source>();
  const [error, setError] = useState<Error>();
  const inflight = useRef<AbortController | undefined>(undefined);
  const last = useRef<
    { order: string; source: Source; cursor?: string } | undefined
  >(undefined);
  useEffect(() => () => inflight.current?.abort(), []);

  async function fetchEvidence(
    order: string,
    source: Source = "ALL",
    cursor?: string,
  ) {
    inflight.current?.abort();
    const active = new AbortController();
    inflight.current = active;
    last.current = { order, source, cursor };
    setError(undefined);
    setLoading(source);
    if (source === "ALL") setEvidence(undefined);
    const query = new URLSearchParams({ orderNo: order, source });
    if (cursor) query.set("cursor", cursor);
    try {
      const page = await client<Evidence>(
        "/admin/order-operation-evidence?" + query,
        {
          signal: AbortSignal.any([active.signal, AbortSignal.timeout(10000)]),
        },
      );
      if (active.signal.aborted) return;
      if (source !== "ALL" && page.trade.status !== "AVAILABLE") {
        setError(new Error("交易来源暂不可用，请稍后重查当前来源"));
        return;
      }
      setEvidence((prior) => {
        if (!prior || source === "ALL") return page;
        if (source === "MARKETING") {
          return { ...prior, marketing: page.marketing };
        }
        if (!page.trade.data || !prior.trade.data)
          return { ...prior, trade: page.trade };
        const current = page.trade.data;
        return {
          ...prior,
          trade: {
            status: "AVAILABLE",
            data: {
              ...prior.trade.data,
              ...(source === "RECEIPTS"
                ? {
                    receipts: current.receipts,
                    receiptNextAfter: current.receiptNextAfter,
                  }
                : {
                    refunds: current.refunds,
                    refundNextAfter: current.refundNextAfter,
                  }),
            },
          },
        };
      });
    } catch (failure) {
      if (!active.signal.aborted)
        setError(
          failure instanceof Error ? failure : new Error("订单证据查询失败"),
        );
    } finally {
      if (!active.signal.aborted) setLoading(undefined);
    }
  }

  const trade = evidence?.trade.data;
  const commerce = evidence?.commerce.data;
  const marketing = evidence?.marketing.data;
  return (
    <Space orientation="vertical" style={{ width: "100%", marginBottom: 16 }}>
      <Button onClick={() => setExpanded(!expanded)} aria-expanded={expanded}>
        {expanded ? "收起同单证据" : "查询同单证据"}
      </Button>
      {expanded && (
        <>
          <Form layout="inline" onFinish={() => fetchEvidence(orderNo.trim())}>
            <Form.Item label="交易订单号" htmlFor="native-order-evidence">
              <Input
                id="native-order-evidence"
                value={orderNo}
                maxLength={25}
                onChange={(event) => setOrderNo(event.target.value)}
              />
            </Form.Item>
            <Form.Item>
              <Button
                htmlType="submit"
                loading={loading === "ALL"}
                disabled={!/^TRD-ORD-\d{6}-\d{10}$/.test(orderNo.trim())}
              >
                查询
              </Button>
            </Form.Item>
          </Form>
          <ErrorNotice
            error={error}
            onRetry={() => {
              const previous = last.current;
              if (previous)
                void fetchEvidence(
                  previous.order,
                  previous.source,
                  previous.cursor,
                );
            }}
          />
          {evidence && (
            <>
              <Descriptions
                title="商城报价"
                size="small"
                items={
                  commerce
                    ? [
                        {
                          key: "quote",
                          label: "报价编号",
                          children: commerce.businessNo,
                        },
                        {
                          key: "store",
                          label: "店铺",
                          children: commerce.storeId,
                        },
                        {
                          key: "at",
                          label: "报价时间",
                          children: time(commerce.createdAt),
                        },
                      ]
                    : []
                }
              />
              {evidence.commerce.status !== "AVAILABLE" && (
                <Alert title={sourceFeedback[evidence.commerce.status]} />
              )}
              <Descriptions
                title="交易收退"
                size="small"
                items={
                  trade
                    ? [
                        {
                          key: "state",
                          label: "资金状态",
                          children: <Status value={trade.financialStatus} />,
                        },
                      ]
                    : []
                }
              />
              {trade ? (
                <>
                  <Table
                    size="small"
                    rowKey="businessNo"
                    pagination={false}
                    dataSource={trade.receipts}
                    locale={{ emptyText: "没有收款回执" }}
                    columns={[
                      { title: "收款回执", dataIndex: "businessNo" },
                      {
                        title: "金额（分）",
                        render: (_, row) =>
                          row.amountMinor + " " + row.currency,
                      },
                      {
                        title: "核对结果",
                        render: (_, row) => <Status value={row.disposition} />,
                      },
                      {
                        title: "确认时间",
                        render: (_, row) => time(row.confirmedAt),
                      },
                    ]}
                  />
                  {trade.receiptNextAfter && (
                    <Button
                      disabled={!!loading}
                      onClick={() =>
                        last.current &&
                        fetchEvidence(
                          last.current.order,
                          "RECEIPTS",
                          trade.receiptNextAfter,
                        )
                      }
                    >
                      下一页收款
                    </Button>
                  )}
                  <Table
                    size="small"
                    rowKey="businessNo"
                    pagination={false}
                    dataSource={trade.refunds}
                    locale={{ emptyText: "没有退款记录" }}
                    columns={[
                      { title: "退款编号", dataIndex: "businessNo" },
                      {
                        title: "金额（分）",
                        render: (_, row) =>
                          row.amountMinor + " " + row.currency,
                      },
                      {
                        title: "退款状态",
                        render: (_, row) => <Status value={row.status} />,
                      },
                      {
                        title: "申请时间",
                        render: (_, row) => time(row.createdAt),
                      },
                    ]}
                  />
                  {trade.refundNextAfter && (
                    <Button
                      disabled={!!loading}
                      onClick={() =>
                        last.current &&
                        fetchEvidence(
                          last.current.order,
                          "REFUNDS",
                          trade.refundNextAfter,
                        )
                      }
                    >
                      下一页退款
                    </Button>
                  )}
                </>
              ) : (
                <Alert
                  title={
                    evidence.trade.status === "AVAILABLE"
                      ? "缺少交易结果"
                      : sourceFeedback[evidence.trade.status]
                  }
                  action={
                    <Button
                      disabled={!!loading}
                      onClick={() =>
                        last.current && fetchEvidence(last.current.order)
                      }
                    >
                      重查交易
                    </Button>
                  }
                />
              )}
              <Descriptions
                title="营销资金"
                size="small"
                items={
                  marketing
                    ? [
                        {
                          key: "application",
                          label: "资金申请",
                          children: marketing.applicationNo,
                        },
                        {
                          key: "campaign",
                          label: "活动引用",
                          children: marketing.campaignId,
                        },
                        {
                          key: "generation",
                          label: "决策发布代次",
                          children: marketing.generation,
                        },
                      ]
                    : []
                }
              />
              {marketing ? (
                <>
                  <Table
                    size="small"
                    pagination={false}
                    dataSource={marketing.history.items}
                    rowKey={(row) =>
                      row.businessNo + row.occurredAt + row.action + row.result
                    }
                    locale={{ emptyText: "没有资金变动记录" }}
                    columns={[
                      {
                        title: "操作",
                        render: (_, row) =>
                          fundingLabels[row.action] ?? row.action,
                      },
                      {
                        title: "金额（分）",
                        render: (_, row) =>
                          row.amountMinor == null
                            ? "—"
                            : row.amountMinor + " " + row.currency,
                      },
                      {
                        title: "结果",
                        render: (_, row) => (
                          <Status value={row.afterState ?? row.result} />
                        ),
                      },
                      {
                        title: "记录时间",
                        render: (_, row) => time(row.occurredAt),
                      },
                    ]}
                  />
                  {marketing.history.nextCursor && (
                    <Button
                      disabled={!!loading}
                      onClick={() =>
                        last.current &&
                        fetchEvidence(
                          last.current.order,
                          "MARKETING",
                          marketing.history.nextCursor,
                        )
                      }
                    >
                      下一页营销资金
                    </Button>
                  )}
                </>
              ) : (
                evidence.marketing.status !== "AVAILABLE" && (
                  <Alert
                    title={sourceFeedback[evidence.marketing.status]}
                    action={
                      evidence.marketing.status === "UNAVAILABLE" ? (
                        <Button
                          disabled={!!loading}
                          onClick={() =>
                            last.current &&
                            fetchEvidence(last.current.order, "MARKETING")
                          }
                        >
                          重查营销资金
                        </Button>
                      ) : undefined
                    }
                  />
                )
              )}
            </>
          )}
        </>
      )}
    </Space>
  );
}
