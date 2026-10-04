import { CursorBack } from "../shared/pagination";
import { useRouteState, useCursorState } from "../shared/routeState";
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Space,
  Table,
  Tabs,
  Typography,
} from "antd";
import { useEffect, useMemo, useRef, useState } from "react";
import {
  ApiError,
  RequestContext,
  type request,
  useResource,
} from "../shared/api";
import { ErrorNotice, time, PageHead } from "../shared/ui";
import { CentralError, HTTP, type Context } from "./api";

const Family = {
  CYCLE: "member-cycles",
  BENEFIT: "member-cycle-benefits",
} as const;
type Family = (typeof Family)[keyof typeof Family];
const Action = {
  PUBLISH: "publish",
  EVALUATE: "evaluate",
  DEFINE: "define",
  GRANT: "grant",
} as const;
type Action = (typeof Action)[keyof typeof Action];
const titles: Record<Action, string> = {
  publish: "发布周期政策",
  evaluate: "考核会员周期",
  define: "定义周期礼包",
  grant: "补发当前周期权益",
};
const families: Record<Action, Family> = {
  publish: Family.CYCLE,
  evaluate: Family.CYCLE,
  define: Family.BENEFIT,
  grant: Family.BENEFIT,
};
type Level = { code: string; minimumGrowth: number };
type Policy = {
  version: number;
  effectiveFrom: string;
  periodDays: number;
  levels: Level[];
};
type Cycle = {
  memberId: string;
  enabled: boolean;
  policyVersion: number;
  cycleStart: string | null;
  cycleEnd: string | null;
  currentGrowth: number;
  retentionGrowth: number;
  memberLevel: string;
  version: number;
};
type BenefitRef = { benefitId: string; version: number };
type Bundle = {
  bindingId: string;
  policyVersion: number;
  level: string;
  storeId: string;
  validUntil: string;
  benefits: BenefitRef[];
};
type Grant = {
  grantId: string;
  benefitId: string;
  name: string;
  status: string;
  units: number;
  remainingUnits: number;
  expiresAt: string;
};
type Receipt = { memberId: string; grants: Grant[] };
type Result = Policy | Cycle | Bundle | Receipt;
type Values = Partial<Policy & Bundle> & { memberId?: string };
const identifier = /^[A-Za-z0-9_-]{1,100}$/,
  PAGE_SIZE = 50,
  MAX_GROWTH = 1_000_000_000_000;
const denied = (error?: Error) =>
  error instanceof ApiError && error.status === HTTP.FORBIDDEN;
const grantStates: Record<string, string> = {
  RESERVED: "已预留",
  REQUESTED: "已受理，待发放",
  AVAILABLE: "可使用",
  CONSUMED: "已使用",
  CANCELLED: "已取消",
  REVOKED: "已撤销",
  COMPENSATION_REQUIRED: "待补偿",
  COMPENSATED: "已补偿",
};

/** 每个页面的凭据只进入本域已登记API，关联政策与权益由服务端Owner读取。 */
function cycleClient(
  context: Context,
  family: Family,
  expired: () => void,
): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    const allowed =
      family === Family.CYCLE
        ? (method === "GET" &&
            (/^\/admin\/member-cycles\/policies\?after=\d+&limit=50$/.test(
              path,
            ) ||
              /^\/admin\/member-cycles\/[A-Za-z0-9_-]{1,100}$/.test(path) ||
              /^\/operations\/member-cycles\/(publish|evaluate)-access$/.test(
                path,
              ))) ||
          (method === "POST" &&
            (path === "/admin/member-cycles/policies" ||
              /^\/admin\/member-cycles\/[A-Za-z0-9_-]{1,100}\/evaluate$/.test(
                path,
              )))
        : (method === "GET" &&
            (/^\/admin\/member-cycle-benefits\?policyVersion=\d+$/.test(path) ||
              /^\/operations\/member-cycle-benefits\/(define|grant)-access$/.test(
                path,
              ))) ||
          (method === "POST" &&
            (path === "/admin/member-cycle-benefits" ||
              /^\/admin\/member-cycle-benefits\/[A-Za-z0-9_-]{1,100}\/grant$/.test(
                path,
              )));
    if (!allowed) throw new ApiError(HTTP.FORBIDDEN, "此入口不支持该操作");
    const headers: Record<string, string> = {
      Authorization: `Bearer ${context.token}`,
      "X-Tenant-Id": context.tenant,
    };
    if (options.body !== undefined)
      headers["Content-Type"] = "application/json";
    if (options.key) headers["Idempotency-Key"] = options.key;
    const response = await fetch(`/v1${path}`, {
      method,
      headers,
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal ?? AbortSignal.timeout(15000),
    }).catch(() => {
      throw new Error("服务连接中断，请稍后重试");
    });
    if (response.status === HTTP.UNAUTHORIZED) expired();
    if (!response.ok)
      throw new ApiError(
        response.status,
        new CentralError(response.status).message,
      );
    return (await response.json()) as T;
  };
}

/** 两个独立入口复用相同表单保护机制，不共享其他域读取或写入资格。 */
export function CentralCycles({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const family =
    location.pathname === "/operations/member-cycle-benefits"
      ? Family.BENEFIT
      : Family.CYCLE;
  const title = family === Family.CYCLE ? "会员周期管理" : "周期权益管理";
  const [workspaceTab, setWorkspaceTab] = useRouteState(
    "workspaceTab",
    family === Family.CYCLE ? "policies" : "bundles",
  );
  const [expired, setExpired] = useState(false),
    dirty = useRef(new Set<Action>());
  const { modal } = App.useApp();
  const client = useMemo(
    () => cycleClient(context, family, () => setExpired(true)),
    [context.token, context.tenant, family],
  );
  useEffect(() => {
    const protect = (event: BeforeUnloadEvent) => {
      if (dirty.current.size) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", protect);
    return () => removeEventListener("beforeunload", protect);
  }, []);
  const logout = () => {
    if (!dirty.current.size) void onLogout();
    else
      modal.confirm({
        title: `离开${title}？`,
        content:
          "未保存输入或未确认结果将离开当前页面。结果未知时请先原样重试确认。",
        okText: "仍然离开",
        cancelText: "留在当前页",
        onOk: onLogout,
      });
  };
  const command = (action: Action) => ({
    key: action,
    label: titles[action],
    children: (
      <CycleCommand
        action={action}
        client={client}
        markDirty={(v) => {
          if (v) dirty.current.add(action);
          else dirty.current.delete(action);
        }}
      />
    ),
  });
  return (
    <main className="central-products">
      <Card>
        <Space orientation="vertical" style={{ width: "100%" }}>
          <PageHead
            eyebrow="企业经营"
            title={title}
            description="核对周期政策、会员考核与当前周期权益。"
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
              <Tabs
                activeKey={workspaceTab}
                onChange={setWorkspaceTab}
                items={
                  family === Family.CYCLE
                    ? [
                        {
                          key: "policies",
                          label: "政策查询",
                          children: <Policies />,
                        },
                        command(Action.PUBLISH),
                        {
                          key: "member",
                          label: "会员周期查询",
                          children: <CycleSearch />,
                        },
                        command(Action.EVALUATE),
                      ]
                    : [
                        {
                          key: "bundles",
                          label: "礼包查询",
                          children: <Bundles />,
                        },
                        command(Action.DEFINE),
                        command(Action.GRANT),
                      ]
                }
              />
            </RequestContext.Provider>
          )}
        </Space>
      </Card>
    </main>
  );
}

function Policies() {
  const [after, setAfter] = useCursorState("Policies.after", 0),
    rows = useResource<Policy[]>(
      `/admin/member-cycles/policies?after=${after}&limit=${PAGE_SIZE}`,
    );
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        政策只追加版本；未来政策到生效时间才接管周期。
      </Typography.Text>
      <ErrorNotice error={rows.error} onRetry={rows.refresh} />
      {!rows.error && (
        <Table<Policy>
          rowKey="version"
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          scroll={{ x: 850 }}
          columns={[
            { title: "版本", dataIndex: "version" },
            { title: "生效时间", dataIndex: "effectiveFrom", render: time },
            { title: "周期天数", dataIndex: "periodDays" },
            {
              title: "等级门槛",
              render: (_, row) =>
                row.levels
                  .map((l) => `${l.code} ≥ ${l.minimumGrowth}`)
                  .join("；"),
            },
          ]}
        />
      )}
      <div className="pager-navigation">
        <Button onClick={rows.refresh}>刷新政策</Button>
        <CursorBack
          name={"Policies.after"}
          after={after}
          onPrevious={setAfter}
          initial={0}
          disabled={rows.loading || !!rows.error}
          count={rows.data?.length}
          pageSize={50}
        />
        <Button
          disabled={!after || rows.loading || !!rows.error}
          onClick={() => setAfter(0)}
        >
          最早政策
        </Button>
        <Button
          disabled={
            rows.loading ||
            !!rows.error ||
            rows.data?.length !== PAGE_SIZE ||
            !Number.isSafeInteger(rows.data?.at(-1)?.version)
          }
          onClick={() => setAfter(rows.data!.at(-1)!.version)}
        >
          下一批政策
        </Button>
      </div>
    </Space>
  );
}
function CycleSearch() {
  const [member, setMember] = useState<string>(),
    [revision, setRevision] = useState(0);
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Form
        name="cycle-search"
        layout="vertical"
        style={{ maxWidth: 700 }}
        onFinish={(v) => {
          setMember(v.memberId);
          setRevision((r) => r + 1);
        }}
      >
        <Form.Item
          name="memberId"
          label="查询会员编号"
          rules={[
            {
              required: true,
              pattern: identifier,
              message: "请输入有效会员编号",
            },
            {
              validator: (_, value) =>
                value !== "policies"
                  ? Promise.resolve()
                  : Promise.reject(
                      new Error("该编号与现有政策路径冲突，请联系管理员核对"),
                    ),
            },
          ]}
        >
          <Input maxLength={100} />
        </Form.Item>
        <Button htmlType="submit" type="primary">
          查询会员周期
        </Button>
      </Form>
      {member && <CycleDetail key={`${member}:${revision}`} member={member} />}
    </Space>
  );
}
function CycleDetail({ member }: { member: string }) {
  const data = useResource<Cycle>(
    `/admin/member-cycles/${encodeURIComponent(member)}`,
  );
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Button onClick={data.refresh}>刷新会员周期</Button>
      <ErrorNotice error={data.error} onRetry={data.refresh} />
      {!data.error &&
        (data.data ? (
          <CycleView value={data.data} />
        ) : (
          <Typography.Text>正在读取周期资料</Typography.Text>
        ))}
    </Space>
  );
}
function CycleView({ value }: { value: Cycle }) {
  return (
    <Card title={`会员 ${value.memberId} · 周期资料`}>
      <Space orientation="vertical" style={{ width: "100%" }}>
        {!value.enabled && (
          <Alert type="info" title="尚无周期考核快照，保留原会员等级" />
        )}
        <Descriptions
          column={{ xs: 1, sm: 2, lg: 3 }}
          items={[
            { key: "member", label: "会员编号", children: value.memberId },
            { key: "level", label: "会员等级", children: value.memberLevel },
            { key: "version", label: "考核版本", children: value.version },
            {
              key: "policy",
              label: "政策版本",
              children: value.enabled ? value.policyVersion : "暂无考核快照",
            },
            {
              key: "start",
              label: "周期开始",
              children: time(value.cycleStart),
            },
            { key: "end", label: "周期结束", children: time(value.cycleEnd) },
            {
              key: "growth",
              label: "本周期成长",
              children: value.currentGrowth,
            },
            {
              key: "retention",
              label: "保级周期成长",
              children: value.retentionGrowth,
            },
          ]}
        />
        <Typography.Text type="secondary">
          展示最后一次考核快照，读取不会触发考核；手动考核需单独授权。
        </Typography.Text>
      </Space>
    </Card>
  );
}
function Bundles() {
  const [version, setVersion] = useState<number>(),
    [revision, setRevision] = useState(0);
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Form
        name="bundle-search"
        layout="vertical"
        style={{ maxWidth: 700 }}
        onFinish={(v) => {
          setVersion(v.policyVersion);
          setRevision((r) => r + 1);
        }}
      >
        <Form.Item
          name="policyVersion"
          label="查询政策版本"
          rules={[
            {
              required: true,
              type: "integer",
              min: 1,
              max: Number.MAX_SAFE_INTEGER,
              message: "请输入正安全整数",
            },
          ]}
        >
          <InputNumber
            min={1}
            max={Number.MAX_SAFE_INTEGER}
            precision={0}
            style={{ width: "100%" }}
          />
        </Form.Item>
        <Button htmlType="submit" type="primary">
          查询周期礼包
        </Button>
      </Form>
      {version && (
        <BundleList key={`${version}:${revision}`} version={version} />
      )}
    </Space>
  );
}
function BundleList({ version }: { version: number }) {
  const rows = useResource<Bundle[]>(
    `/admin/member-cycle-benefits?policyVersion=${version}`,
  );
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Button onClick={rows.refresh}>刷新礼包</Button>
      <ErrorNotice error={rows.error} onRetry={rows.refresh} />
      {!rows.error && (
        <Table<Bundle>
          rowKey="bindingId"
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          scroll={{ x: 1000 }}
          columns={[
            { title: "礼包编号", dataIndex: "bindingId" },
            { title: "政策版本", dataIndex: "policyVersion" },
            { title: "等级", dataIndex: "level" },
            { title: "门店", dataIndex: "storeId" },
            { title: "发放截止", dataIndex: "validUntil", render: time },
            {
              title: "权益引用",
              render: (_, row) =>
                row.benefits
                  .map((b) => `${b.benefitId} / v${b.version}`)
                  .join("；"),
            },
          ]}
        />
      )}
    </Space>
  );
}
function CommandResult({ value, action }: { value: Result; action: Action }) {
  if ("grants" in value)
    return (
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Alert
          type="success"
          title="补发请求已处理"
          description={`会员：${value.memberId}；返回 ${value.grants.length} 项权益。已受理不等于到账，状态以实际结果为准。`}
        />
        {value.grants.length ? (
          <Table<Grant>
            rowKey="grantId"
            dataSource={value.grants}
            pagination={false}
            scroll={{ x: 850 }}
            columns={[
              { title: "权益", dataIndex: "name" },
              {
                title: "状态",
                dataIndex: "status",
                render: (v) => grantStates[v] ?? v,
              },
              { title: "授予编号", dataIndex: "grantId" },
              { title: "总单位", dataIndex: "units" },
              { title: "到期时间", dataIndex: "expiresAt", render: time },
            ]}
          />
        ) : (
          <Typography.Text>当前没有可补发的权益</Typography.Text>
        )}
      </Space>
    );
  if ("enabled" in value)
    return (
      <Space orientation="vertical" style={{ width: "100%" }}>
        <Alert type="success" title={`${titles[action]}成功`} />
        <CycleView value={value} />
      </Space>
    );
  return (
    <Alert
      type="success"
      title={`${titles[action]}成功`}
      description={
        "bindingId" in value
          ? `礼包：${value.bindingId}；政策版本：${value.policyVersion}；等级：${value.level}。礼包列表需重新查询。`
          : `政策版本：${value.version}；周期：${value.periodDays}天；生效时间：${time(value.effectiveFrom)}。政策列表需刷新后查看。`
      }
    />
  );
}

/** 四类写意图分别持有原路径、内容和key；结果未知时不允许换目标重试。 */
function CycleCommand({
  action,
  client,
  markDirty,
}: {
  action: Action;
  client: typeof request;
  markDirty: (value: boolean) => void;
}) {
  const access = useResource<{ allowed: boolean }>(
      `/operations/${families[action]}/${action}-access`,
    ),
    [form] = Form.useForm<Values>();
  const intent = useRef<{ path: string; body: unknown; key: string } | null>(
      null,
    ),
    running = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false),
    [writeDenied, setWriteDenied] = useState(false),
    [error, setError] = useState<Error>(),
    [result, setResult] = useState<Result>();
  const canWrite =
      !!access.data?.allowed &&
      !access.error &&
      !access.loading &&
      !writeDenied,
    disabled = busy || frozen;
  const refresh = () => {
    access.refresh();
    setWriteDenied(false);
  };
  async function submit(value: Values) {
    if (running.current || !canWrite) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    setResult(undefined);
    if (!intent.current) {
      const path =
        action === Action.PUBLISH
          ? "/admin/member-cycles/policies"
          : action === Action.DEFINE
            ? "/admin/member-cycle-benefits"
            : `/admin/${families[action]}/${encodeURIComponent(value.memberId!)}/${action}`;
      const body =
        action === Action.PUBLISH
          ? {
              version: value.version,
              effectiveFrom: new Date(value.effectiveFrom!).toISOString(),
              periodDays: value.periodDays,
              levels: value.levels!.map((l) => ({
                code: l.code,
                minimumGrowth: l.minimumGrowth,
              })),
            }
          : action === Action.DEFINE
            ? {
                bindingId: value.bindingId,
                policyVersion: value.policyVersion,
                level: value.level,
                storeId: value.storeId,
                validUntil: new Date(value.validUntil!).toISOString(),
                benefits: value.benefits!.map((b) => ({
                  benefitId: b.benefitId,
                  version: b.version,
                })),
              }
            : {};
      intent.current = { path, body, key: crypto.randomUUID() };
    }
    try {
      const { path, body, key } = intent.current;
      setResult(await client<Result>(path, { method: "POST", body, key }));
      intent.current = null;
      setFrozen(false);
      markDirty(false);
      form.resetFields();
      refresh();
    } catch (failure) {
      setError(failure as Error);
      if (failure instanceof ApiError && failure.status < 500 && !frozen)
        intent.current = null;
      else {
        setFrozen(true);
        markDirty(true);
      }
      if (denied(failure as Error)) {
        setWriteDenied(true);
        access.refresh();
      }
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  const number = (
    name: string | (string | number)[],
    label: string,
    min = 1,
    max = Number.MAX_SAFE_INTEGER,
  ) => (
    <Form.Item
      name={name}
      label={label}
      rules={[
        {
          required: true,
          type: "integer",
          min,
          max,
          message: `请输入${min}至${max}的整数`,
        },
      ]}
    >
      <InputNumber
        min={min}
        max={max}
        precision={0}
        disabled={disabled}
        style={{ width: "100%" }}
      />
    </Form.Item>
  );
  const id = (name: string | (string | number)[], label: string) => (
    <Form.Item
      name={name}
      label={label}
      rules={[
        {
          required: true,
          pattern: identifier,
          message: "请输入1至100位字母、数字、下划线或连字符",
        },
      ]}
    >
      <Input disabled={disabled} maxLength={100} />
    </Form.Item>
  );
  const date = (name: string, label: string) => (
    <Form.Item
      name={name}
      label={label}
      extra="按本机时区输入，提交时转换为UTC时间。"
      rules={[
        { required: true, message: `请选择${label}` },
        {
          validator: (_, value) =>
            !value || Number.isFinite(new Date(value).getTime())
              ? Promise.resolve()
              : Promise.reject(new Error("时间无效")),
        },
      ]}
    >
      <Input type="datetime-local" disabled={disabled} />
    </Form.Item>
  );
  return (
    <Space
      orientation="vertical"
      size="large"
      style={{ width: "100%", maxWidth: 800 }}
    >
      <Space wrap>
        <Typography.Text>{titles[action]}与读取相互独立</Typography.Text>
        <Button disabled={busy} onClick={refresh}>
          重新核验操作权限
        </Button>
      </Space>
      {result && <CommandResult action={action} value={result} />}
      <ErrorNotice error={error} />
      {error instanceof ApiError &&
        error.status === HTTP.CONFLICT &&
        !frozen && (
          <Alert
            type="warning"
            title="请核对实际版本、会员状态或已有定义，再提交操作"
          />
        )}
      {frozen && (
        <Alert
          type="warning"
          title="操作结果尚未确认，请保留原输入重试"
          description="离开页面将丢失重试信息。若权限已撤销，请联系管理员核对已有结果。"
        />
      )}
      {!denied(access.error) && (
        <ErrorNotice error={access.error} onRetry={access.refresh} />
      )}
      <Card title={titles[action]} loading={access.loading}>
        {canWrite ? (
          <Form
            name={`cycle-${action}`}
            form={form}
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => markDirty(true)}
            initialValues={
              action === Action.PUBLISH
                ? { levels: [{}] }
                : action === Action.DEFINE
                  ? { benefits: [{}] }
                  : {}
            }
          >
            {action === Action.PUBLISH ? (
              <>
                {number("version", "新周期政策版本")}
                {date("effectiveFrom", "周期生效时间")}
                {number("periodDays", "周期天数", 1, 366)}
                <Form.List
                  name="levels"
                  rules={[
                    {
                      validator: (_, levels: Level[]) =>
                        levels?.length >= 1 &&
                        levels.length <= 8 &&
                        new Set(levels.map((l) => l?.code)).size ===
                          levels.length &&
                        levels[0]?.minimumGrowth === 0 &&
                        levels.every(
                          (l, i) =>
                            l &&
                            identifier.test(l.code ?? "") &&
                            Number.isSafeInteger(l.minimumGrowth) &&
                            l.minimumGrowth <= MAX_GROWTH &&
                            (i === 0 ||
                              l.minimumGrowth > levels[i - 1].minimumGrowth),
                        )
                          ? Promise.resolve()
                          : Promise.reject(
                              new Error(
                                "需1至8档唯一等级，首档门槛0，其后递增且不超过1万亿",
                              ),
                            ),
                    },
                  ]}
                >
                  {(fields, { add, remove }, { errors }) => (
                    <Space orientation="vertical" style={{ width: "100%" }}>
                      {fields.map((field, i) => (
                        <Card
                          key={field.key}
                          size="small"
                          title={`等级 ${i + 1}`}
                          extra={
                            <Button
                              disabled={disabled || fields.length === 1}
                              onClick={() => {
                                remove(field.name);
                                markDirty(true);
                              }}
                            >
                              移除等级 {i + 1}
                            </Button>
                          }
                        >
                          {id([field.name, "code"], `等级 ${i + 1} 编号`)}
                          {number(
                            [field.name, "minimumGrowth"],
                            `等级 ${i + 1} 门槛`,
                            0,
                            MAX_GROWTH,
                          )}
                        </Card>
                      ))}
                      <Form.ErrorList errors={errors} />
                      <Button
                        disabled={disabled || fields.length >= 8}
                        onClick={() => {
                          add({});
                          markDirty(true);
                        }}
                      >
                        添加等级
                      </Button>
                    </Space>
                  )}
                </Form.List>
              </>
            ) : action === Action.DEFINE ? (
              <>
                <Typography.Paragraph type="secondary">
                  使用已知政策版本、等级、门店和权益引用。服务端校验真实定义及有效窗口；每政策每等级只能定义一次。
                </Typography.Paragraph>
                {id("bindingId", "礼包编号")}
                {number("policyVersion", "关联周期政策版本")}
                {id("level", "关联等级")}
                {id("storeId", "权益门店编号")}
                {date("validUntil", "礼包发放截止")}
                <Form.List
                  name="benefits"
                  rules={[
                    {
                      validator: (_, refs: BenefitRef[]) =>
                        refs?.length >= 1 &&
                        refs.length <= 8 &&
                        refs.every(
                          (r) =>
                            r &&
                            identifier.test(r.benefitId ?? "") &&
                            Number.isSafeInteger(r.version) &&
                            r.version > 0,
                        ) &&
                        new Set(refs.map((r) => `${r.benefitId}:${r.version}`))
                          .size === refs.length
                          ? Promise.resolve()
                          : Promise.reject(
                              new Error("需1至8项不同的有效权益引用"),
                            ),
                    },
                  ]}
                >
                  {(fields, { add, remove }, { errors }) => (
                    <Space orientation="vertical" style={{ width: "100%" }}>
                      {fields.map((field, i) => (
                        <Card
                          key={field.key}
                          size="small"
                          title={`权益 ${i + 1}`}
                          extra={
                            <Button
                              disabled={disabled || fields.length === 1}
                              onClick={() => {
                                remove(field.name);
                                markDirty(true);
                              }}
                            >
                              移除权益 {i + 1}
                            </Button>
                          }
                        >
                          {id([field.name, "benefitId"], `权益 ${i + 1} 编号`)}
                          {number(
                            [field.name, "version"],
                            `权益 ${i + 1} 版本`,
                          )}
                        </Card>
                      ))}
                      <Form.ErrorList errors={errors} />
                      <Button
                        disabled={disabled || fields.length >= 8}
                        onClick={() => {
                          add({});
                          markDirty(true);
                        }}
                      >
                        添加权益
                      </Button>
                    </Space>
                  )}
                </Form.List>
              </>
            ) : (
              <>
                <Typography.Paragraph type="secondary">
                  {action === Action.EVALUATE
                    ? "按当前生效政策重新考核，可能更新会员等级并产生既有周期事件。"
                    : "仅补发当前周期可获得的权益，不指定历史周期；已授予来源保持幂等。受理后按实际状态确认到账。"}
                </Typography.Paragraph>
                {id("memberId", "会员编号")}
              </>
            )}
            <Form.Item style={{ marginTop: 24, marginBottom: 0 }}>
              <Button
                type="primary"
                loading={busy}
                htmlType={frozen ? "button" : "submit"}
                onClick={
                  frozen ? () => void submit(form.getFieldsValue()) : undefined
                }
              >
                {frozen ? "原样重试" : `确认${titles[action]}`}
              </Button>
            </Form.Item>
          </Form>
        ) : (
          <Typography.Text>
            {denied(access.error) || writeDenied
              ? `当前没有${titles[action]}权限，可联系管理员申请`
              : "操作资格尚未确认，请重新核验权限"}
          </Typography.Text>
        )}
      </Card>
    </Space>
  );
}
