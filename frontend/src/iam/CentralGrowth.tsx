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
import { ErrorNotice } from "../shared/ui";
import { CentralError, HTTP, type Context } from "./api";

const Action = {
  POLICY: "policy",
  ADJUST: "adjust",
  RECALCULATE: "recalculate",
} as const;
type Action = (typeof Action)[keyof typeof Action];
const titles: Record<Action, string> = {
  policy: "发布政策",
  adjust: "人工调整",
  recalculate: "重算等级",
};
type Policy = {
  version: number;
  effectiveFrom: string;
  growthPerYuan: string;
  levels: { code: string; minimumGrowth: number }[];
};
type Wallet = {
  memberId: string;
  growth: number;
  netSpend: string;
  memberLevel: string;
  policyVersion: number;
  version: number;
};
type Entry = {
  sequenceId: number;
  sourceId: string;
  delta: number;
  balance: number;
  policyVersion: number;
  reason: string;
  createdAt: string;
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

/** 本页凭据仅发送至会员成长能力允许列表，不能退回旧控制台通用请求。 */
function growthClient(context: Context, expired: () => void): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    if (!(
      (method === "GET" &&
        (/^\/admin\/member-growth\/policies\?/.test(path) ||
          /^\/admin\/member-growth\/[A-Za-z0-9_-]{1,100}(\/ledger\?.*)?$/.test(
            path,
          ) ||
          /^\/operations\/member-growth\/(policy|adjust|recalculate)-access$/.test(
            path,
          ))) ||
      (method === "POST" &&
        /^\/admin\/member-growth\/(policies|[A-Za-z0-9_-]{1,100}\/(adjust|recalculate))$/.test(
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
  const [after, setAfter] = useState(0);
  const rows = useResource<Policy[]>(
    `/admin/member-growth/policies?after=${after}&limit=${PAGE_SIZE}`,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        政策按版本保留，生效时间使用本机时区显示；发布不会自动重算已有会员。
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
            { title: "每元成长值", dataIndex: "growthPerYuan" },
            {
              title: "等级门槛",
              render: (_, row) =>
                row.levels
                  .map((level) => `${level.code} ≥ ${level.minimumGrowth}`)
                  .join("；"),
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
            rows.loading || !!rows.error || rows.data?.length !== PAGE_SIZE
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
        使用已知会员编号查询成长钱包与账本。成长读取权限不包含会员档案读取。
      </Typography.Text>
      <Form
        name="growth-search"
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
          ]}
        >
          <Input maxLength={100} />
        </Form.Item>
        <Button htmlType="submit" type="primary">
          查询成长
        </Button>
      </Form>
      {member && (
        <WalletDetails key={`${member}:${revision}`} member={member} />
      )}
    </Space>
  );
}

function WalletDetails({ member }: { member: string }) {
  const [after, setAfter] = useState(0);
  const wallet = useResource<Wallet>(
    `/admin/member-growth/${encodeURIComponent(member)}`,
  );
  const ledger = useResource<Entry[]>(
    wallet.data && !wallet.error
      ? `/admin/member-growth/${encodeURIComponent(member)}/ledger?after=${after}&limit=${PAGE_SIZE}`
      : null,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <ErrorNotice error={wallet.error} />
      <Card title="成长钱包" loading={wallet.loading}>
        {wallet.data && !wallet.error && <WalletValue value={wallet.data} />}
      </Card>
      <ErrorNotice error={ledger.error} />
      {wallet.data && !wallet.error && !ledger.error && (
        <Table<Entry>
          rowKey="sequenceId"
          dataSource={ledger.data ?? []}
          loading={ledger.loading}
          pagination={false}
          scroll={{ x: 850 }}
          columns={[
            { title: "流水编号", dataIndex: "sequenceId" },
            { title: "来源", dataIndex: "sourceId" },
            { title: "变动", dataIndex: "delta" },
            { title: "变动后余额", dataIndex: "balance" },
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
            ledger.data?.length !== PAGE_SIZE
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
        { key: "growth", label: "成长余额", children: String(value.growth) },
        { key: "spend", label: "净消费金额", children: value.netSpend },
        { key: "level", label: "会员等级", children: value.memberLevel },
        {
          key: "policy",
          label: "政策版本",
          children: String(value.policyVersion),
        },
        { key: "version", label: "账户版本", children: String(value.version) },
      ]}
    />
  );
}

/** 三个写操作保存各自的原始意图，结果未知后不能更换输入或幂等键。 */
function GrowthCommand({
  action,
  client,
  markDirty,
}: {
  action: Action;
  client: typeof request;
  markDirty: (value: boolean) => void;
}) {
  const access = useResource<{ allowed: boolean }>(
    `/operations/member-growth/${action}-access`,
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
          path: `/admin/member-growth/${action === Action.POLICY ? "policies" : `${encodeURIComponent(value.memberId)}/${action}`}`,
          body:
            action === Action.POLICY
              ? {
                  version: value.version,
                  effectiveFrom: new Date(value.effectiveFrom).toISOString(),
                  growthPerYuan: value.growthPerYuan.trim(),
                  levels: value.levels.map((l) => ({
                    code: l.code.trim(),
                    minimumGrowth: l.minimumGrowth,
                  })),
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
            name={`growth-${action}`}
            form={form}
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => markDirty(true)}
            initialValues={action === Action.POLICY ? { levels: [{}] } : {}}
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
                  name="growthPerYuan"
                  label="每元成长值"
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
                <Form.List
                  name="levels"
                  rules={[
                    {
                      validator: (_, levels: Policy["levels"]) =>
                        levels?.length >= 1 &&
                        levels.length <= 8 &&
                        levels[0].minimumGrowth === 0 &&
                        levels.every(
                          (v, i) =>
                            Number.isSafeInteger(v.minimumGrowth) &&
                            (i === 0 ||
                              v.minimumGrowth > levels[i - 1].minimumGrowth),
                        ) &&
                        new Set(levels.map((v) => v.code?.trim())).size ===
                          levels.length
                          ? Promise.resolve()
                          : Promise.reject(
                              new Error(
                                "须有1至8个不同等级，首档门槛为0，其后严格递增",
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
                            fields.length > 1 && (
                              <Button
                                disabled={busy || frozen}
                                onClick={() => {
                                  remove(field.name);
                                  markDirty(true);
                                }}
                              >
                                移除等级 {i + 1}
                              </Button>
                            )
                          }
                        >
                          <Form.Item
                            name={[field.name, "code"]}
                            label={`等级 ${i + 1} 编码`}
                            rules={[
                              {
                                required: true,
                                pattern: identifier,
                                message:
                                  "请输入1至100位字母、数字、下划线或连字符",
                              },
                            ]}
                          >
                            <Input disabled={busy || frozen} maxLength={100} />
                          </Form.Item>
                          {number(
                            [field.name, "minimumGrowth"],
                            `等级 ${i + 1} 门槛`,
                            0,
                            9000000000000000,
                          )}
                        </Card>
                      ))}
                      <Form.ErrorList errors={errors} />
                      <Button
                        disabled={busy || frozen || fields.length >= 8}
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
                      label="调整成长值"
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
                    title="按当前已生效政策重算该会员等级，成长余额不变。"
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
export function CentralGrowth({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [expired, setExpired] = useState(false);
  const dirty = useRef(new Set<Action>());
  const { modal } = App.useApp();
  const client = useMemo(
    () => growthClient(context, () => setExpired(true)),
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
        title: "离开会员成长？",
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
          <Space wrap>
            <Typography.Title level={2} style={{ margin: 0 }}>
              会员成长
            </Typography.Title>
            <Button onClick={logout}>退出登录</Button>
          </Space>
          <Typography.Text type="secondary">
            当前组织：{context.tenant}
          </Typography.Text>
          {expired ? (
            <Alert
              type="warning"
              title="登录已失效，请重新登录"
              action={<Button onClick={() => void onLogout()}>返回登录</Button>}
            />
          ) : (
            <RequestContext.Provider value={client}>
              <Tabs
                items={[
                  {
                    key: "policies",
                    label: "成长政策",
                    children: <PolicyList />,
                  },
                  {
                    key: "wallet",
                    label: "成长钱包",
                    children: <WalletSearch />,
                  },
                  ...Object.values(Action).map((action) => ({
                    key: action,
                    label: titles[action],
                    children: (
                      <GrowthCommand
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
