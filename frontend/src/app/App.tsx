import {
  Alert,
  Avatar,
  Button,
  Card,
  Form,
  Input,
  Layout,
  Menu,
  Select,
  Space,
  Typography,
} from "antd";
import { useEffect, useState } from "react";
import type { Actor, Capabilities, Store } from "../shared/contracts";
import { request, setAccessToken, useResource } from "../shared/api";
import { ErrorNotice } from "../shared/ui";
import { Orders } from "../features/Orders";
import { Shop } from "../features/Shop";
import { MemberWallet } from "../features/MemberWallet";
import { AdminData, specs } from "../features/AdminData";
import { Marketing } from "../features/Marketing";
import { Journeys } from "../features/Journeys";
import { OpsPages } from "../features/OpsPages";
import { ProductOperations } from "../features/ProductOperations";
const groups = [
  {
    label: "交易与交付",
    children: [
      ["orders", "订单工作台"],
      ["fulfillments", "履约队列"],
      ["aftersales", "售后审批"],
      ["refunds", "退款核对"],
    ],
  },
  {
    label: "营销运营",
    children: [
      ["campaigns", "活动管理"],
      ["audiences", "人群快照"],
      ["rules", "动态规则"],
      ["budgets", "营销预算"],
      ["coupons", "优惠券"],
      ["definitions", "权益定义"],
      ["entitlements", "权益台账"],
    ],
  },
  {
    label: "自动化运营",
    children: [
      ["journeys", "营销旅程"],
      ["instances", "旅程实例"],
      ["pages", "低代码页面"],
      ["events", "异步事件"],
    ],
  },
  {
    label: "业务基础",
    children: [
      ["members", "会员档案"],
      ["merchants", "商家管理"],
      ["stores", "店铺管理"],
      ["store-grants", "经营授权"],
      ["skus", "商品管理"],
      ["inventory", "库存额度"],
    ],
  },
];
function route() {
  const [page, query] = location.hash.slice(1).split("?");
  return {
    page: page || "",
    store: new URLSearchParams(query).get("store") ?? "",
  };
}
export function App() {
  const [actor, setActor] = useState<Actor>();
  const [locationState, setLocationState] = useState(route);
  const [loginError, setLoginError] = useState<Error>();
  const [logging, setLogging] = useState(false);
  useEffect(() => {
    const listener = () => setLocationState(route());
    addEventListener("hashchange", listener);
    return () => removeEventListener("hashchange", listener);
  }, []);
  const stores = useResource<Store[]>(actor ? (actor.role === "OPERATOR" ? "/operations/stores" : "/stores") : null);
  const capabilities = useResource<Capabilities>(
    actor ? "/runtime-capabilities" : null,
  );
  const navigate = (page: string, store = locationState.store) => {
    location.hash = page + (store ? "?store=" + encodeURIComponent(store) : "");
  };
  useEffect(() => {
    if (actor && stores.data?.length && !locationState.store)
      navigate(
        locationState.page || (actor.role === "ADMIN" ? "orders" : actor.role === "OPERATOR" ? "skus" : "shop"),
        stores.data[0].storeId,
      );
  }, [actor, stores.data, locationState.store]);
  const logout = () => {
    setAccessToken("");
    setActor(undefined);
    setLoginError(undefined);
    location.hash = "";
  };
  if (!actor)
    return (
      <div className="login-page">
        <div className="login-story">
          <div className="brand brand-light">
            <span className="brand-mark">商</span>
            <span>
              统一电商<span className="brand-sub">COMMERCE PLATFORM</span>
            </span>
          </div>
          <div>
            <div className="eyebrow">CONNECTED COMMERCE</div>
            <h1>
              连接每一次交易，
              <br />
              经营每一份关系。
            </h1>
            <p>
              从会员与商品，到营销、订单和售后，
              <br />
              让业务在一个平台有序流转。
            </p>
          </div>
          <small>统一业务 · 清晰边界 · 可靠履约</small>
        </div>
        <main className="login-main">
          <Card variant="borderless" className="login-card">
            <div className="eyebrow">WELCOME BACK</div>
            <Typography.Title level={2}>进入业务空间</Typography.Title>
            <p className="muted">
              使用已签发的访问凭据，平台将识别您的角色与租户。
            </p>
            <ErrorNotice error={loginError} />
            <Form
              layout="vertical"
              onFinish={async (v) => {
                setLogging(true);
                setLoginError(undefined);
                setAccessToken(v.token.trim());
                try {
                  const identity = await request<Actor>("/me");
                  setActor(identity);
                  navigate(identity.role === "ADMIN" ? "orders" : identity.role === "OPERATOR" ? "skus" : "shop", "");
                } catch (e) {
                  setAccessToken("");
                  setLoginError(e instanceof Error ? e : new Error("登录失败"));
                } finally {
                  setLogging(false);
                }
              }}
            >
              <Form.Item
                name="token"
                label="访问凭据"
                rules={[{ required: true, message: "请输入访问凭据" }]}
              >
                <Input.Password
                  autoComplete="off"
                  size="large"
                  placeholder="输入您的访问凭据"
                />
              </Form.Item>
              <Button
                htmlType="submit"
                type="primary"
                size="large"
                loading={logging}
                block
              >
                进入平台
              </Button>
            </Form>
            <p className="login-footnote">
              凭据仅保留在本次页面会话，关闭或刷新后需重新输入。
            </p>
          </Card>
        </main>
      </div>
    );
  const operator = actor.role === "OPERATOR";
  const admin = actor.role !== "MEMBER";
  const page = locationState.page || (admin ? "orders" : "shop");
  const store = locationState.store;
  const caps = capabilities.data ?? {
    sandboxEnabled: false,
    workersEnabled: false,
  };
  const menu = operator ? [{ key: "skus", label: "授权商品经营" }] : admin
    ? groups.map((g, i) => ({
        type: "group" as const,
        key: String(i),
        label: g.label,
        children: g.children.map(([key, label]) => ({ key, label })),
      }))
    : [
        ["shop", "逛店铺"],
        ["orders", "我的订单"],
        ["coupons", "优惠券"],
        ["benefits", "我的权益"],
        ["aftersales", "售后"],
        ["notifications", "消息"],
      ].map(([key, label]) => ({ key, label }));
  let content;
  if (admin && page === "skus") content = <ProductOperations key={store} store={store}/>;
  else if (operator) content = <Alert type="warning" title="请从导航进入已授权的商品经营功能" />;
  else if (page === "orders") content = <Orders admin={admin} capabilities={caps} />;
  else if (!admin) {
    if (page === "shop")
      content = (
        <Shop key={store} store={store} onOrder={() => navigate("orders")} />
      );
    else content = <MemberWallet section={page} store={store} />;
  } else if (page === "campaigns" || page === "rules")
    content = <Marketing kind={page} store={store} />;
  else if (page === "journeys") content = <Journeys store={store} />;
  else if (page === "pages") content = <OpsPages store={store} />;
  else if (specs[page])
    content = (
      <AdminData
        key={page + store}
        kind={page}
        store={store}
        capabilities={caps}
        onStoresChanged={stores.refresh}
      />
    );
  else
    content = (
      <Alert type="warning" title="页面不存在，请从导航选择业务功能。" />
    );
  return (
    <Layout className={admin ? "app admin-app" : "app member-app"}>
      {admin && (
        <Layout.Sider width={224} theme="light" className="sidebar">
          <div className="brand">
            <span className="brand-mark">商</span>
            <span>
              统一电商<span className="brand-sub">运营工作空间</span>
            </span>
          </div>
          <Menu
            mode="inline"
            selectedKeys={[page]}
            items={menu}
            onClick={({ key }) => navigate(key)}
          />
        </Layout.Sider>
      )}
      <Layout>
        <Layout.Header className="topbar">
          {!admin && (
            <div className="brand">
              <span className="brand-mark">商</span>
              <span>日常好物</span>
            </div>
          )}
          <Space>
            <span className="context-label">店铺</span>
            <Select
              aria-label="当前店铺"
              value={store || undefined}
              placeholder="选择店铺"
              style={{ width: 210 }}
              options={stores.data?.map((s) => ({
                value: s.storeId,
                label: s.name,
              }))}
              onChange={(v) => navigate(page, v)}
              loading={stores.loading}
            />
            {caps.sandboxEnabled && admin && (
              <span className="environment-badge">本地沙箱</span>
            )}
          </Space>
          <Space>
            <Avatar size="small" style={{ background: "#1D4ED8" }}>
              {actor.actorId.slice(0, 1).toUpperCase()}
            </Avatar>
            <span className="actor-name">{actor.actorId}</span>
            <Button type="text" onClick={logout}>
              退出
            </Button>
          </Space>
        </Layout.Header>
        {!admin && (
          <Menu
            className="member-nav"
            mode="horizontal"
            selectedKeys={[page]}
            items={menu}
            onClick={({ key }) => navigate(key)}
          />
        )}
        <Layout.Content className="workspace">
          <ErrorNotice error={stores.error} />
          <ErrorNotice error={capabilities.error} />
          <div key={actor.tenantId + actor.actorId + page + store}>
            {content}
          </div>
        </Layout.Content>
        <Layout.Footer className="footer">
          统一电商业务平台{" "}
          <span>
            {admin ? `${actor.tenantId} · 运营管理` : "会员服务 · 交易与权益"}
          </span>
        </Layout.Footer>
      </Layout>
    </Layout>
  );
}
