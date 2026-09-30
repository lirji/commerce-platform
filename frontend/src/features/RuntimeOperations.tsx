import {
  Alert,
  Button,
  Card,
  Checkbox,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Table,
  Tag,
  Tabs,
  Typography,
} from "antd";
import { useEffect, useState } from "react";
import { encode, request, useResource } from "../shared/api";
import { useIntent } from "../shared/useIntent";
import { useRouteState } from "../shared/routeState";
import {
  ErrorNotice,
  ListPanel,
  PageHead,
  RecordDrawer,
  RowActions,
  Status,
  Workbench,
  instant,
  time,
} from "../shared/ui";

type WorkType = { workType: string; actions: string[] };
type Stopped = {
  workType: string;
  workId: string;
  state: string;
  failureClass: string;
  lastError: string;
  attempts: number;
  transientAttempts: number;
  firstFailedAt: string;
  lastFailedAt: string;
  manualRecoveries: number;
};
type Outcome = {
  workId: string;
  result: string;
  previousState: string;
  newState: string;
  rejection?: string;
};
type RecoveryResult = {
  workType: string;
  action: string;
  applied: number;
  rejected: number;
  outcomes: Outcome[];
};
type Audit = Outcome & {
  id: number;
  actorId: string;
  action: string;
  workType: string;
  reason: string;
  createdAt: string;
};
type Gate = { allowed: boolean; code: string; detail: string };
function GateDecision({ gate }: { gate: Gate }) {
  return (
    <div>
      <Tag
        className="runtime-gate-status"
        style={{
          color: gate.allowed ? "var(--ok)" : "var(--error)",
          background: gate.allowed ? "var(--okSoft)" : "var(--errorSoft)",
          borderColor: "transparent",
        }}
      >
        {gate.allowed ? "允许" : "禁止"}
      </Tag>
      {!gate.allowed && (
        <Typography.Paragraph
          type="secondary"
          style={{ margin: "8px 0 0" }}
          ellipsis={{
            rows: 2,
            expandable: "collapsible",
            symbol: (expanded) => (expanded ? "收起原因" : "完整原因"),
          }}
        >
          {gate.detail}
        </Typography.Paragraph>
      )}
    </div>
  );
}
type Classification = {
  consumer: string;
  types: string[];
  effects: string[];
  evidence?: string;
  unprocessed: Gate;
  reprocess: Gate;
};
type Scope = {
  consumer: string;
  eventTypes: string[];
  from: string;
  to: string;
  mode: string;
  maxEvents: number;
};
type ReplayInput = Scope & { jobId: string; reason: string };
type DryRun = {
  consumer: string;
  mode: string;
  gate: Gate;
  events: number;
  alreadyProcessed: number;
  wouldExecute: number;
  capped: boolean;
  maxEvents: number;
  byType: { eventType: string; events: number; processed: number }[];
};
type Job = {
  jobId: string;
  consumerId: string;
  eventTypes: string;
  mode: string;
  status: string;
  examined: number;
  executed: number;
  alreadyProcessed: number;
  failed: number;
  maxEvents: number;
  reason: string;
  lastError?: string;
  version: number;
  fromAt: string;
  toAt: string;
  createdAt: string;
};
const BASE = "/admin/runtime";
const actionLabels: Record<string, string> = {
  RETRY: "恢复执行",
  SKIP: "终止剩余处理",
  PAUSE: "暂停任务",
  RESUME: "恢复任务",
  CANCEL: "取消任务",
};
const failureLabels: Record<string, string> = {
  TRANSIENT: "瞬时故障",
  DEPENDENCY_UNAVAILABLE: "依赖不可用",
  CONCURRENCY_RETRYABLE: "并发可重试",
  BUSINESS_REJECTED: "业务拒绝",
  DATA_CORRUPTION: "数据异常",
  CONFIGURATION_ERROR: "配置异常",
  UNKNOWN: "未分类故障",
  PERMANENT: "不可恢复故障",
};

function PendingNotice({ pending }: { pending: boolean }) {
  return pending ? (
    <Alert
      type="warning"
      showIcon
      title="结果待确认，原操作已保留"
      description="输入暂时锁定。请原样重试查询同一命令的结果；离开或刷新前先确认，避免重复操作。"
    />
  ) : null;
}

/** 租户恢复与平台聚合指标分开：此页只请求当前管理员的租户接口。 */
export function RuntimeOperations({ mode }: { mode: "recovery" | "replay" }) {
  return mode === "recovery" ? <Recovery /> : <Replay />;
}

function Recovery() {
  const [tab, setTab] = useRouteState("tab", "stopped");
  const [workType, setWorkType] = useRouteState("workType", "");
  const [failureClass, setFailureClass] = useRouteState("failureClass", "");
  const [after, setAfter] = useRouteState("after", "");
  const [auditAfter, setAuditAfter] = useRouteState("auditAfter", 0);
  const [selected, setSelected] = useState<string[]>([]);
  const [detail, setDetail] = useState<Stopped>();
  const [form] = Form.useForm<{ action: string; reason: string }>();
  const types = useResource<WorkType[]>(BASE + "/work-types");
  const rows = useResource<Stopped[]>(
    workType
      ? `${BASE}/stopped?workType=${encode(workType)}&failureClass=${encode(failureClass)}&after=${encode(after)}&limit=50`
      : null,
  );
  const history = useResource<Audit[]>(
    tab === "history"
      ? `${BASE}/recoveries?after=${auditAfter}&limit=50`
      : null,
  );
  const command = useIntent<RecoveryResult>();
  useEffect(() => {
    if (!workType && types.data?.length) setWorkType(types.data[0].workType);
  }, [types.data, workType]);
  const allowed =
    types.data?.find((t) => t.workType === workType)?.actions ?? [];
  const refresh = () => {
    types.refresh();
    rows.refresh();
    history.refresh();
    if (!command.pending) setSelected([]);
  };
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (command.pending || command.busy) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", warn);
    return () => removeEventListener("beforeunload", warn);
  }, [command.pending, command.busy]);
  return (
    <Workbench>
      <PageHead
        eyebrow="平台工具 / 本租户"
        title="任务恢复"
        description="查看失败证据，明确恢复范围，逐项跟进处理结果。"
        extra={<Button onClick={refresh}>刷新工作</Button>}
      />
      <ErrorNotice error={types.error} />
      <ErrorNotice error={rows.error} />
      <Tabs
        activeKey={tab}
        onChange={(next) => {
          if (!command.pending && !command.busy) setTab(next);
        }}
        items={[
          {
            key: "stopped",
            label: "停止的工作",
            children: (
              <>
                <ListPanel
                  toolbar={
                    <Space wrap>
                      <div className="filter-field">
                        <label htmlFor="recovery-work-type">工作类型</label>
                        <Select
                          id="recovery-work-type"
                          aria-label="工作类型"
                          value={workType || undefined}
                          disabled={command.pending || command.busy}
                          style={{ width: 250 }}
                          options={types.data?.map((t) => ({
                            value: t.workType,
                            label: t.workType,
                          }))}
                          onChange={(v) => {
                            setWorkType(v);
                            setAfter("");
                            setSelected([]);
                            form.resetFields();
                          }}
                        />
                      </div>
                      <div className="filter-field">
                        <label htmlFor="recovery-failure">失败分类</label>
                        <Select
                          id="recovery-failure"
                          aria-label="失败分类"
                          style={{ width: 200 }}
                          value={failureClass}
                          disabled={command.pending || command.busy}
                          options={[
                            { value: "", label: "全部分类" },
                            ...Object.entries(failureLabels).map(
                              ([value, label]) => ({ value, label }),
                            ),
                          ]}
                          onChange={(v) => {
                            setFailureClass(v);
                            setAfter("");
                            setSelected([]);
                          }}
                        />
                      </div>
                    </Space>
                  }
                  count={rows.data?.length ?? 0}
                  after={after}
                  onHome={() => {
                    setAfter("");
                    setSelected([]);
                  }}
                  onNext={() => {
                    setAfter(rows.data!.at(-1)!.workId);
                    setSelected([]);
                  }}
                >
                  <Table<Stopped>
                    rowKey="workId"
                    loading={rows.loading}
                    dataSource={rows.data}
                    pagination={false}
                    scroll={{ x: 960 }}
                    rowSelection={{
                      selectedRowKeys: selected,
                      onChange: (keys) => setSelected(keys.map(String)),
                      getCheckboxProps: () => ({
                        disabled: command.pending || command.busy,
                      }),
                    }}
                    columns={[
                      {
                        title: "工作标识",
                        dataIndex: "workId",
                        ellipsis: true,
                        width: 240,
                      },
                      {
                        title: "状态",
                        dataIndex: "state",
                        render: (v) => <Status value={v} />,
                      },
                      {
                        title: "失败分类",
                        dataIndex: "failureClass",
                        render: (v: string) => failureLabels[v] ?? v,
                      },
                      {
                        title: "失败次数",
                        dataIndex: "attempts",
                        align: "right",
                      },
                      {
                        title: "最后失败",
                        dataIndex: "lastFailedAt",
                        render: time,
                        width: 180,
                      },
                      {
                        title: "操作",
                        width: 100,
                        render: (_, r) => (
                          <Button type="link" onClick={() => setDetail(r)}>
                            查看证据
                          </Button>
                        ),
                      },
                    ]}
                  />
                </ListPanel>
                <Card
                  className="operation-panel"
                  title={`恢复操作 · 已选 ${selected.length} 项`}
                >
                  <PendingNotice pending={command.pending} />
                  <ErrorNotice error={command.error} />
                  <Form
                    name="runtime-recovery"
                    form={form}
                    layout="vertical"
                    disabled={command.pending || command.busy}
                    onFinish={async (v) => {
                      const result = await command.execute(
                        BASE + "/recoveries",
                        {
                          workType,
                          workIds: selected,
                          action: v.action,
                          expectedFailureClass: failureClass || null,
                          reason: v.reason.trim(),
                        },
                      );
                      if (result) {
                        refresh();
                        form.resetFields();
                      }
                    }}
                  >
                    <div className="command-fields">
                      <Form.Item
                        name="action"
                        label="处理方式"
                        rules={[{ required: true, message: "请选择处理方式" }]}
                      >
                        <Select
                          options={allowed.map((a) => ({
                            value: a,
                            label: actionLabels[a] ?? a,
                          }))}
                        />
                      </Form.Item>
                      <Form.Item
                        name="reason"
                        label="处理原因"
                        rules={[
                          {
                            required: true,
                            whitespace: true,
                            message: "请输入处理原因",
                          },
                          { max: 256 },
                        ]}
                      >
                        <Input.TextArea rows={2} />
                      </Form.Item>
                    </div>
                    <Typography.Paragraph type="secondary">
                      恢复执行只继续尚未完成的工作；终止剩余处理不会撤销已发生的效果。每次最多选择50项。
                    </Typography.Paragraph>
                    {!command.pending && (
                      <Button
                        type="primary"
                        htmlType="submit"
                        loading={command.busy}
                        disabled={
                          !selected.length ||
                          selected.length > 50 ||
                          !allowed.length
                        }
                      >
                        确认恢复操作
                      </Button>
                    )}
                  </Form>
                  {command.pending && (
                    <Button
                      type="primary"
                      loading={command.busy}
                      onClick={() => void command.execute("", null)}
                    >
                      原样重试恢复
                    </Button>
                  )}
                  {command.result && (
                    <section className="command-result" aria-live="polite">
                      <Alert
                        showIcon
                        type={command.result.rejected ? "warning" : "success"}
                        title={`已应用 ${command.result.applied} 项，拒绝 ${command.result.rejected} 项`}
                      />
                      <Table<Outcome>
                        rowKey="workId"
                        size="small"
                        pagination={false}
                        dataSource={command.result.outcomes}
                        scroll={{ x: 680 }}
                        columns={[
                          { title: "工作", dataIndex: "workId" },
                          { title: "结果", dataIndex: "result" },
                          { title: "新状态", dataIndex: "newState" },
                          { title: "拒绝原因", dataIndex: "rejection" },
                        ]}
                      />
                    </section>
                  )}
                </Card>
              </>
            ),
          },
          {
            key: "history",
            label: "恢复审计",
            children: (
              <>
                <ErrorNotice error={history.error} />
                <ListPanel
                  count={history.data?.length ?? 0}
                  after={auditAfter}
                  onHome={() => setAuditAfter(0)}
                  onNext={() => setAuditAfter(history.data!.at(-1)!.id)}
                >
                  <Table<Audit>
                    rowKey="id"
                    dataSource={history.data}
                    loading={history.loading}
                    pagination={false}
                    scroll={{ x: 1080 }}
                    columns={[
                      { title: "工作类型", dataIndex: "workType" },
                      { title: "工作标识", dataIndex: "workId" },
                      { title: "操作者", dataIndex: "actorId" },
                      {
                        title: "动作",
                        dataIndex: "action",
                        render: (v: string) => actionLabels[v] ?? v,
                      },
                      { title: "结果", dataIndex: "result" },
                      { title: "原因", dataIndex: "reason" },
                      { title: "时间", dataIndex: "createdAt", render: time },
                    ]}
                  />
                </ListPanel>
              </>
            ),
          },
        ]}
      />
      <RecordDrawer
        title="失败证据"
        open={!!detail}
        onClose={() => setDetail(undefined)}
      >
        {detail && (
          <Descriptions
            column={1}
            bordered
            items={[
              {
                key: "id",
                label: "工作标识",
                children: (
                  <Typography.Text copyable>{detail.workId}</Typography.Text>
                ),
              },
              {
                key: "state",
                label: "当前状态",
                children: <Status value={detail.state} />,
              },
              {
                key: "failure",
                label: "失败分类",
                children: detail.failureClass,
              },
              {
                key: "error",
                label: "最后错误",
                children: detail.lastError || "未记录",
              },
              {
                key: "attempts",
                label: "累计失败 / 瞬时失败",
                children: `${detail.attempts} / ${detail.transientAttempts}`,
              },
              {
                key: "first",
                label: "首次失败",
                children: time(detail.firstFailedAt),
              },
              {
                key: "last",
                label: "最后失败",
                children: time(detail.lastFailedAt),
              },
              {
                key: "manual",
                label: "人工恢复次数",
                children: detail.manualRecoveries,
              },
            ]}
          />
        )}
      </RecordDrawer>
    </Workbench>
  );
}

function Replay() {
  const [tab, setTab] = useRouteState("tab", "jobs");
  const [after, setAfter] = useRouteState("after", "");
  const [selected, setSelected] = useState<string>();
  const jobs = useResource<Job[]>(
    `${BASE}/replays?after=${encode(after)}&limit=50`,
  );
  const detail = useResource<Job>(
    selected ? `${BASE}/replays/${encode(selected)}` : null,
  );
  const classifications = useResource<Classification[]>(
    BASE + "/replay/classifications",
  );
  const [form] = Form.useForm<ReplayInput>();
  const consumer = Form.useWatch("consumer", form);
  const classification = classifications.data?.find(
    (c) => c.consumer === consumer,
  );
  const [preview, setPreview] = useState<{ scope: Scope; result: DryRun }>();
  const [previewBusy, setPreviewBusy] = useState(false);
  const [previewError, setPreviewError] = useState<Error>();
  const create = useIntent<Job>();
  const control = useIntent<Job>();
  const [controlForm] = Form.useForm<{ action: string; reason: string }>();
  const [acknowledged, setAcknowledged] = useState(false);
  const locked = create.pending || create.busy;
  useEffect(() => {
    if (create.pending) setTab("create");
  }, [create.pending]);
  const refresh = () => {
    jobs.refresh();
    detail.refresh();
  };
  function scope(value: ReplayInput): Scope {
    return {
      consumer: value.consumer,
      eventTypes: value.eventTypes,
      from: instant(value.from),
      to: instant(value.to),
      mode: value.mode,
      maxEvents: value.maxEvents,
    };
  }
  async function dryRun() {
    try {
      const value = await form.validateFields([
        "consumer",
        "eventTypes",
        "from",
        "to",
        "mode",
        "maxEvents",
      ]);
      setPreviewBusy(true);
      setPreview(undefined);
      setPreviewError(undefined);
      setAcknowledged(false);
      const input = scope(value as ReplayInput);
      const result = await request<DryRun>(BASE + "/replay/dry-run", {
        method: "POST",
        body: input,
      });
      setPreview({ scope: input, result });
    } catch (error) {
      if (error instanceof Error) setPreviewError(error);
    } finally {
      setPreviewBusy(false);
    }
  }
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (create.pending || create.busy || control.pending || control.busy) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", warn);
    return () => removeEventListener("beforeunload", warn);
  }, [create.pending, create.busy, control.pending, control.busy]);
  return (
    <Workbench>
      <PageHead
        eyebrow="平台工具 / 本租户"
        title="历史重放"
        description="先验证消费者安全与事件范围，再创建可追踪的重放任务。"
        extra={<Button onClick={refresh}>刷新任务</Button>}
      />
      <ErrorNotice error={jobs.error} />
      <ErrorNotice error={classifications.error} />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          {
            key: "jobs",
            label: "重放任务",
            children: (
              <ListPanel
                count={jobs.data?.length ?? 0}
                after={after}
                onHome={() => setAfter("")}
                onNext={() => setAfter(jobs.data!.at(-1)!.jobId)}
              >
                <Table<Job>
                  rowKey="jobId"
                  dataSource={jobs.data}
                  loading={jobs.loading}
                  pagination={false}
                  scroll={{ x: 1060 }}
                  columns={[
                    {
                      title: "任务标识",
                      dataIndex: "jobId",
                      width: 240,
                      ellipsis: true,
                    },
                    { title: "消费者", dataIndex: "consumerId" },
                    {
                      title: "状态",
                      dataIndex: "status",
                      render: (v) => <Status value={v} />,
                    },
                    { title: "已检查", dataIndex: "examined", align: "right" },
                    { title: "已执行", dataIndex: "executed", align: "right" },
                    { title: "失败", dataIndex: "failed", align: "right" },
                    {
                      title: "创建时间",
                      dataIndex: "createdAt",
                      render: time,
                      width: 180,
                    },
                    {
                      title: "操作",
                      width: 100,
                      render: (_, r) => (
                        <Button
                          type="link"
                          onClick={() => {
                            setSelected(r.jobId);
                            control.clearResult();
                          }}
                        >
                          查看任务
                        </Button>
                      ),
                    },
                  ]}
                />
              </ListPanel>
            ),
          },
          {
            key: "create",
            label: "试运行与创建",
            children: (
              <Card className="operation-panel" title="限定事件范围">
                <PendingNotice pending={create.pending} />
                <ErrorNotice error={create.error} />
                <ErrorNotice error={previewError} />
                <Form
                  name="runtime-replay-create"
                  form={form}
                  layout="vertical"
                  disabled={locked || previewBusy}
                  initialValues={{ mode: "UNPROCESSED", maxEvents: 1000 }}
                  onValuesChange={(changed) => {
                    if (
                      Object.keys(changed).some((key) =>
                        [
                          "consumer",
                          "eventTypes",
                          "from",
                          "to",
                          "mode",
                          "maxEvents",
                        ].includes(key),
                      )
                    ) {
                      setPreview(undefined);
                      setAcknowledged(false);
                    }
                  }}
                  onFinish={async (value) => {
                    if (
                      !preview?.result.gate.allowed ||
                      !acknowledged ||
                      JSON.stringify(scope(value)) !==
                        JSON.stringify(preview.scope)
                    )
                      return;
                    const job = await create.execute(BASE + "/replays", {
                      ...preview.scope,
                      jobId: value.jobId.trim(),
                      reason: value.reason.trim(),
                    });
                    if (job) {
                      setPreview(undefined);
                      setAcknowledged(false);
                      refresh();
                    }
                  }}
                >
                  <div className="command-fields">
                    <Form.Item
                      name="consumer"
                      label="消费者"
                      rules={[{ required: true, message: "请选择消费者" }]}
                    >
                      <Select
                        options={classifications.data?.map((c) => ({
                          value: c.consumer,
                          label: c.consumer,
                        }))}
                        onChange={() => form.setFieldValue("eventTypes", [])}
                      />
                    </Form.Item>
                    <Form.Item
                      name="eventTypes"
                      label="事件类型"
                      rules={[
                        { required: true, message: "请选择事件类型" },
                        { type: "array", max: 10 },
                      ]}
                    >
                      <Select
                        mode="multiple"
                        options={classification?.types.map((t) => ({
                          value: t,
                          label: t,
                        }))}
                      />
                    </Form.Item>
                    <Form.Item
                      name="from"
                      label="范围开始（本机时间）"
                      rules={[{ required: true, message: "请选择范围开始" }]}
                    >
                      <Input type="datetime-local" />
                    </Form.Item>
                    <Form.Item
                      name="to"
                      label="范围结束（本机时间）"
                      rules={[{ required: true, message: "请选择范围结束" }]}
                    >
                      <Input type="datetime-local" />
                    </Form.Item>
                    <Form.Item
                      name="mode"
                      label="重放模式"
                      rules={[{ required: true }]}
                    >
                      <Select
                        options={[
                          { value: "UNPROCESSED", label: "补齐尚未处理的事件" },
                          { value: "REPROCESS", label: "重新执行纯投影" },
                        ]}
                      />
                    </Form.Item>
                    <Form.Item
                      name="maxEvents"
                      label="事件检查上限"
                      rules={[
                        { required: true },
                        { type: "number", min: 1, max: 10000 },
                      ]}
                    >
                      <InputNumber
                        min={1}
                        max={10000}
                        precision={0}
                        style={{ width: "100%" }}
                      />
                    </Form.Item>
                  </div>
                  <Typography.Paragraph type="secondary">
                    时间区间最多31天，结束时间不得晚于现在。资金、外部和不可逆副作用由服务端安全门拒绝。
                  </Typography.Paragraph>
                  <Button
                    onClick={() => void dryRun()}
                    loading={previewBusy}
                    disabled={locked}
                  >
                    验证范围与安全门
                  </Button>
                  {preview && (
                    <section className="command-result">
                      <Alert
                        showIcon
                        type={preview.result.gate.allowed ? "info" : "warning"}
                        title={
                          preview.result.gate.allowed
                            ? "试运行通过，尚未创建任务"
                            : "安全门拒绝重放"
                        }
                        description={preview.result.gate.detail}
                      />
                      <Descriptions
                        className="replay-summary"
                        column={{ xs: 1, md: 3 }}
                        items={[
                          {
                            key: "events",
                            label: "范围事件",
                            children: `${preview.result.events}${preview.result.capped ? "（达到截断范围）" : ""}`,
                          },
                          {
                            key: "processed",
                            label: "已处理",
                            children: preview.result.alreadyProcessed,
                          },
                          {
                            key: "execute",
                            label: "预计执行",
                            children: preview.result.wouldExecute,
                          },
                        ]}
                      />
                      <Table
                        size="small"
                        rowKey="eventType"
                        dataSource={preview.result.byType}
                        pagination={false}
                        columns={[
                          { title: "事件类型", dataIndex: "eventType" },
                          {
                            title: "范围事件数",
                            dataIndex: "events",
                            align: "right",
                          },
                          {
                            title: "已处理",
                            dataIndex: "processed",
                            align: "right",
                          },
                        ]}
                      />
                    </section>
                  )}
                  <div className="command-fields section">
                    <Form.Item
                      name="jobId"
                      label="任务标识"
                      rules={[
                        {
                          required: true,
                          whitespace: true,
                          message: "请输入任务标识",
                        },
                        {
                          pattern: /^[A-Za-z0-9_:.\-]{1,100}$/,
                          message: "使用合法业务标识",
                        },
                      ]}
                    >
                      <Input />
                    </Form.Item>
                    <Form.Item
                      name="reason"
                      label="创建原因"
                      rules={[
                        {
                          required: true,
                          whitespace: true,
                          message: "请输入创建原因",
                        },
                        { max: 256 },
                      ]}
                    >
                      <Input />
                    </Form.Item>
                  </div>
                  <Checkbox
                    checked={acknowledged}
                    disabled={!preview?.result.gate.allowed || locked}
                    onChange={(e) => setAcknowledged(e.target.checked)}
                  >
                    已核对范围，确认按安全门允许的方式执行
                  </Checkbox>
                  {!create.pending && (
                    <div className="section">
                      <Button
                        type="primary"
                        htmlType="submit"
                        loading={create.busy}
                        disabled={
                          !preview?.result.gate.allowed || !acknowledged
                        }
                      >
                        创建重放任务
                      </Button>
                    </div>
                  )}
                </Form>
                {create.pending && (
                  <Button
                    type="primary"
                    loading={create.busy}
                    onClick={() => void create.execute("", null)}
                  >
                    原样重试创建
                  </Button>
                )}
                {create.result && (
                  <Alert
                    className="section"
                    type="success"
                    showIcon
                    title={`任务 ${create.result.jobId} 已受理`}
                    description="处理尚未完成，可在重放任务中查看真实进度。"
                  />
                )}
              </Card>
            ),
          },
          {
            key: "safety",
            label: "消费者安全矩阵",
            children: (
              <ListPanel>
                <Table<Classification>
                  rowKey="consumer"
                  dataSource={classifications.data}
                  loading={classifications.loading}
                  pagination={false}
                  scroll={{ x: 980 }}
                  columns={[
                    { title: "消费者", dataIndex: "consumer", width: 240 },
                    { title: "事件类型", render: (_, r) => r.types.join("、") },
                    {
                      title: "补齐未处理",
                      width: 240,
                      render: (_, r) => <GateDecision gate={r.unprocessed} />,
                    },
                    {
                      title: "重新执行",
                      width: 240,
                      render: (_, r) => <GateDecision gate={r.reprocess} />,
                    },
                    {
                      title: "安全依据",
                      dataIndex: "evidence",
                      width: 240,
                      render: (value: string) => (
                        <Typography.Paragraph
                          style={{ margin: 0 }}
                          ellipsis={{
                            rows: 3,
                            expandable: "collapsible",
                            symbol: (expanded) =>
                              expanded ? "收起依据" : "完整依据",
                          }}
                        >
                          {value}
                        </Typography.Paragraph>
                      ),
                    },
                  ]}
                />
              </ListPanel>
            ),
          },
        ]}
      />
      <RecordDrawer
        title="重放任务详情"
        open={!!selected}
        onClose={() => {
          if (!control.busy && !control.pending) setSelected(undefined);
        }}
        footer={
          control.pending ? (
            <Typography.Text>
              请先确认原控制操作结果，再关闭详情。
            </Typography.Text>
          ) : undefined
        }
      >
        <ErrorNotice error={detail.error} />
        {detail.loading && (
          <Typography.Paragraph>正在读取任务…</Typography.Paragraph>
        )}
        {detail.data && (
          <>
            <Descriptions
              column={1}
              bordered
              items={[
                {
                  key: "id",
                  label: "任务标识",
                  children: (
                    <Typography.Text copyable>
                      {detail.data.jobId}
                    </Typography.Text>
                  ),
                },
                {
                  key: "consumer",
                  label: "消费者",
                  children: detail.data.consumerId,
                },
                {
                  key: "status",
                  label: "状态",
                  children: <Status value={detail.data.status} />,
                },
                {
                  key: "scope",
                  label: "事件类型",
                  children: detail.data.eventTypes,
                },
                {
                  key: "from",
                  label: "范围开始",
                  children: time(detail.data.fromAt),
                },
                {
                  key: "to",
                  label: "范围结束",
                  children: time(detail.data.toAt),
                },
                {
                  key: "progress",
                  label: "已检查 / 已执行 / 失败",
                  children: `${detail.data.examined} / ${detail.data.executed} / ${detail.data.failed}`,
                },
                { key: "reason", label: "原因", children: detail.data.reason },
                {
                  key: "error",
                  label: "最后错误",
                  children: detail.data.lastError || "无",
                },
                {
                  key: "version",
                  label: "当前版本",
                  children: detail.data.version,
                },
              ]}
            />
            <div className="section">
              <PendingNotice pending={control.pending} />
              <ErrorNotice error={control.error} />
              {["RUNNING", "PAUSED"].includes(detail.data.status) && (
                <Form
                  key={selected}
                  preserve={false}
                  name="runtime-replay-control"
                  form={controlForm}
                  layout="vertical"
                  disabled={control.busy || control.pending}
                  onFinish={async (v) => {
                    const result = await control.execute(
                      `${BASE}/replays/${encode(selected!)}/control`,
                      { ...v, expectedVersion: detail.data!.version },
                    );
                    if (result) {
                      refresh();
                      controlForm.resetFields();
                    }
                  }}
                >
                  <Form.Item
                    name="action"
                    label="任务操作"
                    rules={[{ required: true }]}
                  >
                    <Select
                      options={(detail.data.status === "RUNNING"
                        ? ["PAUSE", "CANCEL"]
                        : ["RESUME", "CANCEL"]
                      ).map((action) => ({
                        value: action,
                        label: actionLabels[action],
                      }))}
                    />
                  </Form.Item>
                  <Form.Item
                    name="reason"
                    label="操作原因"
                    rules={[{ required: true, whitespace: true }, { max: 256 }]}
                  >
                    <Input.TextArea />
                  </Form.Item>
                  <Typography.Paragraph type="secondary">
                    取消会停止后续处理，已完成的业务效果不会撤销。
                  </Typography.Paragraph>
                  {!control.pending && (
                    <Button
                      type="primary"
                      htmlType="submit"
                      loading={control.busy}
                    >
                      确认任务操作
                    </Button>
                  )}
                </Form>
              )}
              {control.pending && (
                <Button
                  type="primary"
                  loading={control.busy}
                  onClick={() => void control.execute("", null)}
                >
                  原样重试操作
                </Button>
              )}
              {control.result && (
                <Alert
                  type="success"
                  title={`任务状态已确认：${control.result.status}`}
                />
              )}
              <Button className="section" onClick={detail.refresh}>
                重新读取任务
              </Button>
            </div>
          </>
        )}
      </RecordDrawer>
    </Workbench>
  );
}
