import {
  Alert,
  App,
  Button,
  Card,
  Descriptions,
  Drawer,
  Form,
  Input,
  Space,
  Spin,
  Table,
  Typography,
} from "antd";
import { useEffect, useRef, useState } from "react";
import { CentralBehavior } from "./CentralBehavior";
import { CentralTags } from "./CentralTags";
import { CentralGrowth } from "./CentralGrowth";
import { CentralMembers } from "./CentralMembers";
import { CentralDirectory } from "./CentralDirectory";
import { CentralInventory } from "./CentralInventory";
import { CentralCatalog } from "./CentralCatalog";
import { ProductExport } from "./ProductExport";
import type { User } from "oidc-client-ts";
import { enabled, login, manager, session } from "./session";
import { central, CentralError, HTTP, useCentral, type Context } from "./api";

type Product = {
  resourceId: string;
  storeId: string;
  title: string;
  category: string;
  brand: string;
  resourceVersion: number;
};
type Page = {
  items: Product[];
  total: number;
  stores: number;
  nextCursor?: string;
};
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
function ErrorView({ error }: { error?: Error }) {
  return error ? (
    <Alert
      type="error"
      showIcon
      action={
        error instanceof CentralError && error.status === HTTP.UNAUTHORIZED ? (
          <Button onClick={() => void login()}>重新登录</Button>
        ) : undefined
      }
      title={error.message}
      description={
        error instanceof CentralError
          ? `关联编号：${error.traceId ?? "未返回"}`
          : "请求结果未知；写入请保留原命令重试。"
      }
    />
  ) : null;
}
/** 中央试点独立路由，旧凭据工作台不会成为此入口的权限回退。 */
export function CentralProducts() {
  const [user, setUser] = useState<User | null>();
  const [error, setError] = useState<Error>();
  useEffect(() => {
    let active = true;
    session()
      .then((u) => {
        if (active) setUser(u);
      })
      .catch(() => {
        if (active) {
          setError(new Error("登录回调未完成，请重新登录"));
          setUser(null);
        }
      });
    const expired = () => setUser(null);
    manager?.events.addAccessTokenExpired(expired);
    return () => {
      active = false;
      manager?.events.removeAccessTokenExpired(expired);
    };
  }, []);
  if (!enabled)
    return <Alert type="warning" title="商城统一身份入口尚未启用" />;
  if (user === undefined) return <Spin tip="正在恢复登录" fullscreen />;
  if (!user || user.expired)
    return (
      <div className="central-products">
        <Card title="商城统一身份登录">
          {error && <Alert type="error" showIcon title={error.message} />}
          <p>使用企业身份进入已授权业务。</p>
          <Button
            type="primary"
            onClick={() =>
              login().catch(() => setError(new Error("登录服务暂不可用")))
            }
          >
            企业登录
          </Button>
        </Card>
      </div>
    );
  const tenant = new URLSearchParams(location.search).get("tenant_id") ?? "";
  if (!uuid.test(tenant))
    return <Alert type="info" title="请从工作台选择组织后进入商城" />;
  const Page =
    location.pathname === "/operations/member-behavior"
      ? CentralBehavior
      : location.pathname === "/operations/member-tags"
        ? CentralTags
        : location.pathname === "/operations/member-growth"
          ? CentralGrowth
          : location.pathname === "/operations/members"
            ? CentralMembers
            : location.pathname === "/operations/directory"
              ? CentralDirectory
              : location.pathname === "/operations/inventory"
                ? CentralInventory
                : location.pathname === "/operations/catalog"
                  ? CentralCatalog
                  : Products;
  return (
    <Page
      key={`${user.profile.sub}:${tenant}`}
      context={{ token: user.access_token, tenant }}
      onLogout={async () => {
        await manager?.removeUser();
        setUser(null);
      }}
    />
  );
}
function Products({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [params, setParams] = useState(
    () => new URLSearchParams(location.search),
  );
  const [revision, setRevision] = useState(0);
  const dirty = useRef(false);
  const { modal } = App.useApp();
  const guard = (action: () => void) => {
    if (!dirty.current) return action();
    modal.confirm({
      title: "放弃未保存的商品修改？",
      content: "结果未知的命令应先原样重试确认；离开后将丢失本次表单。",
      okText: "放弃修改",
      cancelText: "继续编辑",
      onOk: () => {
        dirty.current = false;
        action();
      },
    });
  };
  useEffect(() => {
    const protect = (event: BeforeUnloadEvent) => {
      if (dirty.current) {
        event.preventDefault();
        event.returnValue = "";
      }
    };
    addEventListener("beforeunload", protect);
    return () => removeEventListener("beforeunload", protect);
  }, []);
  useEffect(() => {
    const sync = () => setParams(new URLSearchParams(location.search));
    addEventListener("popstate", sync);
    return () => removeEventListener("popstate", sync);
  }, []);
  function navigate(values: Record<string, string | null>) {
    const next = new URLSearchParams(params);
    for (const [key, value] of Object.entries(values))
      value ? next.set(key, value) : next.delete(key);
    history.pushState(null, "", `${location.pathname}?${next}`);
    setParams(next);
  }
  const search = params.get("search") ?? "",
    cursor = params.get("cursor") ?? "",
    selected = params.get("product");
  const page = useCentral<Page>(
    context,
    `?${new URLSearchParams({ search, cursor, limit: "25" })}`,
    revision,
  );
  return (
    <main className="central-products">
      <Space className="central-heading" wrap>
        <div>
          <Typography.Title level={2}>
            {location.pathname.startsWith("/collaboration")
              ? "门店商品协作"
              : "商品经营"}
          </Typography.Title>
          <Typography.Text type="secondary">
            查看已授权门店商品，修改权限按商品实时核验。
          </Typography.Text>
        </div>
        <Button onClick={() => guard(() => void onLogout())}>退出本应用</Button>
      </Space>
      <Card>
        <Space direction="vertical" size="large" style={{ width: "100%" }}>
          <Typography.Text type="secondary">
            当前组织：{context.tenant}
          </Typography.Text>
          <Space wrap>
            <Input.Search
              aria-label="搜索商品"
              placeholder="搜索商品名称"
              defaultValue={search}
              onSearch={(value) =>
                navigate({ search: value, cursor: null, product: null })
              }
              style={{ width: 280 }}
            />
            <Button onClick={() => guard(() => setRevision((v) => v + 1))}>
              刷新商品
            </Button>
          </Space>
          <ErrorView error={page.error} />
          <Typography.Text>
            当前范围内 {page.data?.total ?? "—"} 件商品 ·{" "}
            {page.data?.stores ?? "—"} 家门店
          </Typography.Text>
          <Table<Product>
            rowKey="resourceId"
            loading={page.loading}
            dataSource={page.error ? [] : page.data?.items}
            pagination={false}
            scroll={{ x: 720 }}
            columns={[
              {
                title: "商品",
                render: (_, r) => (
                  <Space direction="vertical" size={0}>
                    <Typography.Text strong>{r.title}</Typography.Text>
                    <Typography.Text type="secondary">
                      {r.resourceId}
                    </Typography.Text>
                  </Space>
                ),
              },
              { title: "归属门店", dataIndex: "storeId" },
              { title: "分类", dataIndex: "category" },
              { title: "品牌", dataIndex: "brand" },
              {
                title: "操作",
                render: (_, r) => (
                  <Button
                    type="link"
                    onClick={() => navigate({ product: r.resourceId })}
                  >
                    查看资料
                  </Button>
                ),
              },
            ]}
          />
          <Space>
            <Button
              disabled={!cursor}
              onClick={() => navigate({ cursor: null, product: null })}
            >
              回到首页
            </Button>
            <Button
              disabled={!page.data?.nextCursor}
              onClick={() =>
                navigate({
                  cursor: page.data?.nextCursor ?? null,
                  product: null,
                })
              }
            >
              下一页
            </Button>
          </Space>
        </Space>
      </Card>
      <Card title="限时商品导出" style={{ marginTop: 24 }}>
        <ProductExport
          key={params.get("export") ?? "new-export"}
          context={context}
          search={search}
          jobId={params.get("export")}
          revision={revision}
          onJob={(id) => navigate({ export: id })}
          refresh={() => setRevision((v) => v + 1)}
        />
      </Card>
      <Drawer
        open={!!selected}
        title="商品资料"
        size="large"
        destroyOnHidden
        onClose={() => guard(() => navigate({ product: null }))}
      >
        {selected && (
          <ProductDetail
            key={selected}
            context={context}
            id={selected}
            revision={revision}
            refresh={() => guard(() => setRevision((v) => v + 1))}
            markDirty={(value) => {
              dirty.current = value;
            }}
          />
        )}
      </Drawer>
    </main>
  );
}
function ProductDetail({
  context,
  id,
  revision,
  refresh,
  markDirty,
}: {
  context: Context;
  id: string;
  revision: number;
  refresh: () => void;
  markDirty: (value: boolean) => void;
}) {
  const data = useCentral<Product>(
    context,
    `/resources/${encodeURIComponent(id)}`,
    revision,
  );
  const actions = useCentral<{ update: boolean }>(
    context,
    `/resources/${encodeURIComponent(id)}/actions`,
    revision,
  );
  const [edit, setEdit] = useState(false);
  if (data.loading) return <Spin />;
  if (data.error) return <ErrorView error={data.error} />;
  if (!data.data) return null;
  const row = data.data;
  return (
    <Space direction="vertical" size="large" style={{ width: "100%" }}>
      <Descriptions
        column={1}
        bordered
        items={[
          { key: "title", label: "商品名称", children: row.title },
          { key: "id", label: "商品编号", children: row.resourceId },
          { key: "store", label: "归属门店", children: row.storeId },
          { key: "category", label: "分类", children: row.category },
          { key: "brand", label: "品牌", children: row.brand },
          { key: "version", label: "资料版本", children: row.resourceVersion },
        ]}
      />
      <ErrorView error={actions.error} />
      {actions.data?.update && !actions.error ? (
        edit ? (
          <EditProduct
            key={`${id}:${row.resourceVersion}`}
            context={context}
            row={row}
            markDirty={markDirty}
            onDone={() => {
              markDirty(false);
              setEdit(false);
              refresh();
            }}
          />
        ) : (
          <Button type="primary" onClick={() => setEdit(true)}>
            编辑商品资料
          </Button>
        )
      ) : (
        <Alert type="info" title="当前可查看商品；编辑需要另行授权" />
      )}
      <Button onClick={refresh}>重新核验权限与资料</Button>
    </Space>
  );
}
function EditProduct({
  context,
  row,
  onDone,
  markDirty,
}: {
  context: Context;
  row: Product;
  markDirty: (value: boolean) => void;
  onDone: () => void;
}) {
  const [form] = Form.useForm();
  const { message } = App.useApp();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<Error>();
  const intent = useRef<{ key: string; body: unknown } | undefined>(undefined);
  const [frozen, setFrozen] = useState(false);
  const running = useRef(false);
  async function submit(values: Record<string, string>) {
    if (running.current) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    if (!intent.current)
      intent.current = {
        key: crypto.randomUUID(),
        body: { ...values, expectedVersion: row.resourceVersion },
      };
    try {
      await central(
        context,
        `/resources/${encodeURIComponent(row.resourceId)}`,
        intent.current.body,
        intent.current.key,
      );
      intent.current = undefined;
      message.success("商品资料已保存");
      onDone();
    } catch (e) {
      setError(e as Error);
      if (e instanceof CentralError && e.status < 500) {
        intent.current = undefined;
        setFrozen(false);
      } else setFrozen(true);
    } finally {
      running.current = false;
      setBusy(false);
    }
  }
  return (
    <Form
      form={form}
      layout="vertical"
      initialValues={row}
      onValuesChange={() => markDirty(true)}
      onFinish={submit}
    >
      <ErrorView error={error} />
      {(["title", "category", "brand"] as const).map((name, index) => (
        <Form.Item
          key={name}
          name={name}
          label={["商品名称", "分类", "品牌"][index]}
          rules={[{ required: true, max: name === "title" ? 128 : 64 }]}
        >
          <Input disabled={busy || frozen} />
        </Form.Item>
      ))}
      <Button type="primary" htmlType="submit" loading={busy}>
        {frozen ? "原样重试保存" : "保存商品资料"}
      </Button>
    </Form>
  );
}
