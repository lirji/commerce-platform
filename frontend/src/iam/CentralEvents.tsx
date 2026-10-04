import { Alert, Button, Card, Descriptions, Modal, Space } from "antd";
import { useState } from "react";
import { ApiError, useResource, type request } from "../shared/api";
import { ErrorNotice } from "../shared/ui";
import {
  OperationsWorkspace,
  OperationsList,
  OperationAction,
  type OperationsProps,
  type OperationRow,
} from "./OperationsWorkspace";
function EventHealth() {
  const health = useResource<OperationRow>("/admin/events/health");
  return (
    <Card
      title="本租户事件积压"
      extra={
        <Button onClick={health.refresh} loading={health.loading}>
          刷新诊断
        </Button>
      }
    >
      <ErrorNotice error={health.error} onRetry={health.refresh} />
      {health.data && (
        <Descriptions
          column={{ xs: 1, md: 3 }}
          items={[
            ["pending", "待投递"],
            ["due", "已到期"],
            ["retrying", "重试中"],
            ["isolated", "隔离"],
            ["oldestDueAgeSeconds", "最老到期秒数"],
            ["unrouted", "未路由事件"],
          ].map(([key, label]) => ({
            key,
            label,
            children: String(health.data?.[key] ?? "—"),
          }))}
        />
      )}
    </Card>
  );
}
/** pump 没有幂等回执；失响应后原调用仍未知，下一次必须明确确认为新的有界推进。 */
function EventPump({
  client,
  dirty,
}: {
  client: typeof request;
  dirty: (k: string, v: boolean) => void;
}) {
  const access = useResource<{ allowed: boolean }>(
      "/operations/events/pump-access",
    ),
    [open, setOpen] = useState(false),
    [busy, setBusy] = useState(false),
    [unknown, setUnknown] = useState(false),
    [error, setError] = useState<Error>(),
    [count, setCount] = useState<number>();
  async function pump() {
    if (busy) return;
    setBusy(true);
    dirty("pump", true);
    setError(undefined);
    setCount(undefined);
    let sent = false;
    try {
      await client("/operations/events/pump-access");
      sent = true;
      const value = await client<number>("/admin/events/pump", {
        method: "POST",
      });
      setCount(value);
      if (!unknown) dirty("pump", false);
    } catch (e) {
      setError(e instanceof Error ? e : new Error("推进失败"));
      if (sent && (!(e instanceof ApiError) || e.status >= 500)) {
        setUnknown(true);
        dirty("pump", true);
      } else if (!unknown) dirty("pump", false);
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <Space wrap>
        <Button
          disabled={
            access.loading || !!access.error || access.data?.allowed !== true
          }
          onClick={() => setOpen(true)}
        >
          推进一次事件批次
        </Button>
        <Button onClick={access.refresh}>核验推进资格</Button>
      </Space>
      <ErrorNotice error={access.error} onRetry={access.refresh} />
      <Modal
        className="operations-modal"
        open={open}
        centered
        title="推进当前租户事件"
        maskClosable={false}
        keyboard={!busy && !unknown}
        closable={!busy && !unknown}
        onCancel={() => {
          if (!busy && !unknown) setOpen(false);
        }}
        styles={{ body: { maxHeight: "66vh", overflowY: "auto" } }}
        footer={
          <Space wrap>
            <Button disabled={busy || unknown} onClick={() => setOpen(false)}>
              关闭
            </Button>
            <Button loading={busy} type="primary" onClick={() => void pump()}>
              {unknown ? "确认新的有界推进" : "确认推进一次"}
            </Button>
          </Space>
        }
      >
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Alert
            type="info"
            title="每次调用只推进当前租户的有界批次"
            description="消费者原Inbox保证实际效果不会重复；本操作没有客户端幂等回执。"
          />
          {unknown && (
            <Alert
              type="warning"
              showIcon
              title="之前的调用结果仍未知"
              description="原调用可能已部分或全部执行。下方操作是一次新的推进，不是确认原结果；请保留页面并由实际事件及审计核对。"
            />
          )}
          <ErrorNotice error={error} />
          {count != null && (
            <Alert type="success" title={`本次新调用确认处理 ${count} 项`} />
          )}
        </Space>
      </Modal>
    </>
  );
}
export default function CentralEvents(props: OperationsProps) {
  return (
    <OperationsWorkspace
      {...props}
      family="events"
      title="事件投递"
      description="按当前租户推进和重试原消费者，保留失败证据与原资金、履约责任。"
    >
      {(client, dirty) => (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Card title="独立事件操作">
            <Space wrap>
              <EventPump client={client} dirty={dirty} />
              <OperationAction
                client={client}
                dirty={dirty}
                family="events"
                action="retry"
                label="重试停止事件"
                fields={[{ name: "eventId", label: "原事件编号", kind: "id" }]}
                path={(v) => `/admin/events/${v.eventId}/retry`}
                note="只恢复原事件未成功的消费者；已成功的Inbox不清除，原失败证据保留。"
              />
            </Space>
          </Card>
          <EventHealth />
          <OperationsList
            path="/admin/events"
            idField="eventId"
            columns={[
              ["eventId", "事件"],
              ["eventType", "类型"],
              ["status", "状态"],
              ["attempts", "失败次数"],
            ]}
            fields={[
              ["eventId", "事件编号"],
              ["eventType", "事件类型"],
              ["aggregateId", "业务对象"],
              ["status", "投递状态"],
              ["attempts", "业务失败次数"],
              ["transientAttempts", "瞬时失败次数"],
              ["failureClass", "失败分类"],
              ["lastError", "最近错误摘要"],
              ["skipReason", "跳过原因"],
              ["manualRetries", "人工重试次数"],
              ["availableAt", "下次可推进时间"],
            ]}
          />
        </Space>
      )}
    </OperationsWorkspace>
  );
}
