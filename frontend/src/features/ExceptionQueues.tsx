import { Button, Card, Space, Typography } from "antd";
import { useContext, useState, type ReactNode } from "react";
import { ApiError, useResource } from "../shared/api";
import {
  CentralLinkScope,
  tradeQuery,
  useWorkspaceHref,
} from "../shared/workspaceLinks";

const queues = [
  { page: "orders", name: "待确认关闭的订单", criteria: { status: "CLOSING" } },
  { page: "fulfillments", name: "售后阻拦的履约", criteria: { blocked: true } },
  {
    page: "aftersales",
    name: "待审批的售后",
    criteria: { status: "REQUESTED" },
  },
  { page: "refunds", name: "结果未知的退款", criteria: { status: "UNKNOWN" } },
] as const;
export type ExceptionFamily = (typeof queues)[number]["page"];
const previewSize = 10;

export function ExceptionQueues({
  store,
  provide,
}: {
  store: string;
  provide?: (family: ExceptionFamily, child: ReactNode) => ReactNode;
}) {
  const central = !!useContext(CentralLinkScope);
  const href = useWorkspaceHref();
  const [revision, setRevision] = useState(0);
  const cards = queues.flatMap(({ page, name, criteria }) => {
    const filter = { ...criteria, storeId: store };
    const url = href(page, tradeQuery(page, central, filter));
    if (!url) return [];
    const query = new URLSearchParams({
      after: "",
      limit: String(previewSize),
    });
    for (const [key, value] of Object.entries(filter))
      query.set(key, String(value));
    const card = (
      <QueueLine name={name} href={url} path={`/admin/${page}?${query}`} />
    );
    return [
      <div key={`${page}:${store}:${revision}`}>
        {provide ? provide(page, card) : card}
      </div>,
    ];
  });
  if (!cards.length) return null;
  return (
    <Card
      title="当前门店异常待办"
      style={{ marginBlock: 16 }}
      extra={
        <Button onClick={() => setRevision((n) => n + 1)}>刷新待办</Button>
      }
    >
      <div className="exception-queues">{cards}</div>
    </Card>
  );
}

function QueueLine({
  name,
  path,
  href,
}: {
  name: string;
  path: string;
  href: string;
}) {
  const { data, error, loading, refresh } = useResource<unknown[]>(path);
  const denied = error instanceof ApiError ? error.status : undefined;
  const count = Array.isArray(data) ? data.length : undefined;
  return (
    <Space wrap className="exception-queue-entry">
      <Typography.Text strong>{name}</Typography.Text>
      {loading || (!data && !error) ? (
        <Typography.Text type="secondary">核对中</Typography.Text>
      ) : denied === 401 || denied === 403 ? (
        <Typography.Text type="secondary">
          {denied === 401 ? "登录已失效" : "当前无队列读取权限"}
        </Typography.Text>
      ) : error || count === undefined ? (
        <Button type="link" onClick={refresh}>
          重试读取{name}
        </Button>
      ) : (
        <a
          href={href}
          aria-label={`查看${name}`}
          className={count ? "queue-link-priority" : undefined}
        >
          {count >= previewSize ? `至少 ${previewSize} 条` : `${count} 条`} ·
          查看队列
        </a>
      )}
    </Space>
  );
}
