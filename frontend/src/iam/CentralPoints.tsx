import { useRouteState } from "../shared/routeState";
import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  InputNumber,
  Radio,
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
import { ErrorNotice, PageHead } from "../shared/ui";
import { CentralError, HTTP, type Context } from "./api";

const Action = {
  POLICY: "policy",
  ADJUST: "adjust",
  EXPIRE: "expire",
} as const;
type Action = (typeof Action)[keyof typeof Action];
const titles: Record<Action, string> = {
  policy: "发布积分政策",
  adjust: "人工调整积分",
  expire: "推进积分到期",
};
type Policy = {
  version: number;
  effectiveFrom: string;
  earnPerYuan: string;
  expiryDays: number;
  spendEnabled: boolean;
  pointsPerYuan: number;
  maxDeductionBps: number;
};
type Wallet = {
  memberId: string;
  available: number;
  held: number;
  debt: number;
  credit: number;
  version: number;
};
type Entry = {
  sequenceId: number;
  action: string;
  sourceId: string;
  delta: number;
  available: number;
  held: number;
  debt: number;
  policyVersion: number;
  reason: string;
  createdAt: string;
};
const actions: Record<string, string> = {
  EARN: "订单奖励",
  ADJUST: "人工调整",
  REVOKE: "奖励扣回",
  EXPIRE: "到期",
  HOLD: "冻结",
  SPEND: "消费",
  RELEASE: "解冻",
  REFUND: "退款",
  EXCHANGE: "兑换",
};
type Values = Policy & {
  memberId: string;
  expectedVersion: number;
  delta: number;
  reason: string;
};
const PAGE_SIZE = 50;
const identifier = /^[A-Za-z0-9_-]{1,100}$/;
const denied = (e?: Error) =>
  e instanceof ApiError && e.status === HTTP.FORBIDDEN;

/** 本页凭据仅发送至会员积分能力允许列表，不能退回旧控制台通用请求。 */
function pointsClient(context: Context, expired: () => void): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    if (!(
      (method === "GET" &&
        (/^\/admin\/member-points\/policies\?after=\d+&limit=50$/.test(path) ||
          /^\/admin\/member-points\/[A-Za-z0-9_-]{1,100}(\/ledger\?after=\d+&limit=50)?$/.test(
            path,
          ) ||
          /^\/operations\/member-points\/(policy|adjust|expire)-access$/.test(
            path,
          ))) ||
      (method === "POST" &&
        /^\/admin\/member-points\/(policies|[A-Za-z0-9_-]{1,100}\/(adjust|expire))$/.test(
          path,
        ))
    ))
      throw new ApiError(HTTP.FORBIDDEN, "此入口不支持该操作");
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

function PolicyList() {
  const [after, setAfter] = useRouteState("PolicyList.after", 0);
  const rows = useResource<Policy[]>(
    `/admin/member-points/policies?after=${after}&limit=${PAGE_SIZE}`,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        政策按版本保留，生效时间使用本机时区显示；发布不会自动赠送积分或改变历史订单奖励。
      </Typography.Text>
      <Button onClick={rows.refresh}>刷新政策</Button>
      <ErrorNotice error={rows.error} />
      {!rows.error && (
        <Table<Policy>
          rowKey="version"
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          scroll={{ x: 750 }}
          columns={[
            { title: "版本", dataIndex: "version" },
            {
              title: "生效时间",
              dataIndex: "effectiveFrom",
              render: (v) => new Date(v).toLocaleString(),
            },
            { title: "每元获取积分", dataIndex: "earnPerYuan" },
            { title: "有效天数", dataIndex: "expiryDays" },
            {
              title: "消费抵扣",
              dataIndex: "spendEnabled",
              render: (v) => (v ? "启用" : "停用"),
            },
            { title: "抵扣一元所需积分", dataIndex: "pointsPerYuan" },
            {
              title: "最高抵扣比例",
              dataIndex: "maxDeductionBps",
              render: (v) => `${v / 100}%`,
            },
          ]}
        />
      )}
      <Space>
        <Button
          disabled={!after || rows.loading || !!rows.error}
          onClick={() => setAfter(0)}
        >
          政策首页
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
      </Space>
    </Space>
  );
}

function WalletSearch() {
  const [member, setMember] = useState<string>();
  const [revision, setRevision] = useState(0);
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        使用已知会员编号查询积分钱包与账本。积分读取权限不包含会员档案读取。
      </Typography.Text>
      <Form
        name="points-search"
        layout="vertical"
        onFinish={(v) => {
          setMember(v.memberId);
          setRevision((n) => n + 1);
        }}
        style={{ maxWidth: 700, width: "100%" }}
      >
        <Form.Item
          name="memberId"
          label="查询会员编号"
          rules={[
            {
              required: true,
              pattern: identifier,
              message: "请输入1至100位字母、数字、下划线或连字符",
            },
            {
              validator: (_, v) =>
                v !== "policies"
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
          查询积分
        </Button>
      </Form>
      {member && (
        <WalletDetails key={`${member}:${revision}`} member={member} />
      )}
    </Space>
  );
}

function WalletDetails({ member }: { member: string }) {
  const [after, setAfter] = useRouteState("WalletDetails.after", 0);
  const wallet = useResource<Wallet>(
    `/admin/member-points/${encodeURIComponent(member)}`,
  );
  const ledger = useResource<Entry[]>(
    wallet.data && !wallet.error
      ? `/admin/member-points/${encodeURIComponent(member)}/ledger?after=${after}&limit=${PAGE_SIZE}`
      : null,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <ErrorNotice error={wallet.error} />
      <Card title="积分钱包" loading={wallet.loading}>
        {wallet.data && !wallet.error && <WalletValue value={wallet.data} />}
      </Card>
      <ErrorNotice error={ledger.error} />
      {wallet.data && !wallet.error && !ledger.error && (
        <Table<Entry>
          rowKey="sequenceId"
          dataSource={ledger.data ?? []}
          loading={ledger.loading}
          pagination={false}
          scroll={{ x: 1300 }}
          columns={[
            { title: "流水编号", dataIndex: "sequenceId" },
            { title: "来源", dataIndex: "sourceId" },
            { title: "变动", dataIndex: "delta" },
            {
              title: "动作",
              dataIndex: "action",
              render: (v) => actions[v] ?? v,
            },
            { title: "变动后可用", dataIndex: "available" },
            { title: "冻结", dataIndex: "held" },
            { title: "待偿扣回", dataIndex: "debt" },
            { title: "政策版本", dataIndex: "policyVersion" },
            { title: "原因", dataIndex: "reason" },
            {
              title: "时间",
              dataIndex: "createdAt",
              render: (v) => new Date(v).toLocaleString(),
            },
          ]}
        />
      )}
      <Space>
        <Button
          disabled={
            !after || ledger.loading || !!ledger.error || !!wallet.error
          }
          onClick={() => setAfter(0)}
        >
          账本首页
        </Button>
        <Button
          disabled={
            ledger.loading ||
            !!ledger.error ||
            !!wallet.error ||
            ledger.data?.length !== PAGE_SIZE ||
            !Number.isSafeInteger(ledger.data?.at(-1)?.sequenceId)
          }
          onClick={() => setAfter(ledger.data!.at(-1)!.sequenceId)}
        >
          下一批流水
        </Button>
      </Space>
    </Space>
  );
}

function WalletValue({ value }: { value: Wallet }) {
  return (
    <Descriptions
      column={{ xs: 1, sm: 2, lg: 3 }}
      items={[
        { key: "id", label: "会员编号", children: value.memberId },
        {
          key: "available",
          label: "可用积分",
          children: String(value.available),
        },
        { key: "held", label: "冻结积分", children: String(value.held) },
        { key: "debt", label: "待偿扣回积分", children: String(value.debt) },
        {
          key: "credit",
          label: "有效批次余额",
          children: String(value.credit),
        },
        { key: "version", label: "账户版本", children: String(value.version) },
      ]}
    />
  );
}

/** 三个写操作保存各自的原始意图，结果未知后不能更换输入或幂等键。 */
function PointsCommand({
  action,
  client,
  markDirty,
}: {
  action: Action;
  client: typeof request;
  markDirty: (value: boolean) => void;
}) {
  const access = useResource<{ allowed: boolean }>(
    `/operations/member-points/${action}-access`,
  );
  const [form] = Form.useForm<Values>();
  const intent = useRef<{ path: string; key: string; body: unknown } | null>(
    null,
  );
  const running = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false),
    [writeDenied, setWriteDenied] = useState(false);
  const [error, setError] = useState<Error>(),
    [result, setResult] = useState<Wallet | Policy>();
  const canWrite =
    !!access.data?.allowed && !access.error && !access.loading && !writeDenied;
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
    try {
      if (!intent.current)
        intent.current = {
          key: crypto.randomUUID(),
          path: `/admin/member-points/${action === Action.POLICY ? "policies" : `${encodeURIComponent(value.memberId)}/${action}`}`,
          body:
            action === Action.POLICY
              ? {
                  version: value.version,
                  effectiveFrom: new Date(value.effectiveFrom).toISOString(),
                  earnPerYuan: value.earnPerYuan.trim(),
                  expiryDays: value.expiryDays,
                  spendEnabled: value.spendEnabled,
                  pointsPerYuan: value.pointsPerYuan,
                  maxDeductionBps: value.maxDeductionBps,
                }
              : action === Action.ADJUST
                ? {
                    expectedVersion: value.expectedVersion,
                    delta: value.delta,
                    reason: value.reason.trim(),
                  }
                : {},
        };
      const { path, ...options } = intent.current;
      const saved = await client<Wallet | Policy>(path, {
        method: "POST",
        ...options,
      });
      setResult(saved);
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
    min = 0,
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
        disabled={busy || frozen}
        min={min}
        max={max}
        precision={0}
        style={{ width: "100%" }}
      />
    </Form.Item>
  );
  return (
    <Space
      orientation="vertical"
      size="large"
      style={{ width: "100%", maxWidth: 700 }}
    >
      <Space wrap>
        <Typography.Text>
          {titles[action]}与读取、其他修改相互独立
        </Typography.Text>
        <Button disabled={busy} onClick={refresh}>
          重新核验操作权限
        </Button>
      </Space>
      {result && (
        <Alert
          type="success"
          title={`${titles[action]}成功`}
          description={
            "memberId" in result ? (
              <WalletValue value={result} />
            ) : (
              `政策版本：${result.version}；生效时间：${new Date(result.effectiveFrom).toLocaleString()}。政策列表需刷新后查看。`
            )
          }
        />
      )}
      <ErrorNotice error={error} />
      {error instanceof ApiError &&
        error.status === HTTP.CONFLICT &&
        !frozen && (
          <Alert
            type="warning"
            title="请核对最新版本和当前状态后，再提交操作"
          />
        )}
      {frozen && (
        <Alert
          type="warning"
          title="操作结果尚未确认，请保留原输入重试"
          description="离开页面将丢失本次重试信息。若权限已撤销，请联系管理员核对已有结果。"
        />
      )}
      {!denied(access.error) && <ErrorNotice error={access.error} />}
      <Card title={titles[action]} loading={access.loading}>
        {canWrite ? (
          <Form
            name={`points-${action}`}
            form={form}
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => markDirty(true)}
            initialValues={{}}
          >
            {action === Action.POLICY ? (
              <>
                {number("version", "新政策版本", 1)}
                <Form.Item
                  name="effectiveFrom"
                  label="生效时间"
                  extra="按本机时区输入；发布后政策版本不可修改。"
                  rules={[
                    { required: true, message: "请选择生效时间" },
                    {
                      validator: (_, v) =>
                        !v || Number.isFinite(new Date(v).getTime())
                          ? Promise.resolve()
                          : Promise.reject(new Error("生效时间无效")),
                    },
                  ]}
                >
                  <Input type="datetime-local" disabled={busy || frozen} />
                </Form.Item>
                <Form.Item
                  name="earnPerYuan"
                  label="每元获取积分"
                  rules={[
                    {
                      required: true,
                      pattern: /^(?:\d{1,3}(?:\.\d{1,2})?|1000(?:\.0{1,2})?)$/,
                      message: "请输入0至1000，最多两位小数",
                    },
                  ]}
                >
                  <Input
                    disabled={busy || frozen}
                    maxLength={16}
                    inputMode="decimal"
                  />
                </Form.Item>
                {number("expiryDays", "积分有效天数", 1, 366)}
                <Form.Item
                  name="spendEnabled"
                  label="消费抵扣"
                  rules={[
                    { required: true, message: "请选择是否启用消费抵扣" },
                  ]}
                >
                  <Radio.Group
                    disabled={busy || frozen}
                    options={[
                      { value: true, label: "启用" },
                      { value: false, label: "停用" },
                    ]}
                  />
                </Form.Item>
                {number("pointsPerYuan", "抵扣一元所需积分", 1, 100000)}
                {number("maxDeductionBps", "最高抵扣比例（基点）", 0, 10000)}
                <Typography.Text type="secondary">
                  10000基点代表100%，5000代表50%；是否允许抵扣由政策和实际结算共同校验。
                </Typography.Text>
              </>
            ) : (
              <>
                <Form.Item
                  name="memberId"
                  label="会员编号"
                  rules={[
                    {
                      required: true,
                      pattern: identifier,
                      message: "请输入1至100位字母、数字、下划线或连字符",
                    },
                  ]}
                >
                  <Input disabled={busy || frozen} maxLength={100} />
                </Form.Item>
                {action === Action.ADJUST ? (
                  <>
                    {number("expectedVersion", "当前账户版本")}
                    <Form.Item
                      name="delta"
                      label="调整积分值"
                      extra="正数增加、负数扣减，不能为0。"
                      rules={[
                        {
                          required: true,
                          type: "integer",
                          min: -1000000000,
                          max: 1000000000,
                          message: "请输入绝对值不超过10亿的整数",
                        },
                        {
                          validator: (_, v) =>
                            v !== 0
                              ? Promise.resolve()
                              : Promise.reject(new Error("调整值不能为0")),
                        },
                      ]}
                    >
                      <InputNumber
                        disabled={busy || frozen}
                        min={-1000000000}
                        max={1000000000}
                        precision={0}
                        style={{ width: "100%" }}
                      />
                    </Form.Item>
                    <Form.Item
                      name="reason"
                      label="调整原因"
                      rules={[
                        {
                          required: true,
                          whitespace: true,
                          max: 256,
                          message: "请输入不超过256字的调整原因",
                        },
                      ]}
                    >
                      <Input.TextArea
                        rows={3}
                        disabled={busy || frozen}
                        maxLength={256}
                      />
                    </Form.Item>
                  </>
                ) : (
                  <Alert
                    type="info"
                    title="每次最多推进100个已到期积分批次；未到期批次不受影响，查询钱包不会触发此操作。"
                  />
                )}
              </>
            )}
            <Button
              type="primary"
              htmlType="submit"
              loading={busy}
              style={{ marginTop: 24 }}
            >
              {frozen ? "原样重试" : `确认${titles[action]}`}
            </Button>
          </Form>
        ) : (
          <Typography.Text type="secondary">
            {denied(access.error) || writeDenied
              ? `当前没有${titles[action]}权限，可联系管理员申请`
              : "操作资格尚未确认，请重新核验权限"}
          </Typography.Text>
        )}
      </Card>
    </Space>
  );
}

/** 独立动作不依赖会员读取；未确认的写入只允许原意图重试。 */
export function CentralPoints({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [workspaceTab, setWorkspaceTab] = useRouteState(
    "workspaceTab",
    "policies",
  );
  const [expired, setExpired] = useState(false);
  const dirty = useRef(new Set<Action>());
  const { modal } = App.useApp();
  const client = useMemo(
    () => pointsClient(context, () => setExpired(true)),
    [context.token, context.tenant],
  );
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (dirty.current.size) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", warn);
    return () => removeEventListener("beforeunload", warn);
  }, []);
  const logout = () => {
    if (!dirty.current.size) void onLogout();
    else
      modal.confirm({
        title: "离开会员积分？",
        content:
          "未保存输入或未确认结果将离开当前页面。结果未知时请先原样重试确认，避免重复操作。",
        okText: "仍然离开",
        cancelText: "留在当前页",
        onOk: onLogout,
      });
  };
  return (
    <main className="central-products">
      <Card>
        <Space orientation="vertical" style={{ width: "100%" }}>
          <PageHead
            eyebrow="企业经营"
            title="会员积分"
            description="查询会员积分、账本与独立授权的积分操作。"
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
                items={[
                  {
                    key: "policies",
                    label: "积分政策",
                    children: <PolicyList />,
                  },
                  {
                    key: "wallet",
                    label: "积分钱包",
                    children: <WalletSearch />,
                  },
                  ...Object.values(Action).map((action) => ({
                    key: action,
                    label: titles[action],
                    children: (
                      <PointsCommand
                        action={action}
                        client={client}
                        markDirty={(value) => {
                          if (value) dirty.current.add(action);
                          else dirty.current.delete(action);
                        }}
                      />
                    ),
                  })),
                ]}
              />
            </RequestContext.Provider>
          )}
        </Space>
      </Card>
    </main>
  );
}
