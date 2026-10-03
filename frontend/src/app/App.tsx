import { RecordModal } from "../shared/interactions";
import {
  Alert,
  App as AntApp,
  Avatar,
  Button,
  ConfigProvider,
  Form,
  Input,
  Layout,
  Grid,
  Spin,
  Menu,
  Select,
  Space,
} from "antd";
import { lazy, Suspense, useEffect, useState } from "react";
import type { Actor, Capabilities, Store } from "../shared/contracts";
import { ApiError, request, setAccessToken, useResource } from "../shared/api";
import {
  savedCredential,
  saveCredential,
  SESSION_EXPIRED,
} from "../shared/session";
import { hasUnconfirmedCommand } from "../shared/useIntent";
import { ErrorNotice } from "../shared/ui";
import { Icon, type IconName } from "../shared/Icon";
import { WorkspaceBrand, WorkspaceSearch } from "../shared/WorkspaceChrome";
import { memberTheme } from "../theme";
// 按业务页面加载，登录和总览不下载所有经营表单。
const Orders = lazy(() =>
  import("../features/Orders").then((m) => ({ default: m.Orders })),
);
const Shop = lazy(() =>
  import("../features/Shop").then((m) => ({ default: m.Shop })),
);
const MemberWallet = lazy(() =>
  import("../features/MemberWallet").then((m) => ({ default: m.MemberWallet })),
);
const AdminData = lazy(() =>
  import("../features/AdminData").then((m) => ({ default: m.AdminData })),
);
const Marketing = lazy(() =>
  import("../features/Marketing").then((m) => ({ default: m.Marketing })),
);
const Journeys = lazy(() =>
  import("../features/Journeys").then((m) => ({ default: m.Journeys })),
);
const OpsPages = lazy(() =>
  import("../features/OpsPages").then((m) => ({ default: m.OpsPages })),
);
const ProductOperations = lazy(() =>
  import("../features/ProductOperations").then((m) => ({
    default: m.ProductOperations,
  })),
);
const MemberGrowth = lazy(() =>
  import("../features/MemberGrowth").then((m) => ({ default: m.MemberGrowth })),
);
const CouponDeliveries = lazy(() =>
  import("../features/CouponDeliveries").then((m) => ({
    default: m.CouponDeliveries,
  })),
);
const Segments = lazy(() =>
  import("../features/Segments").then((m) => ({ default: m.Segments })),
);
const MarketingEffects = lazy(() =>
  import("../features/MarketingEffects").then((m) => ({
    default: m.MarketingEffects,
  })),
);
const Dashboard = lazy(() =>
  import("../features/Dashboard").then((m) => ({ default: m.Dashboard })),
);

const RuntimeOperations = lazy(() =>
  import("../features/RuntimeOperations").then((m) => ({
    default: m.RuntimeOperations,
  })),
);
const PlatformRuntime = lazy(() =>
  import("../features/PlatformRuntime").then((m) => ({
    default: m.PlatformRuntime,
  })),
);

const groups = [
  {
    key: "member",
    label: "会员经营",
    children: [
      ["members", "会员档案"],
      ["growth", "会员成长"],
      ["member-tags", "会员标签字典"],
      ["segments", "动态人群"],
      ["audiences", "人群快照"],
    ],
  },
  {
    key: "catalog",
    label: "商品与门店",
    children: [
      ["skus", "商品管理"],
      ["inventory", "库存额度"],
      ["merchants", "商家管理"],
      ["stores", "店铺管理"],
      ["store-grants", "经营授权"],
    ],
  },
  {
    key: "marketing",
    label: "营销与旅程",
    children: [
      ["campaigns", "活动管理"],
      ["effects", "营销效果"],
      ["rules", "动态规则"],
      ["budgets", "营销预算"],
      ["coupons", "优惠券"],
      ["coupon-deliveries", "定向发券"],
      ["definitions", "权益定义"],
      ["entitlements", "权益台账"],
      ["journeys", "营销旅程"],
      ["instances", "旅程实例"],
    ],
  },
  {
    key: "trade",
    label: "交易与交付",
    children: [
      ["orders", "订单工作台"],
      ["fulfillments", "履约队列"],
      ["aftersales", "售后审批"],
      ["refunds", "退款核对"],
    ],
  },
  {
    key: "platform",
    label: "平台工具",
    children: [
      ["pages", "低代码页面"],
      ["events", "异步事件"],
      ["recovery", "任务恢复"],
      ["replay", "历史重放"],
    ],
  },
];
const adminPages = [
  ["dashboard", "经营总览"],
  ...groups.flatMap((g) => g.children),
];
function route() {
  const [page, query] = location.hash.slice(1).split("?");
  return {
    page: page || "",
    store: new URLSearchParams(query).get("store") ?? "",
  };
}
export function App() {
  const { modal } = AntApp.useApp();
  const screens = Grid.useBreakpoint();
  const compact = screens.md === false;
  const [menuOpen, setMenuOpen] = useState(false);
  const [openGroups, setOpenGroups] = useState<string[]>(() =>
    groups
      .filter((g) => g.children.some(([key]) => key === route().page))
      .map((g) => g.key),
  );
  const [actor, setActor] = useState<Actor>();
  const [locationState, setLocationState] = useState(route);
  const [previousHash, setPreviousHash] = useState(location.hash);
  const [loginError, setLoginError] = useState<Error>();
  const [logging, setLogging] = useState(false);
  const [restoring, setRestoring] = useState(!!savedCredential());
  const [restoreError, setRestoreError] = useState<Error>();
  const [storageAvailable, setStorageAvailable] = useState(true);
  const [restoreRevision, setRestoreRevision] = useState(0);
  async function identity() {
    try {
      return await request<Actor>("/me");
    } catch (error) {
      // 平台身份读取只进入平台命名空间，不扩大任何租户接口权限。
      if (error instanceof ApiError && error.status === 403)
        return await request<Actor>("/platform/me");
      throw error;
    }
  }
  useEffect(() => {
    const token = savedCredential();
    if (!token) {
      setRestoring(false);
      return;
    }
    let active = true;
    setRestoring(true);
    setRestoreError(undefined);
    setAccessToken(token);
    identity()
      .then((value) => {
        if (active) setActor(value);
      })
      .catch((error) => {
        if (active && savedCredential()) setRestoreError(error);
      })
      .finally(() => {
        if (active) setRestoring(false);
      });
    return () => {
      active = false;
    };
  }, [restoreRevision]);
  useEffect(() => {
    const expired = () => {
      setActor(undefined);
      setRestoreError(undefined);
      setLoginError(new Error("登录已失效，请重新输入有效访问凭据"));
    };
    addEventListener(SESSION_EXPIRED, expired);
    return () => removeEventListener(SESSION_EXPIRED, expired);
  }, []);
  useEffect(() => {
    const listener = () => {
      const next = route();
      if (
        hasUnconfirmedCommand() &&
        (next.page !== locationState.page || next.store !== locationState.store)
      ) {
        history.replaceState(null, "", previousHash || "#");
        modal.warning({
          title: "请先确认当前操作结果",
          content: "请等待当前请求返回；结果未知时，在当前页面原样重试。",
          okText: "返回确认结果",
        });
        return;
      }
      setPreviousHash(location.hash);
      setLocationState(next);
    };
    addEventListener("hashchange", listener);
    return () => removeEventListener("hashchange", listener);
  }, [locationState.page, locationState.store, previousHash, modal]);
  const stores = useResource<Store[]>(
    actor && actor.role !== "PLATFORM_OPERATOR"
      ? actor.role === "OPERATOR"
        ? "/operations/stores"
        : "/stores"
      : null,
  );
  const capabilities = useResource<Capabilities>(
    actor && actor.role !== "PLATFORM_OPERATOR"
      ? "/runtime-capabilities"
      : null,
  );
  const navigate = (page: string, store = locationState.store) => {
    if (hasUnconfirmedCommand()) {
      modal.warning({
        title: "请先确认当前操作结果",
        content:
          "请等待当前请求返回；结果未知时，原样重试确认后再切换工作空间。",
        okText: "返回确认结果",
      });
      return;
    }
    setMenuOpen(false);
    const group = groups.find((g) => g.children.some(([key]) => key === page));
    if (group) setOpenGroups([group.key]);
    const query =
      page === locationState.page && store === locationState.store
        ? new URLSearchParams(location.hash.split("?")[1])
        : new URLSearchParams();
    if (store) query.set("store", store);
    else query.delete("store");
    location.hash = page + (query.size ? "?" + query : "");
  };
  useEffect(() => {
    if (actor && stores.data?.length && !locationState.store) {
      // 店铺读取可能晚于用户导航；以当前URL为准，避免旧effect覆盖新页面或已恢复的筛选。
      const current = route();
      if (current.store) return;
      const query = new URLSearchParams(location.hash.split("?")[1]);
      query.set("store", stores.data[0].storeId);
      const page =
        current.page ||
        (actor.role === "ADMIN"
          ? "dashboard"
          : actor.role === "OPERATOR"
            ? "skus"
            : "shop");
      location.hash = page + "?" + query;
    }
  }, [actor, stores.data, locationState.store]);
  useEffect(() => {
    const group = groups.find((g) =>
      g.children.some(([key]) => key === locationState.page),
    );
    if (group) setOpenGroups([group.key]);
  }, [locationState.page]);
  const logout = () => {
    if (hasUnconfirmedCommand()) {
      modal.warning({
        title: "请先确认当前操作结果",
        content: "请等待当前请求返回；结果未知时，原样重试确认后再退出。",
        okText: "返回确认结果",
      });
      return;
    }
    setAccessToken("");
    saveCredential("");
    setRestoreError(undefined);
    setActor(undefined);
    setLoginError(undefined);
    location.hash = "";
  };
  if (restoring && !actor)
    return (
      <div className="session-recovery">
        <Spin description="正在恢复登录与工作空间" />
      </div>
    );
  if (restoreError && !actor)
    return (
      <div className="session-recovery">
        <Alert
          type="warning"
          title="暂时无法恢复工作空间"
          description="登录状态已保留，服务恢复后可重试。"
        />
        <ErrorNotice error={restoreError} />
        <Space>
          <Button
            type="primary"
            onClick={() => setRestoreRevision((v) => v + 1)}
          >
            重试恢复
          </Button>
          <Button onClick={logout}>返回登录</Button>
        </Space>
      </div>
    );
  if (!actor)
    return (
      <ConfigProvider theme={memberTheme}>
        <div className="login-page">
          <div className="login-story">
            <div className="brand brand-light">
              <span className="brand-mark">
                <Icon name="overview" />
              </span>
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
            <div className="login-panel">
              <header className="login-panel-head">
                <p className="login-kicker">安全进入</p>
                <h2>进入业务空间</h2>
                <p className="login-lead">
                  粘贴已签发的访问凭据，系统按角色与租户打开对应工作台。
                </p>
              </header>
              <ErrorNotice error={loginError} />
              <Form
                className="login-form"
                layout="vertical"
                requiredMark={false}
                onFinish={async (v) => {
                  setLogging(true);
                  setLoginError(undefined);
                  saveCredential("");
                  setAccessToken(v.token.trim());
                  try {
                    const actorIdentity = await identity();
                    setStorageAvailable(saveCredential(v.token.trim()));
                    setActor(actorIdentity);
                    const requested = route();
                    const allowed =
                      actorIdentity.role === "PLATFORM_OPERATOR"
                        ? requested.page === "platform-runtime"
                        : actorIdentity.role === "ADMIN"
                          ? adminPages.some(([key]) => key === requested.page)
                          : actorIdentity.role === "OPERATOR"
                            ? requested.page === "skus"
                            : [
                                "shop",
                                "orders",
                                "coupons",
                                "benefits",
                                "growth",
                                "aftersales",
                                "notifications",
                              ].includes(requested.page);
                    if (!allowed)
                      navigate(
                        actorIdentity.role === "PLATFORM_OPERATOR"
                          ? "platform-runtime"
                          : actorIdentity.role === "ADMIN"
                            ? "dashboard"
                            : actorIdentity.role === "OPERATOR"
                              ? "skus"
                              : "shop",
                        "",
                      );
                  } catch (e) {
                    setAccessToken("");
                    setLoginError(
                      e instanceof Error ? e : new Error("登录失败"),
                    );
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
                    placeholder="粘贴访问凭据"
                    visibilityToggle
                  />
                </Form.Item>
                <Button
                  className="login-submit"
                  htmlType="submit"
                  type="primary"
                  loading={logging}
                  block
                >
                  进入平台
                </Button>
              </Form>
              <ul className="login-meta">
                <li>凭据仅保存在本次会话</li>
                <li>刷新后恢复工作空间，退出时清除凭据</li>
              </ul>
            </div>
          </main>
        </div>
      </ConfigProvider>
    );
  const platform = actor.role === "PLATFORM_OPERATOR";
  const operator = actor.role === "OPERATOR";
  const admin = actor.role !== "MEMBER";
  const page =
    locationState.page ||
    (platform
      ? "platform-runtime"
      : operator
        ? "skus"
        : admin
          ? "dashboard"
          : "shop");
  const store = locationState.store;
  const caps = capabilities.data ?? {
    sandboxEnabled: false,
    workersEnabled: false,
  };
  const menu = platform
    ? [
        {
          key: "platform-runtime",
          label: "运行健康",
          icon: <Icon name="platform" />,
        },
      ]
    : operator
      ? [{ key: "skus", label: "授权商品经营" }]
      : admin
        ? [
            {
              key: "dashboard",
              label: "经营总览",
              icon: <Icon name="overview" />,
            },
            ...groups.map((g) => ({
              key: g.key,
              label: g.label,
              icon: <Icon name={g.key as IconName} />,
              children: g.children.map(([key, label]) => ({ key, label })),
            })),
          ]
        : [
            ["shop", "逛店铺"],
            ["orders", "我的订单"],
            ["coupons", "优惠券"],
            ["benefits", "我的权益"],
            ["growth", "我的成长"],
            ["aftersales", "售后"],
            ["notifications", "消息"],
          ].map(([key, label]) => ({ key, label }));
  let content;
  if (platform)
    content =
      page === "platform-runtime" ? (
        <PlatformRuntime />
      ) : (
        <Alert type="warning" title="请进入平台运行健康查看已授权指标" />
      );
  else if (admin && (page === "recovery" || page === "replay"))
    content = <RuntimeOperations mode={page} />;
  else if (!operator && admin && page === "dashboard")
    content = <Dashboard key={store} store={store} navigate={navigate} />;
  else if (admin && page === "skus")
    content = <ProductOperations key={store} store={store} />;
  else if (operator)
    content = <Alert type="warning" title="请从导航进入已授权的商品经营功能" />;
  else if (admin && page === "coupon-deliveries")
    content = <CouponDeliveries key={store} store={store} />;
  else if (page === "growth")
    content = <MemberGrowth admin={admin} store={store} />;
  else if (admin && page === "segments") content = <Segments />;
  else if (admin && page === "effects")
    content = <MarketingEffects key={store} store={store} />;
  else if (page === "orders")
    content = <Orders admin={admin} capabilities={caps} />;
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
  else if (adminPages.some(([key]) => key === page))
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
  const shell = (
    <Layout className={admin ? "app admin-app" : "app member-app"}>
      {admin && !compact && (
        <Layout.Sider width={236} theme="light" className="sidebar">
          <WorkspaceBrand />
          <div className="nav-caption">
            {platform
              ? "运行健康工作空间"
              : operator
                ? "门店经营工作空间"
                : "经营工作空间"}
          </div>
          <Menu
            aria-label="经营导航"
            mode="inline"
            selectedKeys={[page]}
            openKeys={openGroups}
            onOpenChange={(keys) => setOpenGroups(keys)}
            items={menu}
            onClick={({ key }) => navigate(key)}
          />
          <div className="sidebar-note">
            {platform
              ? "跨租户聚合 · 只读指标"
              : operator
                ? "门店商品 · 授权经营"
                : "会员 · 商品 · 营销"}
            <br />
            {platform
              ? "从运行事实发现问题"
              : operator
                ? "操作范围由业务权限核验"
                : "让每一次经营都有据可循"}
          </div>
        </Layout.Sider>
      )}
      {admin && compact && (
        <RecordModal
          title="经营导航"
          expandable={false}
          footer={null}
          width={480}
          open={menuOpen}
          onCancel={() => setMenuOpen(false)}
        >
          <Menu
            mode="inline"
            selectedKeys={[page]}
            openKeys={openGroups}
            onOpenChange={(keys) => setOpenGroups(keys)}
            items={menu}
            onClick={({ key }) => navigate(key)}
          />
        </RecordModal>
      )}
      <Layout>
        <Layout.Header className="topbar">
          {admin && compact && (
            <Button aria-label="打开经营导航" onClick={() => setMenuOpen(true)}>
              菜单
            </Button>
          )}
          {admin && (
            <div className="workspace-location">
              <span>
                {platform
                  ? "平台运维"
                  : operator
                    ? "商品与门店"
                    : (groups.find((g) =>
                        g.children.some(([key]) => key === page),
                      )?.label ?? "经营工作台")}
              </span>
              <strong>
                {platform
                  ? "运行健康"
                  : (adminPages.find(([key]) => key === page)?.[1] ??
                    "商品经营")}
              </strong>
            </div>
          )}
          {admin && !operator && !platform && (
            <WorkspaceSearch
              label="搜索功能"
              groups={[
                { label: "工作台", pages: [["dashboard", "经营总览"]] },
                ...groups.map((g) => ({
                  label: g.label,
                  pages: g.children.map(
                    ([target, label]) => [target, label] as const,
                  ),
                })),
              ]}
              onNavigate={navigate}
            />
          )}

          {!admin && (
            <div className="brand">
              <span className="brand-mark">
                <Icon name="bag" />
              </span>
              <span>日常好物</span>
            </div>
          )}
          {!platform && (
            <Space className="store-context">
              {admin && <span className="context-label">店铺</span>}
              <Select
                aria-label="当前店铺"
                value={store || undefined}
                placeholder={admin ? "选择店铺" : "切换店铺"}
                style={{ width: compact ? 170 : admin ? 210 : 200 }}
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
          )}
          {platform && (
            <span className="context-label">平台运维 · 只读聚合</span>
          )}
          <Space className="account-context">
            <Avatar
              size="small"
              style={{ background: "var(--selected)", color: "var(--accent)" }}
            >
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
          {!storageAvailable && (
            <Alert
              type="info"
              title="浏览器未允许会话存储，当前登录仍可使用，刷新后需要重新登录"
            />
          )}
          <ErrorNotice error={stores.error} />
          <ErrorNotice error={capabilities.error} />
          <div key={actor.tenantId + actor.actorId + page + store}>
            <Suspense
              fallback={
                <div className="page-loading">
                  <Spin description="正在加载经营页面" />
                </div>
              }
            >
              {content}
            </Suspense>
          </div>
        </Layout.Content>
        <Layout.Footer className="footer">
          {!admin ? (
            <div className="member-footer">
              <div className="member-footer-legal">
                <span className="footer-brand">日常好物</span>
                <span>订单、优惠券和积分，可在会员导航中查看。</span>
              </div>
            </div>
          ) : (
            <>
              统一电商业务平台{" "}
              <span>
                {actor.tenantId} ·{" "}
                {platform
                  ? "平台运维 · 只读聚合"
                  : operator
                    ? "门店经营"
                    : "运营管理"}
              </span>
            </>
          )}
        </Layout.Footer>
      </Layout>
    </Layout>
  );
  return admin ? (
    shell
  ) : (
    <ConfigProvider theme={memberTheme}>{shell}</ConfigProvider>
  );
}
