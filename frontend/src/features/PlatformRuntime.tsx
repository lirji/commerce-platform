import { Alert, Button, Card, Descriptions, Table, Typography } from "antd";
import { useResource } from "../shared/api";
import {
  ErrorNotice,
  ListPanel,
  PageHead,
  Workbench,
  time,
} from "../shared/ui";
type Rotation = {
  lastRunAt: string;
  completed: number;
  breakerOpen: boolean;
  transientFailures: number;
  otherFailures: number;
  lastRotationMillis: number;
};
type Lane = {
  rotation?: Rotation;
  backlog?: {
    due: number;
    oldestDueAgeSeconds: number | null;
    quarantined: number;
  };
  schedule?: { lastStartLagMillis: number };
};
type View = {
  observedAt: string;
  alerts: string[];
  events: {
    health: {
      pending: number;
      due: number;
      isolated: number;
      oldestDueAgeSeconds: number | null;
      unrouted: number;
    };
    skipped: number;
  };
  lanes: Record<string, Lane>;
  replay: {
    executed: number;
    failed: number;
    blocked: number;
    yielded: number;
  };
  retention: {
    stats: {
      enabled: boolean;
      runs: number;
      failures: number;
      deliveredPurged: number;
      skippedPurged: number;
      inboxPurged: number;
      commandsPurged: number;
      lastRunAt?: string;
    };
    lag: {
      dataClass: string;
      oldestAgeSeconds?: number;
      lagSeconds?: number;
    }[];
  };
};

/** 平台凭据只调用聚合指标接口，不读取任何租户目录或业务载荷。 */
export function PlatformRuntime() {
  const runtime = useResource<View>("/platform/runtime");
  const health = runtime.data?.events.health;
  return (
    <Workbench>
      <PageHead
        eyebrow="平台运维 / 只读"
        title="运行健康"
        description="从事件积压、后台处理和告警信号，判断运行中的系统状态。"
        extra={
          <Button onClick={runtime.refresh} loading={runtime.loading}>
            刷新指标
          </Button>
        }
      />
      <ErrorNotice error={runtime.error} />
      {runtime.data && health && (
        <>
          <div className="runtime-observed">
            观测于 {time(runtime.data.observedAt)} · 聚合指标，不含租户业务数据
          </div>
          <div className="runtime-metrics">
            {[
              ["待投递事件", health.pending],
              ["已到期事件", health.due],
              ["隔离事件", health.isolated],
              [
                "最老到期",
                health.oldestDueAgeSeconds == null
                  ? "—"
                  : `${health.oldestDueAgeSeconds} 秒`,
              ],
            ].map(([label, value]) => (
              <Card key={label}>
                <span className="metric-label">{label}</span>
                <strong>{value}</strong>
              </Card>
            ))}
          </div>
          <Card className="operation-panel" title="当前告警">
            {runtime.data.alerts.length ? (
              runtime.data.alerts.map((code) => (
                <Alert key={code} type="warning" showIcon title={code} />
              ))
            ) : (
              <Typography.Text>
                当前观测未触发告警；持续运行状态需结合后续指标确认。
              </Typography.Text>
            )}
          </Card>
          <ListPanel
            toolbar={
              <Typography.Title level={3}>后台工作车道</Typography.Title>
            }
          >
            <Table
              rowKey="name"
              dataSource={Object.entries(runtime.data.lanes).map(
                ([name, lane]) => ({ name, ...lane }),
              )}
              loading={runtime.loading}
              pagination={false}
              scroll={{ x: 1020 }}
              columns={[
                { title: "工作车道", dataIndex: "name", width: 170 },
                {
                  title: "待处理",
                  align: "right",
                  render: (_, r) => r.backlog?.due ?? "—",
                },
                {
                  title: "最老到期（秒）",
                  align: "right",
                  render: (_, r) => r.backlog?.oldestDueAgeSeconds ?? "—",
                },
                {
                  title: "隔离数",
                  align: "right",
                  render: (_, r) => r.backlog?.quarantined ?? "—",
                },
                {
                  title: "依赖状态",
                  render: (_, r) =>
                    r.rotation?.breakerOpen ? "熔断打开" : "未熔断",
                },
                {
                  title: "最后运行",
                  render: (_, r) => time(r.rotation?.lastRunAt),
                },
                {
                  title: "启动延迟（毫秒）",
                  align: "right",
                  render: (_, r) => r.schedule?.lastStartLagMillis ?? "—",
                },
              ]}
            />
          </ListPanel>
          <Card className="operation-panel" title="重放与保留清理">
            <Descriptions
              column={{ xs: 1, md: 3 }}
              items={[
                {
                  key: "executed",
                  label: "重放已执行",
                  children: runtime.data.replay.executed,
                },
                {
                  key: "failed",
                  label: "重放失败",
                  children: runtime.data.replay.failed,
                },
                {
                  key: "yielded",
                  label: "实时工作让路次数",
                  children: runtime.data.replay.yielded,
                },
                {
                  key: "skipped",
                  label: "已跳过事件",
                  children: runtime.data.events.skipped,
                },
                {
                  key: "unrouted",
                  label: "无消费者事件",
                  children: health.unrouted,
                },
              ]}
            />
            <Typography.Paragraph type="secondary">
              重放计数为当前进程累计观测，积压是读取时快照；不作为业务成交统计。
            </Typography.Paragraph>
          </Card>
          <Card className="operation-panel" title="保留期清理">
            <Descriptions
              column={{ xs: 1, md: 3 }}
              items={[
                {
                  key: "enabled",
                  label: "清理状态",
                  children: runtime.data.retention.stats.enabled
                    ? "已启用"
                    : "未启用",
                },
                {
                  key: "runs",
                  label: "运行次数",
                  children: runtime.data.retention.stats.runs,
                },
                {
                  key: "failures",
                  label: "失败次数",
                  children: runtime.data.retention.stats.failures,
                },
                {
                  key: "delivered",
                  label: "已清理投递事件",
                  children: runtime.data.retention.stats.deliveredPurged,
                },
                {
                  key: "inbox",
                  label: "已清理消费记录",
                  children: runtime.data.retention.stats.inboxPurged,
                },
                {
                  key: "commands",
                  label: "已清理命令回执",
                  children: runtime.data.retention.stats.commandsPurged,
                },
              ]}
            />
            <Table
              rowKey="dataClass"
              size="small"
              pagination={false}
              dataSource={runtime.data.retention.lag}
              columns={[
                { title: "数据类型", dataIndex: "dataClass" },
                { title: "最老数据（秒）", dataIndex: "oldestAgeSeconds" },
                { title: "超过保留期（秒）", dataIndex: "lagSeconds" },
              ]}
            />
          </Card>
        </>
      )}
    </Workbench>
  );
}
