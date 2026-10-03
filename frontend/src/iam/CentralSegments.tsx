import { useListFilters } from "../shared/listFilters";
import { CursorBack } from "../shared/pagination";
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Spin,
  Switch,
  Table,
  Tabs,
  Tag,
  Typography,
} from "antd";
import { useEffect, useMemo, useRef, useState } from "react";
import {
  ApiError,
  RequestContext,
  useResource,
  type request,
} from "../shared/api";
import type { Rule } from "../shared/contracts";
import { RuleEditor, RuleSummary } from "../shared/marketing";
import { useRouteState, useCursorState } from "../shared/routeState";
import { ErrorNotice, PageHead } from "../shared/ui";
import { HTTP, type Context } from "./api";
import {
  SEGMENT_PAGE_SIZE,
  isSegmentView,
  isSegmentRule,
  segmentClient,
  segmentIdentifier,
  type SegmentAction,
  type SegmentDefinition,
  type SegmentReceipt,
  type SegmentRun,
  type SegmentView,
} from "./segmentClient";
import { useSegmentCommand, useSegmentQualification } from "./segmentCommand";

type Props = { context: Context; onLogout: () => Promise<void> };
type Dirty = (task: string, value: boolean) => void;
const labels = {
  create: "创建定义",
  schedule: "调整调度",
  refresh: "发起刷新",
  control: "控制任务",
  pump: "单次推进",
};
const controls = {
  cancel: "取消任务",
  retry: "重试隔离任务",
  "retry-announcement": "重试入组公告",
};
type Control = keyof typeof controls;
const time = (value: string) => new Date(value).toLocaleString();
const idRules = [
  { required: true, message: "请输入实际编号" },
  {
    pattern: segmentIdentifier,
    message: "使用1—64位字母、数字、下划线、点、冒号或连字符",
  },
];
const versionRules = [
  { required: true, message: "请输入版本" },
  {
    validator: (_: unknown, value: number) =>
      Number.isSafeInteger(value) && value >= 0
        ? Promise.resolve()
        : Promise.reject(new Error("版本须为非负安全整数")),
  },
];
const forbidden = (error?: Error) =>
  error instanceof ApiError && error.status === HTTP.FORBIDDEN;

function Unknown({ frozen }: { frozen: boolean }) {
  return frozen ? (
    <Alert
      type="warning"
      showIcon
      title="操作结果尚未确认，请保留原意图重试"
      description="原目标、输入和幂等键已锁定。切换页签或返回会保留意图，重新打开后只能原样重试；权限撤销时请联系管理员核对已有结果。"
    />
  ) : null;
}
function Qualification({
  action,
  access,
  recheck,
}: {
  action: SegmentAction;
  access: ReturnType<typeof useSegmentQualification>;
  recheck: () => void;
}) {
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      {access.loading && <Spin description="正在核验独立操作权限" />}
      {!access.loading && !access.data?.allowed && (
        <Alert
          type={forbidden(access.error) ? "warning" : "info"}
          title={
            forbidden(access.error)
              ? `当前未获${labels[action]}权限`
              : "操作资格尚未确认"
          }
          description="读取、创建、调度、刷新、控制和推进权限分别授予。"
        />
      )}
      {!forbidden(access.error) && <ErrorNotice error={access.error} />}
      <Button disabled={access.loading} onClick={recheck}>
        重新核验{labels[action]}权限
      </Button>
    </Space>
  );
}
function DefinitionDetails({ value }: { value: SegmentView }) {
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Descriptions
        column={1}
        items={[
          { key: "id", label: "定义编号", children: value.content.segmentId },
          { key: "name", label: "名称", children: value.content.name },
          {
            key: "version",
            label: "定义版本（不可变）",
            children: value.content.version,
          },
          { key: "lock", label: "调度锁版本", children: value.lockVersion },
          {
            key: "audience",
            label: "输出人群编号",
            children: value.audienceId,
          },
          {
            key: "enabled",
            label: "未来周期刷新",
            children: value.enabled ? "已启用" : "已停用",
          },
          {
            key: "ttl",
            label: "快照有效期（秒）",
            children: value.content.ttlSeconds,
          },
          {
            key: "refresh",
            label: "刷新周期（秒）",
            children: value.content.refreshSeconds || "仅手动刷新",
          },
          {
            key: "capacity",
            label: "成员容量上限",
            children: value.content.maxMembers,
          },
        ]}
      />
      <div>
        <Typography.Title level={5}>会员匹配规则</Typography.Title>
        <RuleSummary rule={value.content.rule} />
      </div>
    </Space>
  );
}
function RunDetails({ value }: { value: SegmentRun }) {
  return (
    <Descriptions
      column={1}
      items={[
        { key: "id", label: "运行编号", children: value.runId },
        { key: "parent", label: "实际父定义", children: value.segmentId },
        {
          key: "definition",
          label: "固定定义版本",
          children: value.definitionVersion,
        },
        {
          key: "snapshot",
          label: "输出快照版本",
          children: value.snapshotVersion,
        },
        { key: "audience", label: "输出人群编号", children: value.audienceId },
        { key: "status", label: "实际任务状态", children: value.status },
        {
          key: "processed",
          label: "已处理 / 已匹配",
          children: `${value.processed} / ${value.matched}`,
        },
        {
          key: "cursor",
          label: "会员检查点",
          children: value.cursorMember || "尚无检查点",
        },
        { key: "attempts", label: "任务尝试次数", children: value.attempts },
        { key: "error", label: "错误分类", children: value.errorCode ?? "无" },
        {
          key: "start",
          label: "开始时间 / 数据时点",
          children: time(value.startedAt),
        },
        { key: "valid", label: "有效截止", children: time(value.validUntil) },
        {
          key: "next",
          label: "下次可推进时间",
          children: time(value.availableAt),
        },
        {
          key: "announcement",
          label: "入组公告",
          children: value.entriesAnnounced ? "已完成" : "尚未完成",
        },
        {
          key: "entryAttempts",
          label: "公告尝试次数",
          children: value.entryAttempts,
        },
      ]}
    />
  );
}
function Receipt({ value }: { value: SegmentReceipt }) {
  return (
    <Alert
      className="segment-receipt"
      type="success"
      showIcon
      title={isSegmentView(value) ? "定义或调度结果已确认" : "任务回执已确认"}
      description={
        <>
          {!isSegmentView(value) && (
            <p>
              任务回执表示实际任务状态；只有 COMPLETED
              才完成快照，入组公告另行显示。
            </p>
          )}
          {isSegmentView(value) ? (
            <DefinitionDetails value={value} />
          ) : (
            <RunDetails value={value} />
          )}
        </>
      }
    />
  );
}
function Runs({
  segment,
  select,
}: {
  segment: string;
  select: (value: SegmentRun) => void;
}) {
  const [after, setAfter] = useCursorState("SegmentRuns.after", "", segment);
  const rows = useResource<SegmentRun[]>(
    `/admin/segments/${encodeURIComponent(segment)}/runs?after=${encodeURIComponent(after)}&limit=${SEGMENT_PAGE_SIZE}`,
  );
  const [runId, setRunId] = useRouteState("SegmentRuns.detail", "");
  const selected = rows.data?.find((row) => row.runId === runId);
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Space wrap>
        <Button onClick={rows.refresh}>刷新运行记录</Button>
        <Typography.Text type="secondary">
          定义版本、调度锁与快照版本分别独立。
        </Typography.Text>
      </Space>
      <ErrorNotice error={rows.error} />
      {!rows.error && (
        <Table<SegmentRun>
          rowKey="runId"
          loading={rows.loading}
          dataSource={rows.data ?? []}
          pagination={false}
          scroll={{ x: 950 }}
          columns={[
            { title: "运行编号", dataIndex: "runId", width: 270 },
            {
              title: "固定定义版本",
              dataIndex: "definitionVersion",
              width: 130,
            },
            { title: "快照版本", dataIndex: "snapshotVersion", width: 100 },
            { title: "实际状态", dataIndex: "status", width: 130 },
            {
              title: "处理 / 匹配",
              width: 120,
              render: (_, row) => `${row.processed} / ${row.matched}`,
            },
            {
              title: "操作",
              width: 150,
              render: (_, row) => (
                <Space>
                  <Button onClick={() => setRunId(row.runId)}>查看</Button>
                  <Button onClick={() => select(row)}>控制</Button>
                </Space>
              ),
            },
          ]}
          locale={{ emptyText: "当前页没有运行记录" }}
        />
      )}
      <Space wrap>
        <CursorBack
          name={"SegmentRuns.after"}
          after={after}
          onPrevious={setAfter}
          initial={""}
          disabled={rows.loading || !!rows.error}
          count={rows.data?.length}
          pageSize={50}
        />
        <Button disabled={!after} onClick={() => setAfter("")}>
          回到首批记录
        </Button>
        <Button
          disabled={
            rows.loading ||
            !!rows.error ||
            rows.data?.length !== SEGMENT_PAGE_SIZE
          }
          onClick={() => {
            setRunId("");
            setAfter(rows.data?.at(-1)?.runId ?? "");
          }}
        >
          下一批记录
        </Button>
      </Space>
      <Modal
        centered
        className="campaign-modal segment-modal"
        width={600}
        title="动态人群运行详情"
        open={!!runId}
        onCancel={() => setRunId("")}
        footer={<Button onClick={() => setRunId("")}>关闭</Button>}
      >
        <ErrorNotice error={rows.error} />
        {!rows.error &&
          (selected ? (
            <RunDetails value={selected} />
          ) : (
            <Alert
              type="info"
              title="当前页未找到该运行，请刷新或返回记录列表重新选择"
            />
          ))}
      </Modal>
    </Space>
  );
}
function Directory({
  revision,
  selectDefinition,
  selectRun,
}: {
  revision: number;
  selectDefinition: (value: SegmentView) => void;
  selectRun: (value: SegmentRun) => void;
}) {
  const [after, setAfter] = useCursorState("SegmentDirectory.after", ""),
    [detail, setDetail] = useRouteState("SegmentDirectory.detail", "");
  const filters = useListFilters(
    "SegmentDirectory.after",
    "/admin/segments",
    () => setAfter(""),
  );
  const rows = useResource<SegmentView[]>(
    `/admin/segments?after=${encodeURIComponent(after)}&${filters.query}`,
  );
  const refresh = rows.refresh;
  useEffect(() => {
    if (revision) refresh();
  }, [revision, refresh]);
  const selected = rows.data?.find((row) => row.content.segmentId === detail);
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        查看真实最新定义与运行记录。新定义会关闭未来周期刷新，已开始的任务仍固定原定义版本。
      </Typography.Text>
      <Button onClick={refresh}>刷新定义目录</Button>
      {filters.toolbar}
      <ErrorNotice error={rows.error} />
      {!rows.error && (
        <Table<SegmentView>
          rowKey={(row) => row.content.segmentId}
          loading={rows.loading}
          dataSource={rows.data ?? []}
          pagination={false}
          scroll={{ x: 1000 }}
          columns={[
            {
              title: "动态人群",
              width: 260,
              render: (_, row) => (
                <Space orientation="vertical" size={0}>
                  <Typography.Text strong>{row.content.name}</Typography.Text>
                  <Typography.Text type="secondary">
                    {row.content.segmentId}
                  </Typography.Text>
                </Space>
              ),
            },
            {
              title: "定义版本",
              width: 100,
              render: (_, row) => row.content.version,
            },
            { title: "调度锁版本", dataIndex: "lockVersion", width: 120 },
            {
              title: "未来周期刷新",
              width: 150,
              render: (_, row) => (
                <Tag color={row.enabled ? "green" : undefined}>
                  {row.enabled ? "已启用" : "已停用"}
                </Tag>
              ),
            },
            { title: "输出人群", dataIndex: "audienceId", width: 290 },
            {
              title: "操作",
              width: 200,
              render: (_, row) => (
                <Space>
                  <Button onClick={() => setDetail(row.content.segmentId)}>
                    详情与记录
                  </Button>
                  <Button onClick={() => selectDefinition(row)}>
                    调整调度
                  </Button>
                </Space>
              ),
            },
          ]}
          locale={{ emptyText: "当前页没有动态人群定义" }}
        />
      )}
      <Space wrap>
        <CursorBack
          name={"SegmentDirectory.after"}
          after={after}
          onPrevious={setAfter}
          initial={""}
          disabled={rows.loading || !!rows.error}
          count={rows.data?.length}
          pageSize={filters.limit}
        />
        <Button disabled={!after} onClick={() => setAfter("")}>
          回到首页
        </Button>
        <Button
          disabled={
            rows.loading || !!rows.error || rows.data?.length !== filters.limit
          }
          onClick={() => {
            setDetail("");
            setAfter(rows.data?.at(-1)?.content.segmentId ?? "");
          }}
        >
          下一页
        </Button>
      </Space>
      <Modal
        centered
        className="campaign-modal segment-modal"
        width={760}
        title="动态人群定义与运行记录"
        open={!!detail}
        onCancel={() => setDetail("")}
        footer={<Button onClick={() => setDetail("")}>关闭</Button>}
      >
        <ErrorNotice error={rows.error} />
        {!rows.error && selected ? (
          <Space orientation="vertical" size="large" style={{ width: "100%" }}>
            <DefinitionDetails value={selected} />
            <Typography.Title level={5}>刷新运行记录</Typography.Title>
            <Runs
              key={detail}
              segment={detail}
              select={(run) => {
                setDetail("");
                selectRun(run);
              }}
            />
          </Space>
        ) : (
          !rows.error && (
            <Alert type="info" title="当前页未找到该定义，请返回目录重新选择" />
          )
        )}
      </Modal>
    </Space>
  );
}

type KeyedAction = Exclude<SegmentAction, "pump">;
type Fields = Partial<SegmentDefinition> & {
  expectedVersion?: number;
  enabled?: boolean;
  runId?: string;
  control?: Control;
};
function Operation({
  action,
  client,
  dirty,
  changed,
  selected,
  selectedRun,
}: {
  action: KeyedAction;
  client: typeof request;
  dirty: Dirty;
  changed: () => void;
  selected?: SegmentView;
  selectedRun?: SegmentRun;
}) {
  const access = useSegmentQualification(action),
    [form] = Form.useForm<Fields>(),
    { modal } = App.useApp();
  const [open, setOpen] = useState(false),
    [inputError, setInputError] = useState<Error>();
  const edited = useRef(false);
  const command = useSegmentCommand(client, (value) => {
    edited.current = value;
    dirty(action, value);
  });
  const [target, setTarget] = useRouteState(`Segment.${action}.target`, "");
  useEffect(() => {
    if (command.busy || command.frozen) return;
    if (action === "schedule" && selected) {
      form.setFieldsValue({
        segmentId: selected.content.segmentId,
        expectedVersion: selected.lockVersion,
        enabled: selected.enabled,
      });
      setTarget(selected.content.segmentId);
    }
    if (action === "control" && selectedRun) {
      form.setFieldsValue({ runId: selectedRun.runId });
      setTarget(selectedRun.runId);
    }
    // 只消费新的目录选择，提交结束不能用旧行再次覆盖已确认的锁版本。
  }, [selected, selectedRun, action, form]);
  const qualified = !!access.data?.allowed && !access.loading && !access.error;
  const allowed = qualified && !command.error;
  const recheck = () => {
    command.clear();
    access.refresh();
  };
  const close = async () => {
    if (command.busy) return;
    if (command.frozen) {
      if (
        await modal.confirm({
          centered: true,
          title: "保留原意图并返回页签？",
          content: "原目标、输入和幂等键保留，重新打开后只能原样重试。",
          okText: "保留并返回",
          cancelText: "继续确认",
        })
      )
        setOpen(false);
      return;
    }
    if (
      edited.current &&
      !(await modal.confirm({
        centered: true,
        title: "放弃未提交的动态人群输入？",
        okText: "放弃输入",
        cancelText: "继续编辑",
      }))
    )
      return;
    form.resetFields();
    edited.current = false;
    dirty(action, false);
    setInputError(undefined);
    setOpen(false);
  };
  async function submit(value: Fields) {
    if (!allowed || command.busy) return;
    let path: string, body: unknown;
    setInputError(undefined);
    try {
      if (action === "create") {
        if (!isSegmentRule(value.rule))
          throw new Error("请配置有效的会员匹配规则与比较值");
        path = "/admin/segments";
        body = {
          segmentId: value.segmentId,
          version: value.version,
          name: value.name?.trim(),
          rule: value.rule,
          ttlSeconds: value.ttlSeconds,
          refreshSeconds: value.refreshSeconds,
          maxMembers: value.maxMembers,
        };
      } else if (action === "control") {
        if (!value.control || !Object.hasOwn(controls, value.control))
          throw new Error("请选择受支持的控制动作");
        path = `/admin/segment-runs/${encodeURIComponent(value.runId ?? "")}/${value.control}`;
      } else {
        path = `/admin/segments/${encodeURIComponent(value.segmentId ?? "")}/${action}`;
        if (action === "schedule")
          body = {
            expectedVersion: value.expectedVersion,
            enabled: !!value.enabled,
          };
      }
    } catch (failure) {
      setInputError(failure as Error);
      return;
    }
    if (await command.run(path, body, action)) {
      setOpen(false);
      form.resetFields();
      changed();
      access.refresh();
    }
  }
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      {command.result && <Receipt value={command.result} />}
      <ErrorNotice error={command.error} />
      <Unknown frozen={command.frozen} />
      {command.error instanceof ApiError &&
        command.error.status === HTTP.CONFLICT &&
        !command.frozen && (
          <Alert
            type="warning"
            title="目标版本或任务状态冲突"
            description="重新核验权限后，读取实际调度锁版本或核对任务状态再调整输入。"
          />
        )}
      <Qualification action={action} access={access} recheck={recheck} />
      {allowed && (
        <Button
          type="primary"
          onClick={() => {
            if (!command.frozen && target && segmentIdentifier.test(target))
              form.setFieldsValue(
                action === "control"
                  ? { runId: target }
                  : { segmentId: target },
              );
            setOpen(true);
          }}
        >
          {command.frozen ? "恢复原操作意图" : labels[action]}
        </Button>
      )}
      <Modal
        centered
        className="campaign-modal segment-modal"
        title={labels[action]}
        width={action === "create" ? 640 : 560}
        open={open}
        onCancel={() => void close()}
        closable={!command.busy}
        keyboard={!command.busy}
        mask={{ closable: false }}
        footer={
          <Space wrap>
            <Button disabled={command.busy} onClick={() => void close()}>
              {command.frozen ? "返回页签（保留原意图）" : "取消"}
            </Button>
            <Button disabled={command.busy || access.loading} onClick={recheck}>
              重新核验权限
            </Button>
            {allowed && (
              <Button
                type="primary"
                loading={command.busy}
                onClick={() =>
                  void (command.frozen
                    ? submit(form.getFieldsValue())
                    : form.submit())
                }
              >
                {command.frozen ? "原样重试操作" : "确认" + labels[action]}
              </Button>
            )}
          </Space>
        }
      >
        <ErrorNotice error={inputError ?? command.error} />
        <Unknown frozen={command.frozen} />
        {!qualified && (
          <Qualification action={action} access={access} recheck={recheck} />
        )}
        <div hidden={!allowed}>
          <Form
            form={form}
            layout="vertical"
            disabled={command.busy || command.frozen}
            initialValues={{
              version: 1,
              ttlSeconds: 3600,
              refreshSeconds: 0,
              maxMembers: 1000,
              expectedVersion: 0,
              enabled: false,
              control: "cancel",
            }}
            onValuesChange={(value) => {
              edited.current = true;
              dirty(action, true);
              const id = action === "control" ? value.runId : value.segmentId;
              if (
                id === "" ||
                (typeof id === "string" && segmentIdentifier.test(id))
              )
                setTarget(id);
            }}
            onFinish={(value) => void submit(value)}
          >
            {action === "create" && (
              <>
                <Alert
                  type="info"
                  title="定义版本必须严格递增"
                  description="发布新版本会关闭未来周期刷新；已开始的任务仍保留原定义。"
                  style={{ marginBottom: 20 }}
                />
                <Form.Item name="segmentId" label="定义编号" rules={idRules}>
                  <Input maxLength={64} />
                </Form.Item>
                <Form.Item
                  name="version"
                  label="定义版本（不可变）"
                  rules={[
                    { required: true },
                    {
                      validator: (_, value) =>
                        Number.isSafeInteger(value) && value > 0
                          ? Promise.resolve()
                          : Promise.reject(new Error("请输入正整数版本")),
                    },
                  ]}
                >
                  <InputNumber
                    min={1}
                    max={Number.MAX_SAFE_INTEGER}
                    precision={0}
                  />
                </Form.Item>
                <Form.Item
                  name="name"
                  label="人群名称"
                  rules={[{ required: true, whitespace: true, max: 128 }]}
                >
                  <Input maxLength={128} />
                </Form.Item>
                <Form.Item
                  name="rule"
                  label="会员匹配规则"
                  rules={[{ required: true, message: "请填写规则条件" }]}
                >
                  <RuleEditor memberOnly />
                </Form.Item>
                <Form.Item
                  name="ttlSeconds"
                  label="快照有效期（秒）"
                  rules={[
                    { required: true },
                    { type: "integer", min: 300, max: 86400 },
                  ]}
                >
                  <InputNumber min={300} max={86400} precision={0} />
                </Form.Item>
                <Form.Item
                  name="refreshSeconds"
                  label="刷新周期（秒，0表示仅手动）"
                  dependencies={["ttlSeconds"]}
                  rules={[
                    { required: true },
                    {
                      validator: (_, value) =>
                        Number.isInteger(value) &&
                        (value === 0 ||
                          (value >= 60 &&
                            value <= form.getFieldValue("ttlSeconds")))
                          ? Promise.resolve()
                          : Promise.reject(
                              new Error("周期须为0或60秒至快照有效期之间"),
                            ),
                    },
                  ]}
                >
                  <InputNumber min={0} max={86400} precision={0} />
                </Form.Item>
                <Form.Item
                  name="maxMembers"
                  label="成员容量上限"
                  rules={[
                    { required: true },
                    { type: "integer", min: 100, max: 100000 },
                  ]}
                >
                  <InputNumber min={100} max={100000} precision={0} />
                </Form.Item>
              </>
            )}
            {action === "schedule" && (
              <>
                <Alert
                  type="info"
                  title="只调整未来周期刷新"
                  description="停用不会取消已开始的任务。直接输入的目标尚未经过目录核验；锁版本须来自实际定义。"
                  style={{ marginBottom: 20 }}
                />
                <Form.Item
                  name="segmentId"
                  label="实际定义编号"
                  rules={idRules}
                >
                  <Input maxLength={64} />
                </Form.Item>
                <Form.Item
                  name="expectedVersion"
                  label="调度锁版本"
                  rules={versionRules}
                >
                  <InputNumber
                    min={0}
                    max={Number.MAX_SAFE_INTEGER}
                    precision={0}
                  />
                </Form.Item>
                <Form.Item
                  name="enabled"
                  label="启用未来周期刷新"
                  valuePropName="checked"
                >
                  <Switch />
                </Form.Item>
              </>
            )}
            {action === "refresh" && (
              <>
                <Alert
                  type="info"
                  title="发起或返回当前刷新任务"
                  description="返回任务不等于快照完成，请按独立读取权限查询实际运行记录。"
                  style={{ marginBottom: 20 }}
                />
                <Form.Item
                  name="segmentId"
                  label="实际定义编号"
                  rules={idRules}
                >
                  <Input maxLength={64} />
                </Form.Item>
              </>
            )}
            {action === "control" && (
              <>
                <Alert
                  type="info"
                  title="控制实际运行任务"
                  description="运行编号与父定义编号不同；服务端验证原定义版本和原来源，拒绝时不会更换创建者。"
                  style={{ marginBottom: 20 }}
                />
                <Form.Item name="runId" label="实际运行编号" rules={idRules}>
                  <Input maxLength={64} />
                </Form.Item>
                <Form.Item
                  name="control"
                  label="控制动作"
                  rules={[{ required: true }]}
                >
                  <Select
                    options={Object.entries(controls).map(([value, label]) => ({
                      value,
                      label,
                    }))}
                  />
                </Form.Item>
              </>
            )}
          </Form>
        </div>
      </Modal>
    </Space>
  );
}
function Pump({
  client,
  dirty,
  changed,
}: {
  client: typeof request;
  dirty: Dirty;
  changed: () => void;
}) {
  const access = useSegmentQualification("pump"),
    { modal } = App.useApp();
  const [busy, setBusy] = useState(false),
    [unknown, setUnknown] = useState(false),
    [error, setError] = useState<Error>(),
    [result, setResult] = useState<number>();
  const running = useRef(false);
  const qualified = !!access.data?.allowed && !access.loading && !access.error;
  const allowed = qualified && !error;
  const recheck = () => {
    setError(undefined);
    access.refresh();
  };
  async function pump() {
    if (running.current || !allowed || unknown) return;
    running.current = true;
    setBusy(true);
    setResult(undefined);
    setError(undefined);
    dirty("pump", true);
    let sent = false;
    try {
      await client<{ allowed: boolean }>("/operations/segments/pump-access");
      sent = true;
      const count = await client<number>("/admin/segments/pump", {
        method: "POST",
      });
      setResult(count);
      dirty("pump", false);
      changed();
    } catch (failure) {
      setError(failure as Error);
      if (sent && (!(failure instanceof ApiError) || failure.status >= 500))
        setUnknown(true);
      else dirty("pump", false);
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Paragraph>
        单次调用只推进有界批次，逐任务核验原来源。返回处理数不表示所有快照或公告完成。
      </Typography.Paragraph>
      <Qualification action="pump" access={access} recheck={recheck} />
      <ErrorNotice error={error} />
      {result !== undefined && (
        <Alert
          type="success"
          title={`本次推进回执已确认：${result}`}
          description="请按实际运行状态及入组公告状态核对结果。"
        />
      )}
      {unknown && (
        <Alert
          type="warning"
          showIcon
          title="本次推进结果未知，可能已提交部分批次"
          description={
            <>
              <Typography.Paragraph>
                此接口没有幂等键，不能原样重试来保证仅执行一次。有读取权限可查询运行记录；没有读取权限请先请相关人员核对，确认后再发起下一次有界推进。
              </Typography.Paragraph>
              <Button
                disabled={busy}
                onClick={async () => {
                  if (
                    await modal.confirm({
                      centered: true,
                      title: "确认准备发起下一次有界推进？",
                      content:
                        "先前请求可能已推进部分任务；下一次是新调用，不是原请求的幂等重试。请先核对已有结果。",
                      okText: "已核对，允许下一次",
                      cancelText: "暂不推进",
                    })
                  ) {
                    setUnknown(false);
                    dirty("pump", false);
                    recheck();
                  }
                }}
              >
                核对后允许下一次推进
              </Button>
            </>
          }
        />
      )}
      {allowed && !unknown && (
        <Button type="primary" loading={busy} onClick={() => void pump()}>
          执行单次推进
        </Button>
      )}
    </Space>
  );
}
function Panels({ client, dirty }: { client: typeof request; dirty: Dirty }) {
  const [tab, setTab] = useRouteState("workspaceTab", "directory"),
    [operation, setOperation] = useRouteState("Segment.operation", "schedule");
  const [revision, setRevision] = useState(0),
    [selected, setSelected] = useState<SegmentView>(),
    [selectedRun, setSelectedRun] = useState<SegmentRun>();
  const changed = () => setRevision((value) => value + 1);
  return (
    <Tabs
      activeKey={["create", "operations"].includes(tab) ? tab : "directory"}
      onChange={setTab}
      destroyOnHidden={false}
      items={[
        {
          key: "directory",
          label: "定义与运行记录",
          forceRender: true,
          children: (
            <Directory
              revision={revision}
              selectDefinition={(value) => {
                setSelected(value);
                setOperation("schedule");
                setTab("operations");
              }}
              selectRun={(value) => {
                setSelectedRun(value);
                setOperation("control");
                setTab("operations");
              }}
            />
          ),
        },
        {
          key: "create",
          label: "创建定义",
          forceRender: true,
          children: (
            <Operation
              action="create"
              client={client}
              dirty={dirty}
              changed={changed}
            />
          ),
        },
        {
          key: "operations",
          label: "独立操作",
          forceRender: true,
          children: (
            <Tabs
              activeKey={
                ["schedule", "refresh", "control", "pump"].includes(operation)
                  ? operation
                  : "schedule"
              }
              onChange={setOperation}
              destroyOnHidden={false}
              items={[
                ...(["schedule", "refresh", "control"] as const).map(
                  (action) => ({
                    key: action,
                    label: labels[action],
                    forceRender: true,
                    children: (
                      <Operation
                        action={action}
                        client={client}
                        dirty={dirty}
                        changed={changed}
                        selected={selected}
                        selectedRun={selectedRun}
                      />
                    ),
                  }),
                ),
                {
                  key: "pump",
                  label: labels.pump,
                  forceRender: true,
                  children: (
                    <Pump client={client} dirty={dirty} changed={changed} />
                  ),
                },
              ]}
            />
          ),
        },
      ]}
    />
  );
}
export function CentralSegments({ context, onLogout }: Props) {
  const [expired, setExpired] = useState(false),
    tasks = useRef(new Set<string>()),
    { modal } = App.useApp();
  const client = useMemo(
    () => segmentClient(context, () => setExpired(true)),
    [context.token, context.tenant],
  );
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (tasks.current.size) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", warn);
    return () => removeEventListener("beforeunload", warn);
  }, []);
  const dirty: Dirty = (task, value) => {
    if (value) tasks.current.add(task);
    else tasks.current.delete(task);
  };
  const logout = () => {
    if (!tasks.current.size) void onLogout();
    else
      modal.confirm({
        centered: true,
        title: "离开动态人群工作区？",
        content:
          "未保存输入或未知结果将丢失。请先原样重试确认有幂等键的命令，或核对未知的推进结果。",
        okText: "仍然离开",
        cancelText: "留在当前页",
        onOk: onLogout,
      });
  };
  return (
    <main className="central-products">
      <Card>
        <PageHead
          eyebrow="企业经营"
          title="动态人群"
          description="按会员规则生成快照，独立控制未来调度和实际任务。"
          extra={<Button onClick={logout}>退出登录</Button>}
        />
        {expired ? (
          <Alert
            type="warning"
            title="登录已失效，请重新登录"
            action={<Button onClick={() => void onLogout()}>返回登录</Button>}
          />
        ) : (
          <RequestContext.Provider value={client}>
            <Panels client={client} dirty={dirty} />
          </RequestContext.Provider>
        )}
      </Card>
    </main>
  );
}
