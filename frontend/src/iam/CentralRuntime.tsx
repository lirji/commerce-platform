import { formatField, workTypeLabel } from "../shared/ui";
import { CursorBack } from "../shared/pagination";
import { useCursorState } from "../shared/routeState";
import {
  Alert,
  Button,
  Card,
  Form,
  Input,
  Select,
  Space,
  Table,
  Tag,
} from "antd";
import { useState } from "react";
import { useResource } from "../shared/api";
import { ErrorNotice } from "../shared/ui";
import {
  OperationsWorkspace,
  OperationAction,
  OperationsList,
  type Field,
  type OperationRow,
  type OperationsProps,
} from "./OperationsWorkspace";
const replayFields: Field[] = [
  { name: "consumer", label: "原消费者编号", kind: "id" },
  { name: "eventTypes", label: "明确事件类型（逗号分隔，最多20种）" },
  { name: "from", label: "UTC窗口起点", kind: "time" },
  { name: "to", label: "UTC窗口终点", kind: "time" },
  {
    name: "mode",
    label: "重放模式",
    kind: "select",
    options: ["UNPROCESSED", "REPROCESS"],
    initial: "UNPROCESSED",
  },
  {
    name: "maxEvents",
    label: "最多检查事件数",
    kind: "number",
    min: 1,
    max: 10000,
    initial: 100,
  },
];
function replayBody(v: Record<string, unknown>) {
  const eventTypes = String(v.eventTypes)
    .split(",")
    .map((t) => t.trim())
    .filter(Boolean);
  if (!eventTypes.length || eventTypes.length > 20)
    throw new Error("明确事件类型须为1—20种");
  return {
    consumer: v.consumer,
    eventTypes,
    from: new Date(String(v.from) + "Z").toISOString(),
    to: new Date(String(v.to) + "Z").toISOString(),
    mode: v.mode,
    maxEvents: v.maxEvents,
  };
}
function RuntimeRead() {
  const types = useResource<{ workType: string; actions: string[] }[]>(
      "/admin/runtime/work-types",
    ),
    classifications = useResource<OperationRow[]>(
      "/admin/runtime/replay/classifications",
    ),
    [workType, setWorkType] = useState(""),
    [stoppedAfter, setStoppedAfter] = useCursorState(
      "CentralRuntime.stoppedAfter",
      "",
    ),
    [historyAfter, setHistoryAfter] = useCursorState(
      "CentralRuntime.historyAfter",
      0,
    );
  const stopped = useResource<OperationRow[]>(
      workType
        ? `/admin/runtime/stopped?workType=${workType}&after=${encodeURIComponent(stoppedAfter)}&limit=50`
        : null,
    ),
    history = useResource<OperationRow[]>(
      `/admin/runtime/recoveries?after=${historyAfter}&limit=50`,
    );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Card
        title="停止工作与原失败证据"
        extra={
          <Button
            onClick={() => {
              types.refresh();
              stopped.refresh();
              history.refresh();
            }}
          >
            刷新
          </Button>
        }
      >
        <ErrorNotice error={types.error} onRetry={types.refresh} />
        <Select
          aria-label="恢复工作类型"
          style={{ width: "100%", maxWidth: 420 }}
          placeholder="选择需要查看的业务处理类型"
          value={workType || undefined}
          onChange={(value) => {
            setWorkType(value);
            setStoppedAfter("");
          }}
          options={types.data?.map((t) => ({
            value: t.workType,
            label: `${workTypeLabel(t.workType)} · ${t.actions.map((a) => String(formatField("action", a))).join(" / ")}`,
          }))}
        />
        <ErrorNotice error={stopped.error} onRetry={stopped.refresh} />
        <Table<OperationRow>
          rowKey={(r) => String(r.workId)}
          dataSource={stopped.data}
          loading={stopped.loading}
          scroll={{ x: 780 }}
          pagination={false}
          columns={[
            ["workId", "工作编号"],
            ["state", "状态"],
            ["failureClass", "失败分类"],
            ["lastError", "错误摘要"],
            ["attempts", "失败次数"],
          ].map(([dataIndex, title]) => ({
            dataIndex,
            title,
            render: (v) => formatField(dataIndex, v),
          }))}
        />
        <div className="pager-navigation">
          <CursorBack
            name={"CentralRuntime.stoppedAfter"}
            after={stoppedAfter}
            onPrevious={setStoppedAfter}
            initial={""}
            disabled={stopped.loading || !!stopped.error}
            count={stopped.data?.length}
            pageSize={50}
          />
          <Button
            disabled={!stoppedAfter || stopped.loading}
            onClick={() => setStoppedAfter("")}
          >
            返回停止项首页
          </Button>
          <Button
            disabled={
              stopped.loading || !!stopped.error || stopped.data?.length !== 50
            }
            onClick={() =>
              setStoppedAfter(String(stopped.data?.at(-1)?.workId ?? ""))
            }
          >
            下一批停止项
          </Button>
        </div>
        <ErrorNotice error={history.error} onRetry={history.refresh} />
        <Table<OperationRow>
          rowKey={(r) => String(r.id)}
          dataSource={history.data}
          loading={history.loading}
          scroll={{ x: 860 }}
          pagination={false}
          columns={[
            ["workType", "工作类型"],
            ["workId", "原工作"],
            ["action", "恢复动作"],
            ["result", "结果"],
            ["reason", "原原因"],
            ["createdAt", "审计时间"],
          ].map(([dataIndex, title]) => ({
            dataIndex,
            title,
            render: (v) => formatField(dataIndex, v),
          }))}
        />
        <div className="pager-navigation">
          <CursorBack
            name={"CentralRuntime.historyAfter"}
            after={historyAfter}
            onPrevious={setHistoryAfter}
            initial={0}
            disabled={history.loading || !!history.error}
            count={history.data?.length}
            pageSize={50}
          />
          <Button
            disabled={!historyAfter || history.loading}
            onClick={() => setHistoryAfter(0)}
          >
            返回审计首页
          </Button>
          <Button
            disabled={
              history.loading || !!history.error || history.data?.length !== 50
            }
            onClick={() =>
              setHistoryAfter(Number(history.data?.at(-1)?.id ?? 0))
            }
          >
            下一批恢复审计
          </Button>
        </div>
      </Card>
      <Card
        title="消费者真实重放安全门"
        extra={<Button onClick={classifications.refresh}>刷新分类</Button>}
      >
        <ErrorNotice
          error={classifications.error}
          onRetry={classifications.refresh}
        />
        <Table<OperationRow>
          rowKey={(r) => String(r.consumer)}
          dataSource={classifications.data}
          loading={classifications.loading}
          pagination={false}
          scroll={{ x: 780 }}
          columns={[
            { title: "消费者", dataIndex: "consumer" },
            {
              title: "事件类型",
              dataIndex: "types",
              render: (v) => (Array.isArray(v) ? v.join("、") : "—"),
            },
            {
              title: "原副作用声明",
              dataIndex: "effects",
              render: (v) => (Array.isArray(v) ? v.join("、") : "—"),
            },
            {
              title: "证据说明",
              dataIndex: "evidence",
              render: (v) => formatField("evidence", v),
            },
          ]}
        />
      </Card>
      <OperationsList
        path="/admin/runtime/replays"
        idField="jobId"
        columns={[
          ["jobId", "重放任务"],
          ["consumerId", "消费者"],
          ["status", "状态"],
          ["examined", "已检查"],
          ["executed", "已执行"],
          ["failed", "失败"],
        ]}
        fields={[
          ["jobId", "任务编号"],
          ["consumerId", "原消费者"],
          ["eventTypes", "限定类型"],
          ["mode", "重放模式"],
          ["fromAt", "UTC起点"],
          ["toAt", "UTC终点"],
          ["maxEvents", "检查上限"],
          ["status", "任务状态"],
          ["examined", "已检查"],
          ["executed", "已执行"],
          ["failed", "失败"],
          ["version", "控制版本"],
          ["createdBy", "原发起人"],
          ["reason", "原原因"],
        ]}
        detailPath={(id) => `/admin/runtime/replays/${id}`}
      />
    </Space>
  );
}
export default function CentralRuntime(props: OperationsProps) {
  return (
    <OperationsWorkspace
      {...props}
      family="runtime"
      title="运行治理与重放"
      description="恢复原停止工作，重放仅限既有安全门允许的消费者；资金和外部副作用持续拒绝。"
    >
      {(client, dirty) => (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Alert
            type="info"
            showIcon
            title="恢复与历史重放各自独立授权"
            description="有限任务沿用原发起引用，控制或新的员工资格不能为旧任务续期。"
          />
          <Card title="有界运行操作">
            <Space wrap>
              <OperationAction
                client={client}
                dirty={dirty}
                family="runtime"
                action="recover"
                label="恢复指定停止工作"
                fields={[
                  { name: "workType", label: "真实工作类型", kind: "id" },
                  {
                    name: "workIds",
                    label: "明确工作编号（逗号分隔，最多50项）",
                  },
                  {
                    name: "action",
                    label: "恢复动作",
                    kind: "select",
                    options: ["RETRY", "SKIP"],
                    initial: "RETRY",
                  },
                  {
                    name: "expectedFailureClass",
                    label: "期望失败分类（可选）",
                    required: false,
                  },
                  { name: "reason", label: "处置原因" },
                ]}
                path={() => "/admin/runtime/recoveries"}
                body={(v) => {
                  const ids = String(v.workIds)
                    .split(",")
                    .map((i) => i.trim())
                    .filter(Boolean);
                  if (!ids.length || ids.length > 50)
                    throw new Error("工作编号须为1—50项");
                  return {
                    ...v,
                    workIds: ids,
                    expectedFailureClass: v.expectedFailureClass || null,
                  };
                }}
                note="只恢复显式原工作。重试继续原业务责任；SKIP终止剩余效果，已提交效果不会被撤销。"
              />
              <OperationAction
                client={client}
                dirty={dirty}
                family="runtime"
                action="replay-preview"
                label="只读重放试运行"
                readOnly
                fields={replayFields}
                path={() => "/admin/runtime/replay/dry-run"}
                body={replayBody}
                keyed={false}
              />
              <OperationAction
                client={client}
                dirty={dirty}
                family="runtime"
                action="replay-create"
                label="创建有限重放任务"
                fields={[
                  { name: "jobId", label: "新任务编号", kind: "id" },
                  ...replayFields,
                  { name: "reason", label: "发起原因" },
                ]}
                path={() => "/admin/runtime/replays"}
                body={(v) => ({
                  ...replayBody(v),
                  jobId: v.jobId,
                  reason: v.reason,
                })}
                note="授权窗口与原Grant共同限制后台执行；创建受理不代表处理已完成。"
              />
              <OperationAction
                client={client}
                dirty={dirty}
                family="runtime"
                action="replay-control"
                label="控制原重放任务"
                fields={[
                  { name: "jobId", label: "原任务编号", kind: "id" },
                  {
                    name: "action",
                    label: "控制动作",
                    kind: "select",
                    options: ["PAUSE", "RESUME", "CANCEL"],
                    initial: "PAUSE",
                  },
                  {
                    name: "expectedVersion",
                    label: "当前控制版本",
                    kind: "number",
                    min: 0,
                  },
                  { name: "reason", label: "控制原因" },
                ]}
                path={(v) => `/admin/runtime/replays/${v.jobId}/control`}
                body={(v) => ({
                  action: v.action,
                  expectedVersion: v.expectedVersion,
                  reason: v.reason,
                })}
                note="恢复仍核验任务原来源。暂停/取消不会改写原发起员工、Grant或引用期限。"
              />
            </Space>
          </Card>
          <RuntimeRead />
        </Space>
      )}
    </OperationsWorkspace>
  );
}
