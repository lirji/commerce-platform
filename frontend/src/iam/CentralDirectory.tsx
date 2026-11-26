import { useListFilters } from "../shared/listFilters";
import { useWorkspaceHref } from "../shared/workspaceLinks";
import { CursorBack } from "../shared/pagination";
import { useRouteState, useCursorState } from "../shared/routeState";
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  Select,
  Space,
  Table,
  Tabs,
  Typography,
} from "antd";
import { useEffect, useLayoutEffect, useMemo, useRef, useState } from "react";
import {
  ApiError,
  RequestContext,
  type request,
  useResource,
} from "../shared/api";
import { ErrorNotice, PageHead, PrimaryCell, statusText } from "../shared/ui";
import {
  OpeningCategoryField,
  openingSubjects,
  PENDING_OPEN,
  STORE_OPENING_CATEGORIES,
  STORE_OPENINGS,
  StoreOpeningActions,
} from "../features/StoreOpening";
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
type Creation = {
  merchantId: string;
  storeId?: string;
  name: string;
  subjectType?: string;
  categoryCode?: string;
};
const PAGE_SIZE = 50;
const identifier = /^[A-Za-z0-9_.:-]{1,64}$/;
const denied = (error?: Error) =>
  error instanceof ApiError && error.status === HTTP.FORBIDDEN;
const directoryQuery = (kind: Kind) => `DirectorySection.${kind}`;

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
          path === STORE_OPENING_CATEGORIES ||
          /^\/admin\/store-openings\/[^/]+$/.test(path) ||
          /^\/operations\/directory\/(merchants|stores)\/create-access$/.test(
            path,
          ))) ||
      (method === "POST" &&
        (/^\/admin\/(merchants|store-openings)$/.test(path) ||
          /^\/admin\/store-openings\/[^/]+\/(revise|open)$/.test(path)))
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
  useLayoutEffect(() => {
    const params = new URLSearchParams(location.search);
    const previousKey = "DirectorySection.query";
    const previous = params.get(previousKey);
    if (previous === null) return;
    // 旧链接的共享条件只恢复到链接打开的目录，另一个目录从自身条件开始。
    const kind =
      params.get("workspaceTab") === Kind.STORE ? Kind.STORE : Kind.MERCHANT;
    const key = `${directoryQuery(kind)}.query`;
    if (!params.has(key)) params.set(key, previous);
    params.delete(previousKey);
    history.replaceState(
      history.state,
      "",
      `${location.pathname}?${params}${location.hash}`,
    );
    dispatchEvent(new Event("popstate"));
  }, []);
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
  const href = useWorkspaceHref();
  const storePages = [
    ["catalog", "商品"],
    ["inventory", "库存"],
  ] as const;
  const [after, setAfter] = useCursorState(`${directoryQuery(kind)}.after`, "");
  const filters = useListFilters(directoryQuery(kind), `/admin/${kind}`, () =>
    setAfter(""),
  );
  const merchantCriteria = new URLSearchParams(location.search).get(
    `${directoryQuery(Kind.MERCHANT)}.query`,
  );
  const merchantScope = new URLSearchParams(filters.query).get("merchantId");
  const rows = useResource<Entry[]>(
    `/admin/${kind}?after=${encodeURIComponent(after)}&${filters.query}`,
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
          ...(merchant
            ? {}
            : {
                storeId: value.storeId!.trim(),
                subjectType: value.subjectType,
                categoryCode: value.categoryCode,
              }),
        },
      };
    try {
      const result = await client<Entry>(
        merchant ? "/admin/merchants" : STORE_OPENINGS,
        {
          method: "POST",
          ...intent.current,
        },
      );
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
          message: "编号须为1至64位字母、数字或 . _ : -",
        },
      ]}
    >
      <Input disabled={busy || frozen} autoComplete="off" maxLength={64} />
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
      {filters.toolbar}
      {!merchant && merchantScope && (
        <Space wrap>
          <Typography.Text>所属商家 {merchantScope}</Typography.Text>
          <a
            href={href("directory", {
              workspaceTab: Kind.MERCHANT,
              ...(merchantCriteria
                ? {
                    [`${directoryQuery(Kind.MERCHANT)}.query`]:
                      merchantCriteria,
                  }
                : {}),
            })}
          >
            返回商家目录
          </a>
        </Space>
      )}
      <ErrorNotice error={rows.error} onRetry={rows.refresh} />
      {!rows.error && (
        <Table<Entry>
          rowKey={merchant ? "merchantId" : "storeId"}
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          scroll={{ x: 620 }}
          columns={[
            {
              title: label,
              width: 180,
              render: (_, row) => (
                <PrimaryCell
                  title={row.name}
                  subtitle={merchant ? row.merchantId : row.storeId}
                />
              ),
            },
            ...(!merchant
              ? [{ title: "所属商家", dataIndex: "merchantId" }]
              : []),
            {
              title: "状态",
              dataIndex: "status",
              render: (value: string) => statusText(value),
            },
            { title: "版本", dataIndex: "version" },
            ...(merchant && href("directory")
              ? [
                  {
                    title: "关联门店",
                    render: (_: unknown, row: Entry) => (
                      <a
                        href={href("directory", {
                          workspaceTab: "stores",
                          ...(merchantCriteria
                            ? {
                                [`${directoryQuery(Kind.MERCHANT)}.query`]:
                                  merchantCriteria,
                              }
                            : {}),
                          [`${directoryQuery(Kind.STORE)}.query`]:
                            JSON.stringify({ merchantId: row.merchantId }),
                        })}
                      >
                        查看下属门店
                      </a>
                    ),
                  },
                ]
              : []),
            ...(!merchant &&
            (canCreate || storePages.some(([page]) => href(page)))
              ? [
                  {
                    title: "关联作业",
                    render: (_: unknown, row: Entry) => (
                      <Space wrap>
                        {canCreate && row.storeId && (
                          <StoreOpeningActions
                            storeId={row.storeId}
                            onDone={refresh}
                            onOpened={() => setCreated(undefined)}
                            buttonLabel={
                              row.status === PENDING_OPEN
                                ? "继续开店"
                                : "开店资料"
                            }
                          />
                        )}
                        {row.status !== PENDING_OPEN &&
                          storePages.map(([page, title]) => {
                            const url =
                              row.storeId &&
                              href(page, { store_id: row.storeId });
                            return (
                              url && (
                                <a
                                  key={page}
                                  href={url}
                                >{`查看门店${title}`}</a>
                              )
                            );
                          })}
                      </Space>
                    ),
                  },
                ]
              : []),
          ]}
        />
      )}
      <div className="pager-navigation">
        <CursorBack
          name={`DirectorySection.${kind}.after`}
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
          onClick={() => {
            const last = rows.data!.at(-1)!;
            setAfter(merchant ? last.merchantId : last.storeId!);
          }}
        >
          下一页
        </Button>
      </div>
      {created && (
        <Alert
          type="success"
          title={
            merchant ? `商家已创建 · ${created}` : `开店草稿已保存 · ${created}`
          }
          description={merchant ? "目录按当前权限显示。" : undefined}
          action={
            !merchant && canCreate ? (
              <StoreOpeningActions
                storeId={created}
                onDone={refresh}
                onOpened={() => setCreated(undefined)}
              />
            ) : undefined
          }
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
      {!denied(action.error) && (
        <ErrorNotice error={action.error} onRetry={action.refresh} />
      )}
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
            {!merchant && (
              <>
                <Form.Item
                  name="subjectType"
                  label="经营主体类型"
                  rules={[{ required: true, message: "请选择经营主体类型" }]}
                >
                  <Select options={openingSubjects} disabled={busy || frozen} />
                </Form.Item>
                <OpeningCategoryField disabled={busy || frozen} />
              </>
            )}
            <Button type="primary" htmlType="submit" loading={busy}>
              {frozen
                ? `原样重试创建${label}`
                : merchant
                  ? "确认创建商家"
                  : "保存开店草稿"}
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
