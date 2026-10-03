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
  Table,
  Tabs,
  Tag,
  Typography,
} from "antd";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import {
  ApiError,
  RequestContext,
  useResource,
  type request,
} from "../shared/api";
import { PageHead, ErrorNotice } from "../shared/ui";
import type { Governed, Journey, JourneyInstance } from "../shared/contracts";
import { JourneyMap } from "../features/JourneyMap";
import { journeyClient, journeyId, journeyActions } from "./journeyClient";
import { useJourneyCommand } from "./journeyCommand";
import { HTTP, type Context } from "./api";

type Props = { context: Context; onLogout: () => Promise<void> };
type Area = "definitions" | "instances" | "scans" | "effects" | "executions";
type Scan = {
  journeyId: string;
  journeyVersion: number;
  status: string;
  memberCursor: string;
  createdBefore: string;
  nextDue: string;
  scanned: number;
  enrolled: number;
  attempts: number;
  errorCode: string | null;
  version: number;
};
type Execution = {
  orderId: string;
  campaignId: string;
  campaignVersion: number;
  memberId: string;
  storeId: string;
  status: string;
  discountAmount: string;
  grantStatus: string | null;
  eventStatus: string | null;
  failureClass: string | null;
  lockVersion: number;
};
type Row = Record<string, unknown>;
const titles: Record<Area, string> = {
  definitions: "旅程定义",
  instances: "旅程实例",
  scans: "生命周期扫描",
  effects: "营销效果",
  executions: "营销执行",
};
const states = {
  submit: "提交审核",
  approve: "批准",
  reject: "驳回",
  publish: "发布",
  pause: "暂停",
};
const idRules = [
  { required: true, message: "请输入实际编号" },
  { pattern: journeyId, message: "请输入1—64位合法编号" },
];
const hints: Record<Area, string[]> = {
  definitions: journeyActions.map((a) => `/operations/journeys/${a}-access`),
  instances: [
    "/operations/journey-instances/create-access",
    "/operations/journey-instances/control-access",
  ],
  scans: ["/operations/journey-scans/retry-access"],
  effects: ["/operations/marketing-effects/rebuild-access"],
  executions: [],
};
function useQualifications(
  client: typeof request,
  area: Area,
  revision: number,
) {
  const [allowed, setAllowed] = useState<Record<string, boolean>>({}),
    [error, setError] = useState<Error>();
  useEffect(() => {
    const controller = new AbortController();
    setAllowed({});
    setError(undefined);
    void (async () => {
      for (const path of hints[area]) {
        try {
          const data = await client<{ allowed: boolean }>(path, {
            signal: controller.signal,
          });
          if (!controller.signal.aborted)
            setAllowed((prev) => ({ ...prev, [path]: data.allowed === true }));
        } catch (failure) {
          if (controller.signal.aborted) return;
          if (!(
            failure instanceof ApiError && failure.status === HTTP.FORBIDDEN
          ))
            setError(failure as Error);
        }
      }
    })();
    return () => controller.abort();
  }, [client, area, revision]);
  return { allowed, error };
}
function JsonResult({ value }: { value: unknown }) {
  return (
    <pre className="central-journey-json">{JSON.stringify(value, null, 2)}</pre>
  );
}
function Shell({ area, context, onLogout }: { area: Area } & Props) {
  const client = useMemo(
    () => journeyClient(context, () => void onLogout()),
    [context.token, context.tenant, onLogout],
  );
  return (
    <RequestContext.Provider value={client}>
      <Workspace area={area} client={client} />
    </RequestContext.Provider>
  );
}
export function CentralJourneys(props: Props) {
  return <Shell {...props} area="definitions" />;
}
export function CentralJourneyInstances(props: Props) {
  return <Shell {...props} area="instances" />;
}
export function CentralJourneyScans(props: Props) {
  return <Shell {...props} area="scans" />;
}
export function CentralMarketingEffects(props: Props) {
  return <Shell {...props} area="effects" />;
}
export function CentralMarketingExecutions(props: Props) {
  return <Shell {...props} area="executions" />;
}
function Workspace({ area, client }: { area: Area; client: typeof request }) {
  const { modal } = App.useApp();
  const [revision, setRevision] = useState(0),
    [after, setAfter] = useState("");
  const command = useJourneyCommand(client),
    qualification = useQualifications(client, area, revision);
  const [open, setOpen] = useState<string>(),
    [detail, setDetail] = useState<unknown>(),
    [detailTitle, setDetailTitle] = useState("实际记录");
  const [form] = Form.useForm();
  const changed = useRef(false);
  const [historyId, setHistoryId] = useState(""),
    [historyTarget, setHistoryTarget] = useState(""),
    [historyAfter, setHistoryAfter] = useState(-1);
  const history = useResource<{
    instance: JourneyInstance;
    definition: Journey;
    traceCoverage: string;
    steps: Row[];
  }>(
    historyTarget
      ? `/admin/journey-instances/${historyTarget}/history?afterVersion=${historyAfter}&limit=50`
      : null,
  );
  const [range, setRange] = useState<{
      store: string;
      from: string;
      to: string;
    }>(),
    [series, setSeries] = useState("campaigns");
  const [executionTarget, setExecutionTarget] = useState<{
    order: string;
    campaign: string;
  }>();
  const executionDetail = useResource<Execution>(
    executionTarget
      ? `/admin/marketing-executions/${executionTarget.order}/${executionTarget.campaign}`
      : null,
  );
  const query = range
    ? `storeId=${encodeURIComponent(range.store)}&from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}&after=${encodeURIComponent(after)}&limit=50`
    : "";
  const paths = {
    campaigns: "/admin/marketing-effects",
    journeys: "/admin/marketing-effects/journeys",
    deliveries: "/admin/marketing-effects/deliveries",
    counts: "/admin/journey-effects",
  };
  const listPath =
    area === "definitions"
      ? `/admin/journeys?after=${encodeURIComponent(after)}&limit=50`
      : area === "instances"
        ? `/admin/journey-instances?after=${encodeURIComponent(after)}&limit=50`
        : area === "scans"
          ? `/admin/journey-scans?after=${encodeURIComponent(after)}&limit=50`
          : area === "executions"
            ? `/admin/marketing-executions?after=${encodeURIComponent(after)}&limit=50`
            : range
              ? `${paths[series as keyof typeof paths]}?${query}`
              : null;
  const list = useResource<unknown>(listPath);
  const refresh = () => {
    setRevision((v) => v + 1);
    list.refresh();
    history.refresh();
    executionDetail.refresh();
  };
  const allowed = (path: string) => qualification.allowed[path] === true;
  const close = () => {
    if (command.busy) return;
    if (command.frozen) {
      modal.info({
        centered: true,
        title: "请先确认原操作结果",
        content:
          "原目标、输入和幂等键已经保留。当前窗口只允许原样重试，权限撤销时请联系管理员核对已提交结果。",
      });
      return;
    }
    if (changed.current) {
      modal.confirm({
        centered: true,
        title: "放弃未保存的输入？",
        okText: "放弃输入",
        cancelText: "继续编辑",
        onOk: () => {
          changed.current = false;
          command.markDirty(false);
          setOpen(undefined);
        },
      });
      return;
    }
    setOpen(undefined);
  };
  const begin = (task: string, values?: unknown) => {
    if (command.frozen || command.busy) return;
    const show = () => {
      command.clearResult();
      form.resetFields();
      if (values && typeof values === "object") form.setFieldsValue(values);
      setOpen(task);
      changed.current = false;
      command.markDirty(false);
    };
    if (changed.current)
      modal.confirm({
        centered: true,
        title: "放弃未保存的输入？",
        onOk: show,
      });
    else show();
  };
  const completed = (value: unknown) => {
    if (value === undefined) return;
    changed.current = false;
    command.markDirty(false);
    setOpen(undefined);
    setDetail(value);
    setDetailTitle("实际操作结果");
    refresh();
  };
  const retry = async () => completed(await command.retry());
  const run = async (values: Row) => {
    if (command.frozen) {
      await retry();
      return;
    }
    try {
      let path = "",
        body: unknown = values,
        hint = "",
        keyed = true;
      if (open === "create" || open === "validate") {
        const nodes = JSON.parse(String(values.nodes));
        if (!Array.isArray(nodes) || nodes.length < 1 || nodes.length > 32)
          throw new Error("节点必须为1—32项完整配置");
        body = {
          ...values,
          nodes,
          version: Number(values.version),
          maxDurationSeconds: Number(values.maxDurationSeconds),
          validFrom: new Date(String(values.validFrom)).toISOString(),
          validTo: new Date(String(values.validTo)).toISOString(),
          ...(values.controls
            ? { controls: JSON.parse(String(values.controls)) }
            : {}),
          ...(values.lifecycle
            ? { lifecycle: JSON.parse(String(values.lifecycle)) }
            : {}),
        };
        if (!values.controls) delete (body as Row).controls;
        if (!values.lifecycle) delete (body as Row).lifecycle;
        path =
          open === "create" ? "/admin/journeys" : "/admin/journeys/validate";
        hint = `/operations/journeys/${open}-access`;
        keyed = open === "create";
      } else if (open === "preview") {
        path = `/admin/journeys/${values.journeyId}/${values.version}/preview`;
        body = {
          memberId: values.memberId,
          ...(values.orderId ? { orderId: values.orderId } : {}),
          ...(values.at
            ? { at: new Date(String(values.at)).toISOString() }
            : {}),
        };
        hint = "/operations/journeys/preview-access";
        keyed = false;
      } else if (open && open in states) {
        path = `/admin/journeys/${values.journeyId}/${values.version}/${open}`;
        body = { expectedVersion: values.expectedVersion };
        hint = `/operations/journeys/${open}-access`;
      } else if (open === "enroll") {
        path = "/admin/journey-instances";
        hint = "/operations/journey-instances/create-access";
      } else if (open === "control") {
        path = `/admin/journey-instances/${values.instanceId}/${values.action}`;
        body = {};
        hint = "/operations/journey-instances/control-access";
      } else if (open === "scan-retry") {
        path = `/admin/journey-scans/${values.journeyId}/${values.version}/retry`;
        body = {
          expectedVersion: values.expectedVersion,
          reason: values.reason,
        };
        hint = "/operations/journey-scans/retry-access";
      } else if (open === "rebuild") {
        path = "/admin/marketing-effects/rebuild";
        body = { after: values.after ?? "", limit: 50 };
        hint = "/operations/marketing-effects/rebuild-access";
      }
      const value = await command.run(path, body, hint, keyed);
      completed(value);
    } catch (error) {
      modal.error({
        centered: true,
        title: "输入未通过检查",
        content: (error as Error).message,
      });
    }
  };
  const pump = () =>
    modal.confirm({
      centered: true,
      title: "推进当前租户的有限批次？",
      content:
        "后台仅按各实例原来源和固定自动政策继续。部分节点可能已提交；无幂等键的结果未知时请核对实际进度，再显式发起新调用。",
      okText: "推进一次",
      onOk: async () => {
        const value = await command.run(
          "/admin/journeys/pump",
          {},
          "/operations/journeys/pump-access",
          false,
        );
        if (value !== undefined) {
          setDetail(value);
          setDetailTitle("本次实际推进数");
        }
        refresh();
      },
    });
  const raw = list.data as
    | Row[]
    | {
        rows: Row[];
        basis?: string;
        cohort?: string;
        coverage?: string;
        costBasis?: string;
      }
    | undefined;
  const rows = Array.isArray(raw) ? raw : (raw?.rows ?? []);
  const columns =
    area === "definitions"
      ? [
          {
            title: "旅程",
            key: "journey",
            render: (_: unknown, row: Row) => {
              const value = row as unknown as Governed<Journey>;
              return (
                <Button
                  type="link"
                  onClick={() => {
                    setDetail(value);
                    setDetailTitle(value.content.name);
                  }}
                >
                  {value.content.name} · {value.content.journeyId}
                </Button>
              );
            },
          },
          {
            title: "固定内容版本",
            key: "contentVersion",
            render: (_: unknown, row: Row) =>
              (row as unknown as Governed<Journey>).content.version,
          },
          {
            title: "状态 / 状态CAS",
            key: "state",
            render: (_: unknown, row: Row) => (
              <>
                <Tag>{String(row.status)}</Tag>
                {String(row.lockVersion)}
              </>
            ),
          },
          {
            title: "触发",
            key: "trigger",
            render: (_: unknown, row: Row) =>
              (row as unknown as Governed<Journey>).content.trigger,
          },
          {
            title: "操作",
            key: "actions",
            render: (_: unknown, row: Row) => {
              const value = row as unknown as Governed<Journey>;
              return (
                <Space wrap>
                  {Object.entries(states).map(([task, label]) => (
                    <Button
                      key={task}
                      disabled={
                        !allowed(`/operations/journeys/${task}-access`) ||
                        command.frozen
                      }
                      onClick={() =>
                        begin(task, {
                          journeyId: value.content.journeyId,
                          version: value.content.version,
                          expectedVersion: value.lockVersion,
                        })
                      }
                    >
                      {label}
                    </Button>
                  ))}
                </Space>
              );
            },
          },
        ]
      : area === "instances"
        ? [
            {
              title: "实例",
              dataIndex: "instanceId",
              render: (id: string) => (
                <Button
                  type="link"
                  onClick={() => {
                    setHistoryId(id);
                    setHistoryTarget(id);
                    setHistoryAfter(-1);
                  }}
                >
                  {id}
                </Button>
              ),
            },
            {
              title: "旅程 / 固定内容版本",
              key: "content",
              render: (_: unknown, row: Row) =>
                `${row.journeyId} / ${row.journeyVersion}`,
            },
            { title: "状态", dataIndex: "status" },
            { title: "当前节点", dataIndex: "currentNode" },
            { title: "已提交步数", dataIndex: "steps" },
            { title: "进度CAS", dataIndex: "version" },
            { title: "执行截止", dataIndex: "deadline" },
            {
              title: "操作",
              key: "action",
              render: (_: unknown, row: Row) => (
                <Button
                  disabled={
                    !allowed("/operations/journey-instances/control-access") ||
                    command.frozen
                  }
                  onClick={() =>
                    begin("control", { instanceId: row.instanceId })
                  }
                >
                  取消 / 重试
                </Button>
              ),
            },
          ]
        : area === "scans"
          ? [
              { title: "旅程", dataIndex: "journeyId" },
              { title: "固定内容版本", dataIndex: "journeyVersion" },
              { title: "状态", dataIndex: "status" },
              { title: "检查点", dataIndex: "memberCursor" },
              {
                title: "扫描 / 入组",
                key: "counts",
                render: (_: unknown, row: Row) =>
                  `${row.scanned} / ${row.enrolled}`,
              },
              { title: "进度CAS", dataIndex: "version" },
              { title: "下次扫描", dataIndex: "nextDue" },
              { title: "失败码", dataIndex: "errorCode" },
              {
                title: "操作",
                key: "retry",
                render: (_: unknown, row: Row) => (
                  <Button
                    disabled={
                      !allowed("/operations/journey-scans/retry-access") ||
                      command.frozen
                    }
                    onClick={() =>
                      begin("scan-retry", {
                        journeyId: row.journeyId,
                        version: row.journeyVersion,
                        expectedVersion: row.version,
                      })
                    }
                  >
                    恢复扫描
                  </Button>
                ),
              },
            ]
          : area === "executions"
            ? [
                {
                  title: "订单",
                  dataIndex: "orderId",
                  render: (id: string, row: Row) => (
                    <Button
                      type="link"
                      onClick={() =>
                        setExecutionTarget({
                          order: id,
                          campaign: String(row.campaignId),
                        })
                      }
                    >
                      {id}
                    </Button>
                  ),
                },
                { title: "活动", dataIndex: "campaignId" },
                { title: "固定版本", dataIndex: "campaignVersion" },
                { title: "状态", dataIndex: "status" },
                { title: "成交优惠", dataIndex: "discountAmount" },
                { title: "权益真实状态", dataIndex: "grantStatus" },
                { title: "失败分类", dataIndex: "failureClass" },
              ]
            : Object.keys(rows[0] ?? {}).map((key) => ({
                title:
                  (
                    {
                      journeyId: "旅程",
                      journeyVersion: "固定版本",
                      batchId: "发券批次",
                      netReceipts: "净收款",
                      received: "已收款",
                      refunded: "成功退款",
                      coverage: "覆盖",
                    } as Record<string, string>
                  )[key] ?? key,
                dataIndex: key,
                render: (value: unknown) => (
                  <span>{value == null ? "—" : String(value)}</span>
                ),
              }));
  const field = (
    name: string,
    label: string,
    number = false,
    optional = false,
  ) => (
    <Form.Item
      key={name}
      name={name}
      label={label}
      rules={
        optional && name !== "at"
          ? []
          : number
            ? [{ required: true, message: "请输入整数" }]
            : ["from", "to", "validFrom", "validTo", "at"].includes(name)
              ? [
                  { required: !optional, message: "请输入带时区的时间" },
                  {
                    validator: (_: unknown, value: string) =>
                      (!value && optional) ||
                      (Number.isFinite(Date.parse(value)) &&
                        /(Z|[+-][0-9]{2}:[0-9]{2})$/.test(value))
                        ? Promise.resolve()
                        : Promise.reject(new Error("时间必须含明确UTC或时区")),
                  },
                ]
              : [
                    "journeyId",
                    "storeId",
                    "store",
                    "entry",
                    "memberId",
                    "eventKey",
                    "instanceId",
                    "orderId",
                    "campaignId",
                  ].includes(name)
                ? idRules
                : [{ required: true, message: "请输入" + label }]
      }
    >
      {number ? (
        <InputNumber
          min={name === "expectedVersion" ? 0 : 1}
          precision={0}
          max={Number.MAX_SAFE_INTEGER}
          style={{ width: "100%" }}
        />
      ) : (
        <Input />
      )}
    </Form.Item>
  );
  let fields: ReactNode;
  if (open === "create" || open === "validate")
    fields = (
      <>
        {field("journeyId", "旅程编号")}
        {field("version", "固定内容版本", true)}
        {field("storeId", "实际门店编号")}
        {field("name", "名称")}
        <Form.Item name="trigger" label="触发方式" rules={[{ required: true }]}>
          <Select
            options={[
              "MANUAL",
              "ORDER_PAID",
              "MEMBER_REGISTERED",
              "LEVEL_CHANGED",
              "SEGMENT_ENTERED",
              "BIRTHDAY",
              "DORMANT",
              "REPURCHASE",
              "CART_ABANDONED",
            ].map((value) => ({ value, label: value }))}
          />
        </Form.Item>
        {field("validFrom", "入组窗口起点（含，带时区）")}
        {field("validTo", "入组窗口终点（不含，带时区）")}
        {field("maxDurationSeconds", "实例最大执行秒数（1—2592000）", true)}
        {field("entry", "起始节点")}
        <Form.Item
          name="nodes"
          label="完整节点配置"
          extra="使用现有节点字段：id、kind、next；WAIT seconds；DECIDE rule/yesNext/noNext；GRANT benefit；COUPON coupon；NOTIFY title/body。"
          rules={[{ required: true }]}
        >
          <Input.TextArea rows={8} />
        </Form.Item>
        <Form.Item name="controls" label="频控配置（会员事件必填）">
          <Input.TextArea rows={3} />
        </Form.Item>
        <Form.Item name="lifecycle" label="生命周期配置（生命周期触发必填）">
          <Input.TextArea rows={3} />
        </Form.Item>
      </>
    );
  else if (open === "preview")
    fields = (
      <>
        {field("journeyId", "实际旅程编号")}
        {field("version", "固定内容版本", true)}
        {field("memberId", "会员编号")}
        {field("orderId", "来源订单编号", false, true)}
        {field("at", "评估时间（带时区）", false, true)}
        <Alert type="info" title="预览不发放，WAIT后的可变事实不能预测。" />
      </>
    );
  else if (open && open in states)
    fields = (
      <>
        {field("journeyId", "实际旅程编号")}
        {field("version", "固定内容版本", true)}
        {field("expectedVersion", "当前状态CAS", true)}
      </>
    );
  else if (open === "enroll")
    fields = (
      <>
        {field("journeyId", "已发布手工旅程编号")}
        {field("version", "固定内容版本", true)}
        {field("memberId", "实际会员编号")}
        {field("eventKey", "原触发去重键")}
        <Alert
          type="info"
          title="后续节点持续核验本次原有限来源；新登录或推进资格不会为原任务续权。"
        />
      </>
    );
  else if (open === "control")
    fields = (
      <>
        {field("instanceId", "实际实例编号")}
        <Form.Item name="action" label="操作" rules={[{ required: true }]}>
          <Select
            options={[
              { value: "cancel", label: "取消未执行节点" },
              { value: "retry", label: "重试隔离实例" },
            ]}
          />
        </Form.Item>
        <Alert
          type="warning"
          title="取消保留已提交通知、券及权益；重试保留原来源、检查点及执行截止。"
        />
      </>
    );
  else if (open === "scan-retry")
    fields = (
      <>
        {field("journeyId", "旅程编号")}
        {field("version", "固定内容版本", true)}
        {field("expectedVersion", "实际扫描进度CAS", true)}
        {field("reason", "恢复原因")}
        <Alert
          type="info"
          title="恢复保留原游标，不撤销已入组实例；原政策和入组窗口仍需有效。"
        />
      </>
    );
  else if (open === "rebuild")
    fields = (
      <>
        {field("after", "原订单游标（首批留空）", false, true)}
        <Alert
          type="info"
          title="每批50单，按实际返回游标继续补齐。重建只修复读模型，不重放权益或付款。"
        />
      </>
    );
  return (
    <>
      <PageHead
        eyebrow="企业经营"
        title={titles[area]}
        description={
          area === "definitions"
            ? "固定内容、审批状态和状态CAS分别展示；新版本不会改写已有实例。"
            : area === "effects"
              ? "读取真实成交、退款和覆盖口径；观察结果不能当作因果ROI。"
              : "查看真实持久进度、固定版本及已提交结果。"
        }
        extra={
          <Button onClick={refresh} disabled={command.busy}>
            重新核验并刷新
          </Button>
        }
      />
      <ErrorNotice error={qualification.error} />
      <ErrorNotice error={command.error} />
      <Space wrap className="central-journey-actions">
        {area === "definitions" && (
          <>
            {["create", "validate", "preview", ...Object.keys(states)].map(
              (task) => (
                <Button
                  key={task}
                  disabled={
                    !allowed(`/operations/journeys/${task}-access`) ||
                    command.frozen
                  }
                  onClick={() => begin(task)}
                >
                  {
                    (
                      {
                        create: "创建版本",
                        validate: "校验定义",
                        preview: "预览路径",
                        ...states,
                      } as Record<string, string>
                    )[task]
                  }
                </Button>
              ),
            )}
            <Button
              disabled={
                !allowed("/operations/journeys/pump-access") || command.frozen
              }
              onClick={pump}
            >
              推进一次
            </Button>
          </>
        )}
        {area === "instances" && (
          <>
            <Button
              disabled={
                !allowed("/operations/journey-instances/create-access") ||
                command.frozen
              }
              onClick={() => begin("enroll")}
            >
              手工入组
            </Button>
            <Button
              disabled={
                !allowed("/operations/journey-instances/control-access") ||
                command.frozen
              }
              onClick={() => begin("control")}
            >
              按实例编号控制
            </Button>
          </>
        )}
        {area === "scans" && (
          <Button
            disabled={
              !allowed("/operations/journey-scans/retry-access") ||
              command.frozen
            }
            onClick={() => begin("scan-retry")}
          >
            按版本恢复扫描
          </Button>
        )}
        {area === "effects" && (
          <Button
            disabled={
              !allowed("/operations/marketing-effects/rebuild-access") ||
              command.frozen
            }
            onClick={() => begin("rebuild")}
          >
            补齐历史投影
          </Button>
        )}
      </Space>
      {command.frozen && (
        <Alert
          type="warning"
          showIcon
          title="操作结果尚未确认"
          description="原目标、输入及幂等键已锁定；只能原样重试，不能用新输入替代原操作。"
          action={
            <Button onClick={() => void retry()} loading={command.busy}>
              原样重试
            </Button>
          }
        />
      )}
      {area === "effects" && (
        <Card title="选择实际分析门店与UTC窗口">
          <Form
            layout="vertical"
            onFinish={(value) => {
              setAfter("");
              setRange({
                store: value.store,
                from: new Date(value.from).toISOString(),
                to: new Date(value.to).toISOString(),
              });
            }}
          >
            <div className="central-journey-fields">
              {field("store", "实际门店编号")}
              {field("from", "起点（含，带时区）")}
              {field("to", "终点（不含，最多93天）")}
            </div>
            <Button htmlType="submit" type="primary">
              读取分析
            </Button>
          </Form>
          <Tabs
            activeKey={series}
            onChange={(value) => {
              setAfter("");
              setSeries(value);
            }}
            items={[
              { key: "campaigns", label: "活动成交" },
              { key: "journeys", label: "生命周期队列" },
              { key: "deliveries", label: "批次券成交" },
              { key: "counts", label: "旅程执行计数" },
            ]}
          />
        </Card>
      )}
      <Card
        title="实际记录"
        extra={
          <Space>
            <Input
              aria-label="分页游标"
              placeholder="实际返回的编号游标"
              value={after}
              onChange={(event) => setAfter(event.target.value)}
            />
            <Button
              onClick={() => {
                setAfter("");
                list.refresh();
              }}
            >
              回到首批
            </Button>
          </Space>
        }
      >
        <ErrorNotice error={list.error} />
        {list.error instanceof ApiError &&
          list.error.status === HTTP.FORBIDDEN && (
            <Alert
              type="info"
              title="当前没有该目录读取权限"
              description="独立操作岗位仍可使用上方已授权操作，直接输入实际目标。"
            />
          )}
        {raw && !Array.isArray(raw) && (
          <Descriptions
            column={1}
            items={[
              {
                key: "basis",
                label: "选择口径",
                children: raw.basis ?? raw.cohort,
              },
              { key: "coverage", label: "覆盖范围", children: raw.coverage },
              { key: "cost", label: "成本口径", children: raw.costBasis },
            ]}
          />
        )}
        <Table
          dataSource={rows}
          loading={list.loading}
          columns={columns}
          rowKey={(row) =>
            String(
              row.instanceId ??
                row.seriesId ??
                row.batchId ??
                row.orderId ??
                (row.content as Row | undefined)?.journeyId ??
                row.journeyId,
            )
          }
          pagination={false}
          scroll={{ x: "max-content" }}
          locale={{
            emptyText:
              area === "effects" && !range
                ? "填写实际门店与时间窗口后读取"
                : "本批没有实际记录",
          }}
        />
        {rows.length === 50 && (
          <Button
            onClick={() => {
              const row = rows.at(-1)!;
              setAfter(
                String(
                  row.instanceId ??
                    row.orderId ??
                    row.seriesId ??
                    (row.content as Row | undefined)?.journeyId ??
                    row.journeyId,
                ),
              );
            }}
          >
            下一批50条
          </Button>
        )}
      </Card>
      {area === "instances" && (
        <Card title="按实例读取固定版本与历史">
          <Space wrap>
            <Input
              aria-label="历史实例编号"
              value={historyId}
              onChange={(e) => setHistoryId(e.target.value)}
            />
            <Button
              disabled={!journeyId.test(historyId)}
              onClick={() => {
                setHistoryTarget(historyId);
                setHistoryAfter(-1);
                history.refresh();
              }}
            >
              读取实际历史
            </Button>
          </Space>
          <ErrorNotice error={history.error} />
          {history.data && (
            <>
              <Descriptions
                column={1}
                items={[
                  {
                    key: "version",
                    label: "固定内容版本",
                    children: history.data.definition.version,
                  },
                  {
                    key: "coverage",
                    label: "历史覆盖",
                    children: history.data.traceCoverage,
                  },
                ]}
              />
              <JourneyMap journey={history.data.definition} />
              <JsonResult value={history.data} />
              {history.data.steps.length === 50 && (
                <Button
                  onClick={() =>
                    setHistoryAfter(
                      Number(history.data!.steps.at(-1)!.transitionVersion),
                    )
                  }
                >
                  继续读取历史
                </Button>
              )}
            </>
          )}
        </Card>
      )}
      {area === "executions" && (
        <Card title="读取指定订单活动参与">
          <Form
            layout="vertical"
            onFinish={(value) =>
              setExecutionTarget({
                order: value.orderId,
                campaign: value.campaignId,
              })
            }
          >
            <div className="central-journey-fields">
              {field("orderId", "实际订单编号")}
              {field("campaignId", "实际活动编号")}
            </div>
            <Button htmlType="submit">读取执行详情</Button>
          </Form>
          <ErrorNotice error={executionDetail.error} />
          {executionDetail.data && <JsonResult value={executionDetail.data} />}
        </Card>
      )}
      <Modal
        centered
        styles={{ body: { maxHeight: "75dvh", overflowY: "auto" } }}
        title={
          open
            ? (
                {
                  create: "创建旅程固定版本",
                  validate: "校验完整定义",
                  preview: "预览真实路径",
                  enroll: "手工入组",
                  control: "控制实例",
                  "scan-retry": "恢复生命周期扫描",
                  rebuild: "补齐历史投影",
                  ...states,
                } as Record<string, string>
              )[open]
            : "操作"
        }
        open={!!open}
        onCancel={close}
        footer={null}
        width={760}
        destroyOnHidden
        maskClosable={false}
      >
        <Form
          form={form}
          layout="vertical"
          disabled={command.busy || command.frozen}
          onValuesChange={() => {
            changed.current = true;
            command.markDirty(true);
          }}
          onFinish={run}
        >
          <div className="central-journey-fields">{fields}</div>
          <Space>
            <Button onClick={close}>关闭</Button>
            <Button type="primary" htmlType="submit" loading={command.busy}>
              提交实际操作
            </Button>
          </Space>
        </Form>
        {command.frozen && (
          <Button onClick={() => void retry()} loading={command.busy}>
            原样重试
          </Button>
        )}
        <ErrorNotice error={command.error} />
        {command.result !== undefined && <JsonResult value={command.result} />}
      </Modal>
      <Modal
        centered
        styles={{ body: { maxHeight: "75dvh", overflowY: "auto" } }}
        title={detailTitle}
        open={detail !== undefined}
        onCancel={() => setDetail(undefined)}
        footer={<Button onClick={() => setDetail(undefined)}>关闭</Button>}
        width={900}
      >
        {detail != null &&
          typeof detail === "object" &&
          "content" in detail && (
            <JourneyMap journey={(detail as Governed<Journey>).content} />
          )}
        <JsonResult value={detail} />
      </Modal>
    </>
  );
}
