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
import { ErrorNotice, PageHead } from "../shared/ui";
import { CentralError, HTTP, type Context } from "./api";

const OfferStatus = { ACTIVE: "ACTIVE", INACTIVE: "INACTIVE" } as const;
const AssetKind = { COUPON: "COUPON", ENTITLEMENT: "ENTITLEMENT" } as const;
const Action = { DEFINE: "define", STATUS: "status" } as const;
type Action = (typeof Action)[keyof typeof Action];
const titles: Record<Action, string> = {
  define: "定义兑换商品",
  status: "停启兑换商品",
};
type Offer = {
  offerId: string;
  storeId: string;
  name: string;
  kind: (typeof AssetKind)[keyof typeof AssetKind];
  assetId: string;
  assetVersion: number;
  points: number;
  quota: number;
  perMemberLimit: number;
  validFrom: string;
  validTo: string;
};
type OfferView = {
  content: Offer;
  status: string;
  issued: number;
  version: number;
};
type Values = Offer & {
  expectedVersion: number;
  active: boolean;
  reason: string;
};
const PAGE_SIZE = 50;
const identifier = /^[A-Za-z0-9_-]{1,64}$/;
const denied = (e?: Error) =>
  e instanceof ApiError && e.status === HTTP.FORBIDDEN;

/** 本页凭据仅发送至积分兑换商品能力允许列表，不能退回旧控制台通用请求。 */
function offersClient(context: Context, expired: () => void): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    if (!(
      (method === "GET" &&
        (/^\/admin\/point-offers\?storeId=[A-Za-z0-9_-]{1,64}&after=[A-Za-z0-9_-]{0,64}&limit=50$/.test(
          path,
        ) ||
          /^\/operations\/point-offers\/(define|status)-access$/.test(path))) ||
      (method === "POST" &&
        /^\/admin\/point-offers(\/[A-Za-z0-9_-]{1,64}\/status)?$/.test(path))
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

function OfferDirectory() {
  const [store, setStore] = useState<string>(),
    [revision, setRevision] = useState(0);
  return (
    <Space orientation="vertical" size="large" style={{ width: "100%" }}>
      <Typography.Text type="secondary">
        使用已知门店编号查询；兑换商品读取权限不包含门店或资产目录读取。客户能否兑换仍由实际窗口、余额和额度决定。
      </Typography.Text>
      <Form
        name="offer-search"
        layout="vertical"
        style={{ maxWidth: 700, width: "100%" }}
        onFinish={(v) => {
          setStore(v.storeId);
          setRevision((n) => n + 1);
        }}
      >
        <Form.Item
          name="storeId"
          label="查询门店编号"
          rules={[
            {
              required: true,
              pattern: identifier,
              message: "请输入1至64位字母、数字、下划线或连字符",
            },
          ]}
        >
          <Input maxLength={64} />
        </Form.Item>
        <Button htmlType="submit" type="primary">
          查询兑换商品
        </Button>
      </Form>
      {store && <OfferList key={`${store}:${revision}`} store={store} />}
    </Space>
  );
}
function OfferList({ store }: { store: string }) {
  const [after, setAfter] = useRouteState("OfferList.after", "");
  const rows = useResource<OfferView[]>(
    `/admin/point-offers?storeId=${encodeURIComponent(store)}&after=${encodeURIComponent(after)}&limit=${PAGE_SIZE}`,
  );
  return (
    <Space orientation="vertical" style={{ width: "100%" }}>
      <ErrorNotice error={rows.error} />
      {!rows.error && (
        <Table<OfferView>
          rowKey={(v) => v.content.offerId}
          dataSource={rows.data ?? []}
          loading={rows.loading}
          pagination={false}
          scroll={{ x: 1900 }}
          columns={[
            { title: "商品编号", dataIndex: ["content", "offerId"] },
            { title: "名称", dataIndex: ["content", "name"] },
            { title: "门店", dataIndex: ["content", "storeId"] },
            {
              title: "资产类型",
              dataIndex: ["content", "kind"],
              render: (v) =>
                v === AssetKind.COUPON
                  ? "优惠券"
                  : v === AssetKind.ENTITLEMENT
                    ? "权益"
                    : v,
            },
            { title: "资产编号", dataIndex: ["content", "assetId"] },
            { title: "资产版本", dataIndex: ["content", "assetVersion"] },
            { title: "兑换积分", dataIndex: ["content", "points"] },
            { title: "总兑换额度", dataIndex: ["content", "quota"] },
            { title: "单会员上限", dataIndex: ["content", "perMemberLimit"] },
            { title: "已兑换次数", dataIndex: "issued" },
            {
              title: "状态",
              dataIndex: "status",
              render: (v) =>
                v === OfferStatus.ACTIVE
                  ? "启用"
                  : v === OfferStatus.INACTIVE
                    ? "停用"
                    : v,
            },
            { title: "商品版本", dataIndex: "version" },
            {
              title: "生效时间",
              dataIndex: ["content", "validFrom"],
              render: (v) => new Date(v).toLocaleString(),
            },
            {
              title: "失效时间",
              dataIndex: ["content", "validTo"],
              render: (v) => new Date(v).toLocaleString(),
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
          onClick={() => setAfter(rows.data!.at(-1)!.content.offerId)}
        >
          下一批商品
        </Button>
      </Space>
    </Space>
  );
}
function OfferValue({ value }: { value: OfferView }) {
  return (
    <Descriptions
      column={{ xs: 1, sm: 2 }}
      items={[
        { key: "id", label: "商品编号", children: value.content.offerId },
        {
          key: "status",
          label: "状态",
          children:
            value.status === OfferStatus.ACTIVE
              ? "启用"
              : value.status === OfferStatus.INACTIVE
                ? "停用"
                : value.status,
        },
        { key: "version", label: "商品版本", children: String(value.version) },
        { key: "issued", label: "已兑换次数", children: String(value.issued) },
      ]}
    />
  );
}

/** 两个写操作保存各自的原始意图，结果未知后不能更换输入或幂等键。 */
function OfferCommand({
  action,
  client,
  markDirty,
}: {
  action: Action;
  client: typeof request;
  markDirty: (value: boolean) => void;
}) {
  const access = useResource<{ allowed: boolean }>(
    `/operations/point-offers/${action}-access`,
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
    [result, setResult] = useState<OfferView>();
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
          path:
            action === Action.DEFINE
              ? "/admin/point-offers"
              : `/admin/point-offers/${encodeURIComponent(value.offerId)}/status`,
          body:
            action === Action.DEFINE
              ? {
                  offerId: value.offerId,
                  storeId: value.storeId,
                  name: value.name.trim(),
                  kind: value.kind,
                  assetId: value.assetId,
                  assetVersion: value.assetVersion,
                  points: value.points,
                  quota: value.quota,
                  perMemberLimit: value.perMemberLimit,
                  validFrom: new Date(value.validFrom).toISOString(),
                  validTo: new Date(value.validTo).toISOString(),
                }
              : {
                  expectedVersion: value.expectedVersion,
                  active: value.active,
                  reason: value.reason.trim(),
                },
        };
      const { path, ...options } = intent.current;
      const saved = await client<OfferView>(path, {
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
          description={<OfferValue value={result} />}
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
            name={`offer-${action}`}
            form={form}
            layout="vertical"
            onFinish={submit}
            onValuesChange={() => markDirty(true)}
            initialValues={{}}
          >
            <Form.Item
              name="offerId"
              label="积分商品编号"
              rules={[
                {
                  required: true,
                  pattern: identifier,
                  message: "请输入1至64位字母、数字、下划线或连字符",
                },
              ]}
            >
              <Input disabled={busy || frozen} maxLength={64} />
            </Form.Item>
            {action === Action.DEFINE ? (
              <>
                <Alert
                  type="info"
                  title="商品规则创建后不可修改，初始状态为启用；实际兑换还需满足有效期和资产资格。"
                />
                <Form.Item
                  name="storeId"
                  label="门店编号"
                  rules={[
                    {
                      required: true,
                      pattern: identifier,
                      message: "请输入有效门店编号",
                    },
                  ]}
                >
                  <Input disabled={busy || frozen} maxLength={64} />
                </Form.Item>
                <Form.Item
                  name="name"
                  label="商品名称"
                  rules={[
                    {
                      required: true,
                      whitespace: true,
                      max: 128,
                      message: "请输入不超过128字的名称",
                    },
                  ]}
                >
                  <Input disabled={busy || frozen} maxLength={128} />
                </Form.Item>
                <Form.Item
                  name="kind"
                  label="资产类型"
                  rules={[{ required: true, message: "请选择资产类型" }]}
                >
                  <Radio.Group
                    disabled={busy || frozen}
                    options={[
                      { value: AssetKind.COUPON, label: "优惠券" },
                      { value: AssetKind.ENTITLEMENT, label: "权益" },
                    ]}
                  />
                </Form.Item>
                <Form.Item
                  name="assetId"
                  label="资产编号"
                  rules={[
                    {
                      required: true,
                      pattern: identifier,
                      message: "请输入有效资产编号",
                    },
                  ]}
                >
                  <Input disabled={busy || frozen} maxLength={64} />
                </Form.Item>
                {number("assetVersion", "资产版本", 1)}
                {number("points", "兑换积分", 1, 1000000000)}
                {number("quota", "总兑换额度", 1, 1000000)}
                {number("perMemberLimit", "单会员兑换上限", 1, 1000)}
                <Form.Item
                  name="validFrom"
                  label="生效时间"
                  extra="按本机时区输入，提交时转换为UTC。"
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
                  name="validTo"
                  label="失效时间"
                  dependencies={["validFrom"]}
                  rules={[
                    { required: true, message: "请选择失效时间" },
                    {
                      validator: (_, v) =>
                        !v ||
                        (Number.isFinite(new Date(v).getTime()) &&
                          new Date(v).getTime() >
                            new Date(form.getFieldValue("validFrom")).getTime())
                          ? Promise.resolve()
                          : Promise.reject(
                              new Error("失效时间必须晚于生效时间"),
                            ),
                    },
                  ]}
                >
                  <Input type="datetime-local" disabled={busy || frozen} />
                </Form.Item>
              </>
            ) : (
              <>
                {number("expectedVersion", "当前商品版本")}
                <Form.Item
                  name="active"
                  label="目标状态"
                  rules={[{ required: true, message: "请选择目标状态" }]}
                >
                  <Radio.Group
                    disabled={busy || frozen}
                    options={[
                      { value: true, label: "启用" },
                      { value: false, label: "停用" },
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
                    disabled={busy || frozen}
                    maxLength={256}
                  />
                </Form.Item>
                <Alert
                  type="info"
                  title="停用只阻止新兑换，不撤销已发出的优惠券或权益。"
                />
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

/** 独立动作不依赖目录读取；未确认的写入只允许原意图重试。 */
export function CentralPointOffers({
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
  const [expired, setExpired] = useState(false);
  const dirty = useRef(new Set<Action>());
  const { modal } = App.useApp();
  const client = useMemo(
    () => offersClient(context, () => setExpired(true)),
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
        title: "离开积分兑换商品？",
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
            title="积分兑换商品"
            description="配置积分兑换标的，确认扣减与补偿边界。"
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
                    key: "directory",
                    label: "兑换商品目录",
                    children: <OfferDirectory />,
                  },
                  ...Object.values(Action).map((action) => ({
                    key: action,
                    label: titles[action],
                    children: (
                      <OfferCommand
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
