import { useRouteState } from "../shared/routeState";
import {
  Alert,
  App,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Space,
  Table,
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

type Stock = {
  storeId: string;
  skuId: string;
  available: number;
  held: number;
  sold: number;
  version: number;
};
type Receipt = { storeId: string; skuId: string; quantity: number };
const PAGE_SIZE = 50;
const identifier = /^[A-Za-z0-9_-]{1,100}$/;

/** 中央库存只连接已登记的三个接口，不把Bearer传给旧控制台。 */
function inventoryClient(
  context: Context,
  expired: () => void,
): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    const method = options.method ?? "GET";
    if (
      !(
        method === "GET" &&
        /^\/(admin\/inventory|operations\/inventory\/actions)\?/.test(path)
      ) &&
      !(method === "POST" && path === "/admin/inventory/receipts")
    )
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

/** 门店是查询上下文；真实范围由服务端逐次判定。 */
export function CentralInventory({
  context,
  onLogout,
}: {
  context: Context;
  onLogout: () => Promise<void>;
}) {
  const [store, setStore] = useState(
    () => new URLSearchParams(location.search).get("store_id") ?? "",
  );
  const [expired, setExpired] = useState(false);
  const dirty = useRef(false);
  const { modal } = App.useApp();
  const client = useMemo(
    () => inventoryClient(context, () => setExpired(true)),
    [context.token, context.tenant],
  );
  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => {
      if (dirty.current) {
        e.preventDefault();
        e.returnValue = "";
      }
    };
    window.addEventListener("beforeunload", warn);
    return () => window.removeEventListener("beforeunload", warn);
  }, []);
  function leave(action: () => void) {
    if (!dirty.current) action();
    else
      modal.confirm({
        title: "离开库存入库？",
        content:
          "未保存输入或未确认结果将离开当前页面；结果未知时请先原样重试确认，避免重复入库。",
        okText: "仍然离开",
        cancelText: "留在当前页",
        onOk: action,
      });
  }
  return (
    <main className="central-products">
      <Card>
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Space wrap>
            <Typography.Title level={2} style={{ margin: 0 }}>
              库存额度
            </Typography.Title>
            <Button onClick={() => leave(() => void onLogout())}>
              退出登录
            </Button>
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
            <Form
              layout="inline"
              initialValues={{ store }}
              onFinish={({ store: value }: { store: string }) =>
                leave(() => {
                  const next = value.trim();
                  const url = new URL(location.href);
                  url.searchParams.set("store_id", next);
                  history.replaceState(null, "", url.pathname + url.search);
                  dirty.current = false;
                  setStore(next);
                })
              }
            >
              <Form.Item
                name="store"
                label="门店编号"
                rules={[
                  {
                    required: true,
                    whitespace: true,
                    message: "请输入门店编号",
                  },
                  { pattern: identifier, message: "门店编号格式不正确" },
                ]}
              >
                <Input placeholder="输入获授权的门店编号" autoComplete="off" />
              </Form.Item>
              <Form.Item>
                <Button htmlType="submit" type="primary">
                  进入门店
                </Button>
              </Form.Item>
            </Form>
          )}
          {!expired && !store && (
            <Alert
              type="info"
              title="请输入获授权的门店编号，或使用包含门店的工作台链接"
            />
          )}
        </Space>
      </Card>
      {!expired && store && (
        <RequestContext.Provider value={client}>
          <Inventory
            key={`${context.tenant}:${store}`}
            store={store}
            client={client}
            markDirty={(v) => {
              dirty.current = v;
            }}
          />
        </RequestContext.Provider>
      )}
    </main>
  );
}
function Inventory({
  store,
  client,
  markDirty,
}: {
  store: string;
  client: typeof request;
  markDirty: (v: boolean) => void;
}) {
  const [after, setAfter] = useRouteState("Inventory.after", "");
  const rows = useResource<Stock[]>(
    `/admin/inventory?storeId=${encodeURIComponent(store)}&after=${encodeURIComponent(after)}&limit=${PAGE_SIZE}`,
  );
  const actions = useResource<{ receive: boolean }>(
    `/operations/inventory/actions?storeId=${encodeURIComponent(store)}`,
  );
  const [form] = Form.useForm<Receipt>();
  const { message } = App.useApp();
  const intent = useRef<{ key: string; body: Receipt } | null>(null);
  const running = useRef(false);
  const [busy, setBusy] = useState(false),
    [frozen, setFrozen] = useState(false);
  const [error, setError] = useState<Error>();
  const [writeDenied, setWriteDenied] = useState(false);
  const refresh = () => {
    rows.refresh();
    actions.refresh();
    setWriteDenied(false);
  };
  const canReceive =
    actions.data?.receive &&
    !actions.error &&
    !actions.loading &&
    !rows.error &&
    !writeDenied;
  async function receive(value: Receipt) {
    if (running.current || !canReceive) return;
    running.current = true;
    setBusy(true);
    setError(undefined);
    if (!intent.current)
      intent.current = {
        key: crypto.randomUUID(),
        body: {
          storeId: store,
          skuId: value.skuId.trim(),
          quantity: value.quantity,
        },
      };
    try {
      await client<Stock>("/admin/inventory/receipts", {
        method: "POST",
        ...intent.current,
      });
      intent.current = null;
      setFrozen(false);
      markDirty(false);
      form.resetFields();
      message.success("库存已增加");
      refresh();
    } catch (failure) {
      setError(failure as Error);
      // 曾丢失成功响应时，后续拒绝也不能证明旧命令未生效，保留原键直到确认。
      if (failure instanceof ApiError && failure.status < 500 && !frozen) {
        intent.current = null;
        setFrozen(false);
      } else {
        setFrozen(true);
        markDirty(true);
      }
      if (failure instanceof ApiError && failure.status === HTTP.FORBIDDEN) {
        setWriteDenied(true);
        rows.refresh();
        actions.refresh();
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
      style={{ width: "100%", marginTop: 24 }}
    >
      <Card
        title="门店库存"
        extra={
          <Button onClick={refresh} disabled={busy}>
            重新核验权限与库存
          </Button>
        }
      >
        <Typography.Paragraph type="secondary">
          可售、预占与已售分别记录；资金未知期间保留占用。
        </Typography.Paragraph>
        <ErrorNotice error={rows.error} />
        <ErrorNotice error={actions.error} />
        {!rows.error && (
          <Table<Stock>
            rowKey="skuId"
            dataSource={rows.data ?? []}
            loading={rows.loading}
            pagination={false}
            scroll={{ x: 640 }}
            columns={[
              { title: "SKU", dataIndex: "skuId" },
              { title: "可售", dataIndex: "available" },
              { title: "预占", dataIndex: "held" },
              { title: "已售", dataIndex: "sold" },
              { title: "版本", dataIndex: "version" },
            ]}
          />
        )}
        <Space style={{ marginTop: 16 }}>
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
            onClick={() => setAfter(rows.data!.at(-1)!.skuId)}
          >
            下一页
          </Button>
        </Space>
      </Card>
      <ErrorNotice error={error} />
      {canReceive ? (
        <Card title="增加库存">
          <Form
            form={form}
            layout="vertical"
            onFinish={receive}
            onValuesChange={() => markDirty(true)}
          >
            {frozen && (
              <Alert
                type="warning"
                title="入库结果尚未确认，请保留原输入重试"
              />
            )}
            <Form.Item
              name="skuId"
              label="SKU标识"
              rules={[
                { required: true, message: "请输入SKU标识" },
                { pattern: identifier, message: "SKU标识格式不正确" },
              ]}
            >
              <Input
                disabled={busy || frozen}
                autoComplete="off"
                placeholder="输入当前门店已发布的SKU标识"
              />
            </Form.Item>
            <Form.Item
              name="quantity"
              label="入库数量"
              rules={[
                { required: true, message: "请输入入库数量" },
                {
                  type: "integer",
                  min: 1,
                  max: 1000000,
                  message: "数量须为1至1000000的整数",
                },
              ]}
            >
              <InputNumber
                disabled={busy || frozen}
                min={1}
                max={1000000}
                precision={0}
                style={{ width: "100%" }}
              />
            </Form.Item>
            <Button type="primary" htmlType="submit" loading={busy}>
              {frozen ? "原样重试入库" : "确认入库"}
            </Button>
          </Form>
        </Card>
      ) : (
        !actions.loading &&
        !actions.error &&
        !rows.error && (
          <Alert type="info" title="当前可查看库存；入库需要另行授权" />
        )
      )}
    </Space>
  );
}
