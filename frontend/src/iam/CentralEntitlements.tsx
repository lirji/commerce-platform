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
import { ErrorNotice } from "../shared/ui";
import { CentralError, HTTP, type Context } from "./api";

type ResolutionInput = {
  grantId: string;
  resolution: "RECOVERED" | "WRITTEN_OFF";
  reference: string;
};
type Entitlement = {
  grantId: string;
  orderId: string | null;
  memberId: string;
  benefitId: string;
  benefitVersion: number;
  name: string;
  status: string;
  units: number;
  remainingUnits: number;
  debtUnits: number;
  expiresAt: string | null;
  version: number;
  sourceType: string;
  sourceId: string;
};
const PAGE_SIZE = 50;
const identifier = /^[A-Za-z0-9_.:-]{1,64}$/;
const denied = (e?: Error) =>
  e instanceof ApiError && e.status === HTTP.FORBIDDEN;

/** 目录键和解码后的标识逐一核对；中央凭据不能发送到任意URL或旧控制台客户端。 */
function entitlementClient(
  context: Context,
  expired: () => void,
): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    let directory = false,
      resolve = false;
    if (path.startsWith("/admin/entitlements?")) {
      const query = new URLSearchParams(path.slice(path.indexOf("?") + 1));
      directory =
        Array.from(query.keys()).length === 2 &&
        query.has("after") &&
        query.get("limit") === String(PAGE_SIZE) &&
        (query.get("after") === "" ||
          identifier.test(query.get("after") ?? ""));
    }
    const match = /^\/admin\/entitlements\/([^/?#]+)\/resolve$/.exec(path);
    if (match) {
      try {
        resolve = identifier.test(decodeURIComponent(match[1]));
      } catch {
        resolve = false;
      }
    }
    if (!(
      (method === "GET" &&
        (directory || path === "/operations/entitlements/resolve-access")) ||
      (method === "POST" && resolve)
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
  const [after, setAfter] = useRouteState("Directory.after", "");
  const rows = useResource<Entitlement[]>(
    `/admin/entitlements?after=${encodeURIComponent(after)}&limit=${PAGE_SIZE}`,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        当前组织的权益实例，包含各状态；处理补偿需要独立权限。
      </Typography.Text>
      <ErrorNotice error={rows.error} />
      {!rows.error && (
        <Table<Entitlement>
          rowKey="grantId"
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          scroll={{ x: 2200 }}
          columns={[
            { title: "权益实例编号", dataIndex: "grantId" },
            { title: "会员编号", dataIndex: "memberId" },
            { title: "权益定义编号", dataIndex: "benefitId" },
            { title: "定义版本", dataIndex: "benefitVersion" },
            { title: "名称", dataIndex: "name" },
            { title: "状态", dataIndex: "status" },
            { title: "总单位数", dataIndex: "units" },
            { title: "剩余单位数", dataIndex: "remainingUnits" },
            { title: "欠项单位数", dataIndex: "debtUnits" },
            {
              title: "到期时间",
              dataIndex: "expiresAt",
              render: (v) => (v ? new Date(v).toLocaleString() : "尚未生效"),
            },
            { title: "实例版本", dataIndex: "version" },
            { title: "来源类型", dataIndex: "sourceType" },
            { title: "来源编号", dataIndex: "sourceId" },
            {
              title: "订单编号",
              dataIndex: "orderId",
              render: (v) => v ?? "—",
            },
          ]}
        />
      )}
      <Space wrap>
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
          onClick={() => setAfter(rows.data!.at(-1)!.grantId)}
        >
          下一批实例
        </Button>
        <Button disabled={rows.loading} onClick={rows.refresh}>
          刷新实例
        </Button>
      </Space>
    </Space>
  );
}

/** 原始请求在结果未知时保持不变，重试不能生成第二次处理意图。 */
function ResolveEntitlement({
  client,
  markDirty,
}: {
  client: typeof request;
  markDirty: (v: boolean) => void;
}) {
  const access = useResource<{ allowed: boolean }>(
    "/operations/entitlements/resolve-access",
  );
  const [form] = Form.useForm<ResolutionInput>();
  const intent = useRef<{
      path: string;
      key: string;
      body: Omit<ResolutionInput, "grantId">;
    } | null>(null),
    running = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false),
    [writeDenied, setWriteDenied] = useState(false);
  const [error, setError] = useState<Error>(),
    [result, setResult] = useState<Entitlement>();
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
  async function submit(value: ResolutionInput) {
    if (running.current || !canWrite) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    setResult(undefined);
    try {
      if (!intent.current)
        intent.current = {
          path: `/admin/entitlements/${encodeURIComponent(value.grantId)}/resolve`,
          key: crypto.randomUUID(),
          body: {
            resolution: value.resolution,
            reference: value.reference.trim(),
          },
        };
      const { path, ...options } = intent.current;
      setResult(
        await client<Entitlement>(path, { method: "POST", ...options }),
      );
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
      size="large"
      style={{ maxWidth: 700, width: "100%" }}
    >
      <Space wrap>
        <Typography.Text>补偿处理与目录读取权限相互独立</Typography.Text>
        <Button disabled={busy} onClick={refresh}>
          重新核验操作权限
        </Button>
      </Space>
      {result && (
        <Alert
          type="success"
          title="处理权益补偿成功"
          description={
            <Descriptions
              column={{ xs: 1, sm: 2 }}
              items={[
                { key: "id", label: "权益实例编号", children: result.grantId },
                { key: "status", label: "状态", children: result.status },
                {
                  key: "remaining",
                  label: "剩余单位数",
                  children: String(result.remainingUnits),
                },
                {
                  key: "debt",
                  label: "欠项单位数",
                  children: String(result.debtUnits),
                },
              ]}
            />
          }
        />
      )}
      <ErrorNotice error={error} />
      {error instanceof ApiError &&
        error.status === HTTP.CONFLICT &&
        !frozen && (
          <Alert
            type="warning"
            title="请核对实例状态与补偿凭据后，再提交操作"
          />
        )}
      {frozen && (
        <Alert
          type="warning"
          title="操作结果尚未确认，请保留原输入重试"
          description="离开页面将丢失本次重试信息；权限已撤销时，请联系管理员核对已有结果。"
        />
      )}
      {!denied(access.error) && <ErrorNotice error={access.error} />}
      <Card title="处理权益补偿" loading={access.loading}>
        {canWrite ? (
          <Form
            name="entitlement-resolve"
            form={form}
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => markDirty(true)}
          >
            <Alert
              type="info"
              title="仅处理待补偿实例；请填写已完成恢复或确认损失的凭据，本操作不会自动执行外部扣款。"
            />
            <Form.Item
              name="grantId"
              label="权益实例编号"
              rules={[
                {
                  required: true,
                  pattern: identifier,
                  message: "请输入1至64位合法标识",
                },
              ]}
            >
              <Input maxLength={64} disabled={busy || frozen} />
            </Form.Item>
            <Form.Item
              name="resolution"
              label="补偿结论"
              rules={[{ required: true, message: "请选择补偿结论" }]}
            >
              <Radio.Group
                disabled={busy || frozen}
                options={[
                  { value: "RECOVERED", label: "已恢复" },
                  { value: "WRITTEN_OFF", label: "确认损失" },
                ]}
              />
            </Form.Item>
            <Form.Item
              name="reference"
              label="补偿凭据"
              rules={[
                {
                  required: true,
                  whitespace: true,
                  max: 128,
                  message: "请输入不超过128字的补偿凭据",
                },
              ]}
            >
              <Input maxLength={128} disabled={busy || frozen} />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              {frozen ? "原样重试" : "确认处理权益补偿"}
            </Button>
          </Form>
        ) : (
          <Typography.Text type="secondary">
            {denied(access.error) || writeDenied
              ? "当前没有处理权益补偿权限，可联系管理员申请"
              : "操作资格尚未确认，请重新核验权限"}
          </Typography.Text>
        )}
      </Card>
    </Space>
  );
}

/** 独立处理资格不依赖目录读取；离开前提示未确认的处理意图。 */
export function CentralEntitlements({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [workspaceTab, setWorkspaceTab] = useRouteState(
    "workspaceTab",
    "directory",
  );
  const [expired, setExpired] = useState(false),
    dirty = useRef(false);
  const { modal } = App.useApp();
  const client = useMemo(
    () => entitlementClient(context, () => setExpired(true)),
    [context.token, context.tenant],
  );
  useEffect(() => {
    const warn = (event: BeforeUnloadEvent) => {
      if (dirty.current) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", warn);
    return () => removeEventListener("beforeunload", warn);
  }, []);
  const logout = () => {
    if (!dirty.current) void onLogout();
    else
      modal.confirm({
        title: "离开权益实例？",
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
              权益实例
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
                activeKey={workspaceTab}
                onChange={setWorkspaceTab}
                items={[
                  {
                    key: "directory",
                    label: "权益实例目录",
                    children: <Directory />,
                  },
                  {
                    key: "create",
                    label: "处理权益补偿",
                    children: (
                      <ResolveEntitlement
                        client={client}
                        markDirty={(v) => {
                          dirty.current = v;
                        }}
                      />
                    ),
                  },
                ]}
              />
            </RequestContext.Provider>
          )}
        </Space>
      </Card>
    </main>
  );
}
