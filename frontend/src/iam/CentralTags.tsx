import { useListFilters } from "../shared/listFilters";
import { CursorBack } from "../shared/pagination";
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

const Action = { DEFINE: "define", ASSIGN: "assign" } as const;
const TagOperation = { ASSIGN: "ASSIGN", REMOVE: "REMOVE" } as const;
type Action = (typeof Action)[keyof typeof Action];
const titles: Record<Action, string> = {
  define: "定义标签",
  assign: "分配与撤销",
};
type Definition = { tagId: string; name: string };
type Assignment = {
  tagId: string;
  active: boolean;
  version: number;
  source: string;
  reason: string;
};
type Values = {
  tagId: string;
  name?: string;
  memberId?: string;
  expectedVersion?: number;
  operation?: (typeof TagOperation)[keyof typeof TagOperation];
  reason?: string;
};
const PAGE_SIZE = 50;
const identifier = /^[A-Za-z0-9_-]{1,100}$/;
const denied = (e?: Error) =>
  e instanceof ApiError && e.status === HTTP.FORBIDDEN;

/** 本页凭据仅发送至会员标签能力允许列表，不能退回旧控制台通用请求。 */
function tagClient(context: Context, expired: () => void): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    if (!(
      (method === "GET" &&
        (/^\/admin\/member-tags\?/.test(path) ||
          /^\/admin\/member-tags\/[A-Za-z0-9_-]{1,100}\/assignments\?/.test(
            path,
          ) ||
          /^\/operations\/member-tags\/(define|assign)-access$/.test(path))) ||
      (method === "POST" &&
        /^\/admin\/member-tags(\/[A-Za-z0-9_-]{1,100}\/assign)?$/.test(path))
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
export function CentralTags({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [workspaceTab, setWorkspaceTab] = useRouteState(
    "workspaceTab",
    "dictionary",
  );
  const [expired, setExpired] = useState(false);
  const dirty = useRef(new Set<Action>());
  const { modal } = App.useApp();
  const client = useMemo(
    () => tagClient(context, () => setExpired(true)),
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
        title: "离开会员标签管理？",
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
            title="会员标签管理"
            description="维护标签字典，将分配操作与定义权限分开。"
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
                    key: "dictionary",
                    label: "标签字典",
                    children: <TagDictionary />,
                  },
                  {
                    key: "assignments",
                    label: "会员标签",
                    children: <AssignmentSearch />,
                  },
                  ...Object.values(Action).map((action) => ({
                    key: action,
                    label: titles[action],
                    children: (
                      <TagCommand
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

function TagDictionary() {
  const [after, setAfter] = useCursorState("TagDictionary.after", "");
  const filters = useListFilters(
    "TagDictionary.after",
    "/admin/member-tags",
    () => setAfter(""),
  );
  const rows = useResource<Definition[]>(
    `/admin/member-tags?after=${encodeURIComponent(after)}&${filters.query}`,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Space wrap>
        <Typography.Text>
          当前组织的标签字典；定义和分配分别授权
        </Typography.Text>
        <Button onClick={rows.refresh}>刷新字典</Button>
      </Space>
      {filters.toolbar}
      <ErrorNotice error={rows.error} onRetry={rows.refresh} />
      {!rows.error && (
        <Table<Definition>
          rowKey="tagId"
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          scroll={{ x: 550 }}
          columns={[
            { title: "标签编号", dataIndex: "tagId" },
            { title: "标签名称", dataIndex: "name" },
          ]}
        />
      )}
      <div className="pager-navigation">
        <CursorBack
          name={"TagDictionary.after"}
          after={after}
          onPrevious={setAfter}
          initial={""}
          disabled={rows.loading || !!rows.error}
          count={rows.data?.length}
          pageSize={50}
        />
        <Button
          disabled={!after || rows.loading || !!rows.error}
          onClick={() => setAfter("")}
        >
          字典首页
        </Button>
        <Button
          disabled={
            rows.loading || !!rows.error || rows.data?.length !== filters.limit
          }
          onClick={() => setAfter(rows.data!.at(-1)!.tagId)}
        >
          下一批标签
        </Button>
      </div>
    </Space>
  );
}

function AssignmentSearch() {
  const [member, setMember] = useState<string>();
  const [revision, setRevision] = useState(0);
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        使用已知会员编号查询标签；此权限不额外授予会员档案或成长数据读取。
      </Typography.Text>
      <Form
        name="tag-search"
        layout="vertical"
        onFinish={(v) => {
          setMember(v.memberId);
          setRevision((n) => n + 1);
        }}
        style={{ width: "100%", maxWidth: 700 }}
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
        <Button type="primary" htmlType="submit">
          查询会员标签
        </Button>
      </Form>
      {member && <Assignments key={`${member}:${revision}`} member={member} />}
    </Space>
  );
}

function Assignments({ member }: { member: string }) {
  const [after, setAfter] = useCursorState("Assignments.after", "", member);
  const rows = useResource<Assignment[]>(
    `/admin/member-tags/${encodeURIComponent(member)}/assignments?after=${encodeURIComponent(after)}&limit=${PAGE_SIZE}`,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Text>
        会员编号：{member}；撤销的关联仍保留，便于核对版本和原因。
      </Typography.Text>
      <ErrorNotice error={rows.error} onRetry={rows.refresh} />
      {!rows.error && (
        <Table<Assignment>
          rowKey="tagId"
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          scroll={{ x: 750 }}
          columns={[
            { title: "标签编号", dataIndex: "tagId" },
            {
              title: "关联状态",
              dataIndex: "active",
              render: (v) => (v ? "已分配" : "已撤销"),
            },
            { title: "关联版本", dataIndex: "version" },
            {
              title: "来源",
              dataIndex: "source",
              render: (v) => (v === "MANUAL" ? "人工维护" : v),
            },
            { title: "原因", dataIndex: "reason" },
          ]}
        />
      )}
      <div className="pager-navigation">
        <CursorBack
          name={"Assignments.after"}
          after={after}
          onPrevious={setAfter}
          initial={""}
          disabled={rows.loading || !!rows.error}
          count={rows.data?.length}
          pageSize={50}
        />
        <Button
          disabled={!after || rows.loading || !!rows.error}
          onClick={() => setAfter("")}
        >
          关联首页
        </Button>
        <Button
          disabled={
            rows.loading || !!rows.error || rows.data?.length !== PAGE_SIZE
          }
          onClick={() => setAfter(rows.data!.at(-1)!.tagId)}
        >
          下一批关联
        </Button>
      </div>
    </Space>
  );
}

/** 未知结果后保留原会员、标签、操作和幂等键，不能靠修改输入开始第二次写入。 */
function TagCommand({
  action,
  client,
  markDirty,
}: {
  action: Action;
  client: typeof request;
  markDirty: (value: boolean) => void;
}) {
  const access = useResource<{ allowed: boolean }>(
    `/operations/member-tags/${action}-access`,
  );
  const [form] = Form.useForm<Values>();
  const intent = useRef<{
    path: string;
    key: string;
    body: unknown;
    member?: string;
  } | null>(null);
  const running = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false),
    [writeDenied, setWriteDenied] = useState(false);
  const [error, setError] = useState<Error>(),
    [result, setResult] = useState<{
      value: Definition | Assignment;
      member?: string;
    }>();
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
        member: action === Action.ASSIGN ? value.memberId : undefined,
        path: `/admin/member-tags${action === Action.DEFINE ? "" : `/${encodeURIComponent(value.memberId!)}/assign`}`,
        body:
          action === Action.DEFINE
            ? { tagId: value.tagId, name: value.name!.trim() }
            : {
                tagId: value.tagId,
                expectedVersion: value.expectedVersion,
                active: value.operation === TagOperation.ASSIGN,
                reason: value.reason!.trim(),
              },
      };
    try {
      const { path, key, body, member } = intent.current;
      const saved = await client<Definition | Assignment>(path, {
        method: "POST",
        key,
        body,
      });
      setResult({ value: saved, member });
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
  const idField = (name: "tagId" | "memberId", label: string) => (
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
      <Input disabled={busy || frozen} maxLength={100} autoComplete="off" />
    </Form.Item>
  );
  return (
    <Space
      orientation="vertical"
      size="large"
      style={{ width: "100%", maxWidth: 700 }}
    >
      <Space wrap>
        <Typography.Text>{titles[action]}与读取相互独立</Typography.Text>
        <Button disabled={busy} onClick={refresh}>
          重新核验操作权限
        </Button>
      </Space>
      {result && (
        <Alert
          type="success"
          title={`${titles[action]}成功：${result.value.tagId}`}
          description={
            "name" in result.value
              ? `标签名称：${result.value.name}。字典需刷新后查看。`
              : `会员：${result.member}；状态：${result.value.active ? "已分配" : "已撤销"}；关联版本：${result.value.version}。关联列表需重新查询后查看。`
          }
        />
      )}
      <ErrorNotice error={error} />
      {error instanceof ApiError &&
        error.status === HTTP.CONFLICT &&
        !frozen && (
          <Alert
            type="warning"
            title="请核对标签是否已存在或关联最新版本，再提交操作"
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
            name={`tag-${action}`}
            form={form}
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => markDirty(true)}
          >
            {action === Action.ASSIGN && idField("memberId", "会员编号")}
            {idField("tagId", "标签编号")}
            {action === Action.DEFINE ? (
              <Form.Item
                name="name"
                label="标签名称"
                rules={[
                  {
                    required: true,
                    whitespace: true,
                    max: 64,
                    message: "请输入不超过64字的标签名称",
                  },
                ]}
              >
                <Input maxLength={64} disabled={busy || frozen} />
              </Form.Item>
            ) : (
              <>
                <Form.Item
                  name="expectedVersion"
                  label="当前关联版本"
                  extra="首次关联填0，后续使用该会员与标签的最新关联版本。"
                  rules={[
                    {
                      required: true,
                      type: "integer",
                      min: 0,
                      max: Number.MAX_SAFE_INTEGER,
                      message: "请输入非负安全整数",
                    },
                  ]}
                >
                  <InputNumber
                    disabled={busy || frozen}
                    min={0}
                    max={Number.MAX_SAFE_INTEGER}
                    precision={0}
                    style={{ width: "100%" }}
                  />
                </Form.Item>
                <Form.Item
                  name="operation"
                  label="标签操作"
                  extra="撤销保留历史关联；注销会员不能变更标签，单会员最多64个活跃标签。"
                  rules={[{ required: true, message: "请选择分配或撤销" }]}
                >
                  <Select
                    disabled={busy || frozen}
                    options={[
                      { value: TagOperation.ASSIGN, label: "分配标签" },
                      { value: TagOperation.REMOVE, label: "撤销标签" },
                    ]}
                  />
                </Form.Item>
                <Form.Item
                  name="reason"
                  label="操作原因"
                  rules={[
                    {
                      required: true,
                      whitespace: true,
                      max: 256,
                      message: "请输入不超过256字的操作原因",
                    },
                  ]}
                >
                  <Input.TextArea
                    rows={3}
                    maxLength={256}
                    disabled={busy || frozen}
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
