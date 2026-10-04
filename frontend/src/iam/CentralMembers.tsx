import { useListFilters } from "../shared/listFilters";
import { CursorBack } from "../shared/pagination";
import { RecordModal } from "../shared/interactions";
import { useRouteState, useCursorState } from "../shared/routeState";
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Select,
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
  CREATE: "create",
  PROFILE: "profile",
  STATUS: "status",
} as const;
type Action = (typeof Action)[keyof typeof Action];
const titles: Record<Action, string> = {
  create: "新建会员",
  profile: "修改资料",
  status: "修改状态",
};
const states: Record<string, string> = {
  ACTIVE: "正常",
  FROZEN: "冻结",
  CLOSED: "已注销",
};
type Member = {
  memberId: string;
  actorId: string;
  displayName: string;
  memberLevel: string;
  status: string;
  version: number;
};
type History = {
  version: number;
  action: string;
  beforeValue: string;
  afterValue: string;
  reason: string;
  actorId: string;
  createdAt: string;
};
type Values = {
  memberId: string;
  actorId?: string;
  displayName?: string;
  memberLevel?: string;
  expectedVersion?: number;
  value?: string;
  reason?: string;
};
const PAGE_SIZE = 50;
const identifier = /^[A-Za-z0-9_-]{1,100}$/;
const denied = (e?: Error) =>
  e instanceof ApiError && e.status === HTTP.FORBIDDEN;

/** 本页凭据仅发送至会员基础能力允许列表，不能退回旧控制台通用请求。 */
function memberClient(context: Context, expired: () => void): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    if (!(
      (method === "GET" &&
        (/^\/admin\/members\?/.test(path) ||
          /^\/admin\/members\/[A-Za-z0-9_-]{1,100}\/history\?/.test(path) ||
          /^\/operations\/members\/(create|profile|status)-access$/.test(
            path,
          ))) ||
      (method === "POST" &&
        /^\/admin\/members(\/[A-Za-z0-9_-]{1,100}\/(profile|status))?$/.test(
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

/** 独立动作不依赖会员读取；未确认的写入只允许原意图重试。 */
export function CentralMembers({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [workspaceTab, setWorkspaceTab] = useRouteState("workspaceTab", "list");
  const [expired, setExpired] = useState(false);
  const dirty = useRef(new Set<Action>());
  const { modal } = App.useApp();
  const client = useMemo(
    () => memberClient(context, () => setExpired(true)),
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
        title: "离开会员管理？",
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
            title="会员管理"
            description="查询会员档案，创建、修改与状态操作分别核验权限。"
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
                  { key: "list", label: "会员档案", children: <MemberList /> },
                  ...Object.values(Action).map((action) => ({
                    key: action,
                    label: titles[action],
                    children: (
                      <MemberCommand
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

function MemberList() {
  const [after, setAfter] = useCursorState("MemberList.after", "");
  const filters = useListFilters("MemberList", "/admin/members", () =>
    setAfter(""),
  );
  const rows = useResource<Member[]>(
    `/admin/members?after=${encodeURIComponent(after)}&${filters.query}`,
  );
  const [selected, setSelected] = useState<Member>();
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Space wrap>
        <Typography.Text>
          当前组织的会员档案；每项修改需单独授权
        </Typography.Text>
        <Button onClick={() => rows.refresh()}>刷新会员</Button>
      </Space>
      {filters.toolbar}
      <ErrorNotice error={rows.error} onRetry={rows.refresh} />
      {!rows.error && (
        <Table<Member>
          rowKey="memberId"
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          scroll={{ x: 850 }}
          columns={[
            { title: "会员编号", dataIndex: "memberId" },
            { title: "姓名", dataIndex: "displayName" },
            { title: "客户账号", dataIndex: "actorId" },
            { title: "等级", dataIndex: "memberLevel" },
            {
              title: "状态",
              dataIndex: "status",
              render: (value) => states[value] ?? value,
            },
            { title: "版本", dataIndex: "version" },
            {
              title: "操作",
              render: (_, row) => (
                <Button onClick={() => setSelected(row)}>查看变更记录</Button>
              ),
            },
          ]}
        />
      )}
      <div className="pager-navigation">
        <CursorBack
          name={"MemberList.after"}
          after={after}
          onPrevious={setAfter}
          initial={""}
          disabled={rows.loading || !!rows.error}
          count={rows.data?.length}
          pageSize={filters.limit}
        />
        <Button
          disabled={!after || rows.loading || !!rows.error}
          onClick={() => setAfter("")}
        >
          回到首页
        </Button>
        <Button
          disabled={
            rows.loading || !!rows.error || rows.data?.length !== filters.limit
          }
          onClick={() => setAfter(rows.data!.at(-1)!.memberId)}
        >
          下一页
        </Button>
      </div>
      <RecordModal
        title={selected ? `${selected.displayName} · 变更记录` : "变更记录"}
        open={!!selected}
        onCancel={() => setSelected(undefined)}
        width={640}
        destroyOnHidden
      >
        {selected && (
          <MemberHistory key={selected.memberId} member={selected} />
        )}
      </RecordModal>
    </Space>
  );
}

function MemberHistory({ member }: { member: Member }) {
  const [after, setAfter] = useCursorState(
    "MemberHistory.after",
    0,
    member.memberId,
  );
  const rows = useResource<History[]>(
    `/admin/members/${encodeURIComponent(member.memberId)}/history?after=${after}&limit=${PAGE_SIZE}`,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Text>会员编号：{member.memberId}</Typography.Text>
      <Typography.Text type="secondary">
        按变更版本排序，时间使用本机时区显示
      </Typography.Text>
      <Button onClick={() => rows.refresh()}>刷新变更记录</Button>
      <ErrorNotice error={rows.error} onRetry={rows.refresh} />
      {!rows.error && (
        <Table<History>
          rowKey="version"
          loading={rows.loading}
          dataSource={rows.data ?? []}
          pagination={false}
          scroll={{ x: 700 }}
          columns={[
            { title: "版本", dataIndex: "version" },
            {
              title: "类型",
              dataIndex: "action",
              render: (value) => (value === "STATUS" ? "状态变更" : "资料变更"),
            },
            {
              title: "变更前",
              render: (_, row) =>
                row.action === "STATUS"
                  ? (states[row.beforeValue] ?? row.beforeValue)
                  : row.beforeValue,
            },
            {
              title: "变更后",
              render: (_, row) =>
                row.action === "STATUS"
                  ? (states[row.afterValue] ?? row.afterValue)
                  : row.afterValue,
            },
            { title: "原因", dataIndex: "reason" },
            { title: "操作人", dataIndex: "actorId" },
            {
              title: "时间",
              dataIndex: "createdAt",
              render: (value) => new Date(value).toLocaleString(),
            },
          ]}
        />
      )}
      <div className="pager-navigation">
        <CursorBack
          name={"MemberHistory.after"}
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
          记录首页
        </Button>
        <Button
          disabled={
            rows.loading || !!rows.error || rows.data?.length !== PAGE_SIZE
          }
          onClick={() => setAfter(rows.data!.at(-1)!.version)}
        >
          下一批记录
        </Button>
      </div>
    </Space>
  );
}

function MemberCommand({
  action,
  client,
  markDirty,
}: {
  action: Action;
  client: typeof request;
  markDirty: (value: boolean) => void;
}) {
  const access = useResource<{ allowed: boolean }>(
    `/operations/members/${action}-access`,
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
    [result, setResult] = useState<Member>();
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
    if (!intent.current)
      intent.current = {
        key: crypto.randomUUID(),
        path: `/admin/members${action === Action.CREATE ? "" : `/${encodeURIComponent(value.memberId)}/${action}`}`,
        body:
          action === Action.CREATE
            ? {
                memberId: value.memberId,
                actorId: value.actorId,
                displayName: value.displayName!.trim(),
                memberLevel: value.memberLevel!.trim(),
              }
            : {
                expectedVersion: value.expectedVersion,
                value: value.value!.trim(),
                reason: value.reason!.trim(),
              },
      };
    try {
      const { path, ...options } = intent.current;
      const saved = await client<Member>(path, { method: "POST", ...options });
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
  const idField = (name: "memberId" | "actorId", label: string) => (
    <Form.Item
      name={name}
      label={label}
      rules={[
        { required: true, message: `请输入${label}` },
        {
          pattern: identifier,
          message: "编号须为1至100位字母、数字、下划线或连字符",
        },
      ]}
    >
      <Input disabled={busy || frozen} maxLength={100} autoComplete="off" />
    </Form.Item>
  );
  const textRules = (label: string, max: number) => [
    { required: true, whitespace: true, message: `请输入${label}` },
    { max, message: `不能超过${max}字` },
  ];
  return (
    <Space
      orientation="vertical"
      size="large"
      style={{ width: "100%", maxWidth: 700 }}
    >
      <Space wrap>
        <Typography.Text>
          {titles[action]}资格与会员读取相互独立
        </Typography.Text>
        <Button disabled={busy} onClick={refresh}>
          重新核验操作权限
        </Button>
      </Space>
      {result && (
        <Alert
          type="success"
          title={`${titles[action]}成功：${result.memberId}`}
          description={`姓名：${result.displayName}；状态：${states[result.status] ?? result.status}；版本：${result.version}。列表需刷新后查看。`}
        />
      )}
      <ErrorNotice error={error} />
      {error instanceof ApiError &&
        error.status === HTTP.CONFLICT &&
        !frozen && (
          <Alert
            type="warning"
            title="请核对会员最新版本和状态后，再提交修改"
          />
        )}
      {frozen && (
        <Alert
          type="warning"
          title="操作结果尚未确认，请保留原输入重试"
          description="离开页面将丢失本次重试信息。若权限已撤销，请联系管理员核对已有结果。"
        />
      )}
      {!denied(access.error) && (
        <ErrorNotice error={access.error} onRetry={access.refresh} />
      )}
      <Card title={titles[action]} loading={access.loading}>
        {canWrite ? (
          <Form
            name={`member-${action}`}
            form={form}
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => markDirty(true)}
          >
            {idField("memberId", "会员编号")}
            {action === Action.CREATE ? (
              <>
                {idField("actorId", "客户账号编号")}
                <Form.Item
                  name="displayName"
                  label="会员姓名"
                  rules={textRules("会员姓名", 128)}
                >
                  <Input disabled={busy || frozen} maxLength={128} />
                </Form.Item>
                <Form.Item
                  name="memberLevel"
                  label="会员等级"
                  rules={textRules("会员等级", 64)}
                >
                  <Input disabled={busy || frozen} maxLength={64} />
                </Form.Item>
              </>
            ) : (
              <>
                <Form.Item
                  name="expectedVersion"
                  label="当前版本"
                  extra="使用已知会员的最新版本；修改权限不会额外授予档案读取。"
                  rules={[{ required: true, message: "请输入当前版本" }]}
                >
                  <InputNumber
                    disabled={busy || frozen}
                    min={0}
                    max={Number.MAX_SAFE_INTEGER}
                    precision={0}
                    style={{ width: "100%" }}
                  />
                </Form.Item>
                {action === Action.PROFILE ? (
                  <Form.Item
                    name="value"
                    label="新的会员姓名"
                    rules={textRules("新的会员姓名", 128)}
                  >
                    <Input disabled={busy || frozen} maxLength={128} />
                  </Form.Item>
                ) : (
                  <Form.Item
                    name="value"
                    label="目标状态"
                    extra="注销后无法恢复，请核实对象和原因。"
                    rules={[{ required: true, message: "请选择目标状态" }]}
                  >
                    <Select
                      disabled={busy || frozen}
                      options={Object.entries(states).map(([value, label]) => ({
                        value,
                        label,
                      }))}
                    />
                  </Form.Item>
                )}
                <Form.Item
                  name="reason"
                  label="变更原因"
                  rules={textRules("变更原因", 256)}
                >
                  <Input.TextArea
                    disabled={busy || frozen}
                    maxLength={256}
                    rows={3}
                  />
                </Form.Item>
              </>
            )}
            <Button type="primary" htmlType="submit" loading={busy}>
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
