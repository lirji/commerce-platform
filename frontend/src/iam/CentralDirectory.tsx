import { useRouteState } from "../shared/routeState";
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
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

const Kind = { MERCHANT: "merchants", STORE: "stores" } as const;
type Kind = (typeof Kind)[keyof typeof Kind];
type Entry = {
  merchantId: string;
  storeId?: string;
  name: string;
  status: string;
  version: number;
};
type Creation = { merchantId: string; storeId?: string; name: string };
const PAGE_SIZE = 50;
const identifier = /^[A-Za-z0-9_-]{1,100}$/;
const denied = (error?: Error) =>
  error instanceof ApiError && error.status === HTTP.FORBIDDEN;

/** 白名单客户端隔离中央凭据；列表、提示和创建都由服务端独立核验。 */
function directoryClient(
  context: Context,
  expired: () => void,
): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    if (!(
      (method === "GET" &&
        (/^\/admin\/(merchants|stores)\?/.test(path) ||
          /^\/operations\/directory\/(merchants|stores)\/create-access$/.test(
            path,
          ))) ||
      (method === "POST" && /^\/admin\/(merchants|stores)$/.test(path))
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

/** 商家和门店是独立工作区，切换保留表单；退出提示尚未确认的命令。 */
export function CentralDirectory({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [workspaceTab, setWorkspaceTab] = useRouteState(
    "workspaceTab",
    Kind.MERCHANT,
  );
  const [expired, setExpired] = useState(false);
  const dirty = useRef(new Set<Kind>());
  const { modal } = App.useApp();
  const client = useMemo(
    () => directoryClient(context, () => setExpired(true)),
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
        title: "离开目录管理？",
        content:
          "未保存输入或未确认结果将离开当前页面；结果未知时请先原样重试确认，避免重复创建。",
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
            title="商家与门店"
            description="维护商家与门店目录，明确各对象的经营归属。"
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
                items={Object.values(Kind).map((kind) => ({
                  key: kind,
                  label: kind === Kind.MERCHANT ? "商家目录" : "门店目录",
                  children: (
                    <DirectorySection
                      kind={kind}
                      client={client}
                      markDirty={(value) => {
                        if (value) dirty.current.add(kind);
                        else dirty.current.delete(kind);
                      }}
                    />
                  ),
                }))}
              />
            </RequestContext.Provider>
          )}
        </Space>
      </Card>
    </main>
  );
}

function DirectorySection({
  kind,
  client,
  markDirty,
}: {
  kind: Kind;
  client: typeof request;
  markDirty: (value: boolean) => void;
}) {
  const merchant = kind === Kind.MERCHANT,
    label = merchant ? "商家" : "门店";
  const [after, setAfter] = useRouteState(`DirectorySection.${kind}.after`, "");
  const rows = useResource<Entry[]>(
    `/admin/${kind}?after=${encodeURIComponent(after)}&limit=${PAGE_SIZE}`,
  );
  const action = useResource<{ allowed: boolean }>(
    `/operations/directory/${kind}/create-access`,
  );
  const [form] = Form.useForm<Creation>();
  const intent = useRef<{ key: string; body: Creation } | null>(null);
  const running = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false),
    [writeDenied, setWriteDenied] = useState(false);
  const [error, setError] = useState<Error>(),
    [created, setCreated] = useState<string>();
  const canCreate =
    action.data?.allowed && !action.error && !action.loading && !writeDenied;
  const refresh = () => {
    rows.refresh();
    action.refresh();
    setWriteDenied(false);
  };
  async function create(value: Creation) {
    if (running.current || !canCreate) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    setCreated(undefined);
    if (!intent.current)
      intent.current = {
        key: crypto.randomUUID(),
        body: {
          merchantId: value.merchantId.trim(),
          name: value.name.trim(),
          ...(merchant ? {} : { storeId: value.storeId!.trim() }),
        },
      };
    try {
      const result = await client<Entry>(`/admin/${kind}`, {
        method: "POST",
        ...intent.current,
      });
      setCreated(merchant ? result.merchantId : result.storeId);
      intent.current = null;
      setFrozen(false);
      markDirty(false);
      form.resetFields();
      refresh();
    } catch (failure) {
      setError(failure as Error);
      // 一次响应丢失后，后续拒绝不能证明旧命令未提交，原键和原输入必须保留。
      if (failure instanceof ApiError && failure.status < 500 && !frozen)
        intent.current = null;
      else {
        setFrozen(true);
        markDirty(true);
      }
      if (denied(failure as Error)) {
        setWriteDenied(true);
        rows.refresh();
        action.refresh();
      }
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  const idField = (
    name: "merchantId" | "storeId",
    title: string,
    help?: string,
  ) => (
    <Form.Item
      name={name}
      label={title}
      extra={help}
      rules={[
        { required: true, message: `请输入${title}` },
        {
          pattern: identifier,
          message: "编号须为1至100位字母、数字、下划线或连字符",
        },
      ]}
    >
      <Input disabled={busy || frozen} autoComplete="off" maxLength={100} />
    </Form.Item>
  );
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Space wrap>
        <Typography.Text>仅显示当前获授权的{label}</Typography.Text>
        <Button disabled={busy} onClick={refresh}>
          重新核验权限与目录
        </Button>
      </Space>
      <ErrorNotice error={rows.error} />
      {!rows.error && (
        <Table<Entry>
          rowKey={merchant ? "merchantId" : "storeId"}
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          scroll={{ x: 620 }}
          columns={[
            {
              title: `${label}编号`,
              dataIndex: merchant ? "merchantId" : "storeId",
            },
            { title: "名称", dataIndex: "name" },
            ...(!merchant
              ? [{ title: "所属商家", dataIndex: "merchantId" }]
              : []),
            {
              title: "状态",
              dataIndex: "status",
              render: (value: string) => (value === "ACTIVE" ? "可用" : value),
            },
            { title: "版本", dataIndex: "version" },
          ]}
        />
      )}
      <Space>
        <Button
          disabled={!after || rows.loading || !!rows.error}
          onClick={() => setAfter("")}
        >
          回到首页
        </Button>
        <Button
          disabled={
            rows.loading || !!rows.error || rows.data?.length !== PAGE_SIZE
          }
          onClick={() => {
            const last = rows.data!.at(-1)!;
            setAfter(merchant ? last.merchantId : last.storeId!);
          }}
        >
          下一页
        </Button>
      </Space>
      {created && (
        <Alert
          type="success"
          title={`${label}已创建：${created}`}
          description="创建成功不代表获授该对象的读取权限；目录按当前权限显示。"
        />
      )}
      <ErrorNotice error={error} />
      {frozen && (
        <Alert
          type="warning"
          title="创建结果尚未确认，请保留原输入重试"
          description="离开页面将丢失本次重试信息。若权限已撤销，请联系管理员核对已有结果。"
        />
      )}
      {!denied(action.error) && <ErrorNotice error={action.error} />}
      <Card title={`新建${label}`} loading={action.loading}>
        {canCreate ? (
          <Form
            name={`directory-${kind}`}
            form={form}
            layout="vertical"
            onFinish={create}
            onValuesChange={() => markDirty(true)}
          >
            {!merchant && idField("storeId", "门店编号")}
            {idField(
              "merchantId",
              merchant ? "商家编号" : "所属商家编号",
              merchant
                ? undefined
                : "输入当前组织内已知的商家编号；创建门店不包含商家查询权限。",
            )}
            <Form.Item
              name="name"
              label={`${label}名称`}
              rules={[
                {
                  required: true,
                  whitespace: true,
                  message: `请输入${label}名称`,
                },
                { max: 128, message: "名称不能超过128字" },
              ]}
            >
              <Input disabled={busy || frozen} maxLength={128} />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              {frozen ? `原样重试创建${label}` : `确认创建${label}`}
            </Button>
          </Form>
        ) : (
          <Typography.Text type="secondary">
            {denied(action.error) || writeDenied
              ? `当前没有新建${label}权限，可联系管理员申请`
              : "创建资格尚未确认，请重新核验权限"}
          </Typography.Text>
        )}
      </Card>
    </Space>
  );
}
