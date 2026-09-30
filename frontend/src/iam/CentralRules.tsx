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
import type { Rule } from "../shared/contracts";
import { RuleEditor, RuleSummary } from "../shared/marketing";
import { ErrorNotice } from "../shared/ui";
import { CentralError, HTTP, type Context } from "./api";

type RuleContent = {
  ruleId: string;
  version: number;
  name: string;
  rule: Rule;
};
type RuleView = { content: RuleContent; status: string };
type Operation = "create" | "publish";
type InputValue = {
  ruleId: string;
  version: number;
  name?: string;
  rule?: Rule;
};
type Intent = { path: string; key: string; body?: RuleContent };
const CREATE: Operation = "create",
  PUBLISH: Operation = "publish";
const PAGE_SIZE = 50,
  identifier = /^[A-Za-z0-9_.:-]{1,64}$/;
const labels = { create: "创建规则", publish: "发布规则" };
const denied = (error?: Error) =>
  error instanceof ApiError && error.status === HTTP.FORBIDDEN;

/** 只允许规则固定入口；租户与Token不进入任意URL或旧控制台请求。 */
function ruleClient(context: Context, expired: () => void): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    let directory = false,
      publication = false;
    if (path.startsWith("/admin/rules?")) {
      const query = new URLSearchParams(path.slice(path.indexOf("?") + 1));
      directory =
        Array.from(query.keys()).length === 2 &&
        query.has("after") &&
        query.get("limit") === String(PAGE_SIZE) &&
        (query.get("after") === "" ||
          identifier.test(query.get("after") ?? ""));
    }
    const match = /^\/admin\/rules\/([^/]+)\/([1-9][0-9]*)\/publish$/.exec(
      path,
    );
    if (match) {
      try {
        publication =
          identifier.test(decodeURIComponent(match[1])) &&
          Number.isSafeInteger(Number(match[2]));
      } catch {
        publication = false;
      }
    }
    if (!(
      (method === "GET" &&
        (directory ||
          path === "/admin/rule-fields" ||
          path === "/operations/rules/create-access" ||
          path === "/operations/rules/publish-access")) ||
      (method === "POST" && (path === "/admin/rules" || publication))
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

function Directory() {
  const [after, setAfter] = useState("");
  const rows = useResource<RuleView[]>(
    `/admin/rules?after=${encodeURIComponent(after)}&limit=${PAGE_SIZE}`,
  );
  const fields = useResource<Record<string, string>>("/admin/rule-fields");
  return (
    <Space
      orientation="vertical"
      style={{ width: "100%" }}
      styles={{ item: { minWidth: 0, maxWidth: "100%" } }}
    >
      <Typography.Text type="secondary">
        目录显示每个规则的最新内容版本。旧版本已发布，不代表最新草稿已发布；发布时请填写实际版本。
      </Typography.Text>
      <Button
        disabled={rows.loading || fields.loading}
        onClick={() => {
          rows.refresh();
          fields.refresh();
        }}
      >
        刷新规则目录
      </Button>
      <ErrorNotice error={rows.error} />
      {!rows.error && (
        <Table<RuleView>
          rowKey={(r) => r.content.ruleId}
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          style={{ maxWidth: "100%" }}
          scroll={{ x: 850 }}
          columns={[
            { title: "规则编号", dataIndex: ["content", "ruleId"] },
            { title: "版本", dataIndex: ["content", "version"] },
            { title: "名称", dataIndex: ["content", "name"] },
            { title: "状态", dataIndex: "status" },
            {
              title: "规则条件",
              render: (_, r) => <RuleSummary rule={r.content.rule} />,
            },
          ]}
        />
      )}
      <Space>
        <Button
          disabled={!after || rows.loading || !!rows.error}
          onClick={() => setAfter("")}
        >
          目录首页
        </Button>
        <Button
          disabled={
            rows.loading || !!rows.error || rows.data?.length !== PAGE_SIZE
          }
          onClick={() => setAfter(rows.data!.at(-1)!.content.ruleId)}
        >
          下一批规则
        </Button>
      </Space>
      <ErrorNotice error={fields.error} />
      {!fields.error && fields.data && (
        <Card title="可信规则字段">
          <Descriptions
            size="small"
            column={{ xs: 1, sm: 2, lg: 3 }}
            items={Object.entries(fields.data).map(([name, type]) => ({
              key: name,
              label: name,
              children: type,
            }))}
          />
        </Card>
      )}
    </Space>
  );
}

/** 两个动作各自持有原请求；结果未知时冻结树和目标，直到原键重试确认。 */
function RuleAction({
  operation,
  client,
  markDirty,
}: {
  operation: Operation;
  client: typeof request;
  markDirty: (value: boolean) => void;
}) {
  const creating = operation === CREATE,
    label = labels[operation];
  const access = useResource<{ allowed: boolean }>(
    `/operations/rules/${operation}-access`,
  );
  const [form] = Form.useForm<InputValue>();
  const intent = useRef<Intent | null>(null),
    running = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false),
    [writeDenied, setWriteDenied] = useState(false);
  const [error, setError] = useState<Error>(),
    [result, setResult] = useState<RuleView>();
  const canWrite =
    !!access.data?.allowed &&
    !access.error &&
    !access.loading &&
    !writeDenied &&
    !(error instanceof ApiError && error.status >= 500);
  const refresh = () => {
    access.refresh();
    setWriteDenied(false);
    setError(undefined);
  };
  async function submit(value: InputValue) {
    if (running.current || !canWrite) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    setResult(undefined);
    try {
      if (!intent.current)
        intent.current = creating
          ? {
              path: "/admin/rules",
              key: crypto.randomUUID(),
              body: {
                ruleId: value.ruleId,
                version: value.version,
                name: value.name!.trim(),
                rule: structuredClone(value.rule!),
              },
            }
          : {
              path: `/admin/rules/${encodeURIComponent(value.ruleId)}/${value.version}/publish`,
              key: crypto.randomUUID(),
            };
      const { path, ...options } = intent.current;
      setResult(await client<RuleView>(path, { method: "POST", ...options }));
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
  return (
    <Space
      orientation="vertical"
      styles={{ item: { minWidth: 0, maxWidth: "100%" } }}
      size="large"
      style={{ maxWidth: 780, width: "100%" }}
    >
      <Space wrap>
        <Typography.Text>创建、发布与读取权限相互独立</Typography.Text>
        <Button disabled={busy} onClick={refresh}>
          重新核验操作权限
        </Button>
      </Space>
      {result && (
        <Alert
          type="success"
          title={`${label}成功`}
          description={
            <Descriptions
              column={{ xs: 1, sm: 2 }}
              items={[
                {
                  key: "id",
                  label: "规则编号",
                  children: result.content.ruleId,
                },
                {
                  key: "version",
                  label: "版本",
                  children: String(result.content.version),
                },
                { key: "status", label: "状态", children: result.status },
              ]}
            />
          }
        />
      )}
      <ErrorNotice error={error} />
      {error instanceof ApiError &&
        error.status === HTTP.CONFLICT &&
        !frozen && (
          <Alert type="warning" title="请核对规则编号和版本后，再提交操作" />
        )}
      {frozen && (
        <Alert
          type="warning"
          title="操作结果尚未确认，请保留原输入重试"
          description="离开页面将丢失本次重试信息；权限已撤销时，请联系管理员核对已有结果。"
        />
      )}
      {!denied(access.error) && <ErrorNotice error={access.error} />}
      <Card title={label} loading={access.loading}>
        {canWrite ? (
          <Form
            name={`rule-${operation}`}
            form={form}
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => markDirty(true)}
          >
            <Alert
              type="info"
              title={
                creating
                  ? "创建生成不可变的内容版本，初始为草稿。"
                  : "发布指定内容版本；已发布版本再次确认仍会成功。"
              }
            />
            <Form.Item
              name="ruleId"
              label="规则编号"
              rules={[
                {
                  required: true,
                  pattern: identifier,
                  message: "请输入1至64位字母、数字、下划线、点、冒号或连字符",
                },
              ]}
            >
              <Input maxLength={64} disabled={busy || frozen} />
            </Form.Item>
            <Form.Item
              name="version"
              label="规则版本"
              rules={[
                {
                  required: true,
                  type: "integer",
                  min: 1,
                  max: Number.MAX_SAFE_INTEGER,
                  message: "请输入正整数版本",
                },
              ]}
            >
              <InputNumber
                min={1}
                max={Number.MAX_SAFE_INTEGER}
                precision={0}
                disabled={busy || frozen}
                style={{ width: "100%" }}
              />
            </Form.Item>
            {creating && (
              <>
                <Form.Item
                  name="name"
                  label="规则名称"
                  rules={[
                    {
                      required: true,
                      whitespace: true,
                      max: 128,
                      message: "请输入不超过128字的名称",
                    },
                  ]}
                >
                  <Input maxLength={128} disabled={busy || frozen} />
                </Form.Item>
                {/* inert阻止整个递归编辑器的交互，包含自定义节点按钮；不改变共享编辑器原有契约。 */}
                <div
                  inert={busy || frozen}
                  style={busy || frozen ? { opacity: 0.65 } : undefined}
                >
                  <Form.Item
                    name="rule"
                    label="规则条件"
                    rules={[{ required: true, message: "请填写规则条件" }]}
                  >
                    <RuleEditor />
                  </Form.Item>
                </div>
              </>
            )}
            <Button type="primary" htmlType="submit" loading={busy}>
              {frozen ? "原样重试" : `确认${label}`}
            </Button>
          </Form>
        ) : (
          <Typography.Text type="secondary">
            {denied(access.error) || writeDenied
              ? `当前没有${label}权限，可联系管理员申请`
              : "操作资格尚未确认，请重新核验权限"}
          </Typography.Text>
        )}
      </Card>
    </Space>
  );
}

/** 未保存状态按动作分别记录，一个动作成功不能抹掉另一个未知结果。 */
export function CentralRules({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [expired, setExpired] = useState(false),
    dirty = useRef(new Set<Operation>());
  const { modal } = App.useApp();
  const client = useMemo(
    () => ruleClient(context, () => setExpired(true)),
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
        title: "离开规则管理？",
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
        <Space
          orientation="vertical"
          style={{ width: "100%" }}
          styles={{ item: { minWidth: 0, maxWidth: "100%" } }}
        >
          <Space wrap>
            <Typography.Title level={2} style={{ margin: 0 }}>
              规则管理
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
                    key: "directory",
                    label: "规则目录",
                    children: <Directory />,
                  },
                  ...([CREATE, PUBLISH] as const).map((operation) => ({
                    key: operation,
                    label: labels[operation],
                    children: (
                      <RuleAction
                        operation={operation}
                        client={client}
                        markDirty={(v) => {
                          if (v) dirty.current.add(operation);
                          else dirty.current.delete(operation);
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
