import { PageHead } from "../shared/ui";
import { Alert, Button, Card, Form, Input, Space, Typography } from "antd";
import { lazy, Suspense, useMemo, useState } from "react";
const ProductOperations = lazy(() =>
  import("../features/ProductOperations").then((m) => ({
    default: m.ProductOperations,
  })),
);
import { ApiError, RequestContext, type request } from "../shared/api";
import { HTTP, type Context } from "./api";

/** 只复用CATALOG组件；固定路径避免中央身份被共享组件传给旧管理接口。 */
export function catalogClient(
  context: Context,
  expired: () => void,
): typeof request {
  return async <T,>(
    path: string,
    options: Parameters<typeof request>[1] = {},
  ) => {
    if (
      !/^\/operations\/(products|skus|catalog-jobs|catalog-categories|specification-templates|catalog-search)(\/|\?|$)/.test(
        path,
      )
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
      method: options.method ?? "GET",
      headers,
      body:
        options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal ?? AbortSignal.timeout(15000),
    });
    if (response.status === HTTP.UNAUTHORIZED) expired();
    const result = await response.json();
    if (!response.ok)
      throw new ApiError(
        response.status,
        response.status === HTTP.UNAVAILABLE
          ? "授权服务暂不可用，请稍后重试"
          : (result.message ?? "请求未能完成"),
        result.traceId,
      );
    return result as T;
  };
}

/** 门店编号只是查询上下文，实际经营范围由每次请求的Owner事实和中央授权决定。 */
export function CentralCatalog({
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
  const client = useMemo(
    () => catalogClient(context, () => setExpired(true)),
    [context.token, context.tenant],
  );
  return (
    <div className="central-products">
      <Card>
        <Space orientation="vertical" style={{ width: "100%" }}>
          <PageHead
            eyebrow="企业经营"
            title="商品经营"
            description="维护有经营权限的门店商品、规格与展示资料。"
            extra={<Button onClick={() => void onLogout()}>退出登录</Button>}
          />
          {expired ? (
            <Alert
              type="warning"
              title="登录已失效，请重新登录"
              action={<Button onClick={() => void onLogout()}>返回登录</Button>}
            />
          ) : (
            <>
              <Form
                layout="inline"
                initialValues={{ store }}
                onFinish={({ store: value }: { store: string }) => {
                  const next = value.trim();
                  const url = new URL(location.href);
                  url.searchParams.set("store_id", next);
                  history.replaceState(null, "", url.pathname + url.search);
                  setStore(next);
                }}
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
                    {
                      pattern: /^[A-Za-z0-9_-]{1,100}$/,
                      message: "门店编号格式不正确",
                    },
                  ]}
                >
                  <Input
                    placeholder="输入获授权的门店编号"
                    autoComplete="off"
                  />
                </Form.Item>
                <Form.Item>
                  <Button htmlType="submit" type="primary">
                    进入门店
                  </Button>
                </Form.Item>
              </Form>
              {!store && (
                <Alert
                  type="info"
                  title="请输入获授权的门店编号，或使用包含门店的工作台链接"
                />
              )}
            </>
          )}
        </Space>
      </Card>
      {!expired && store && (
        <RequestContext.Provider value={client}>
          <Suspense fallback={<Alert type="info" title="正在载入商品经营" />}>
            <ProductOperations
              key={`${context.tenant}:${store}`}
              store={store}
              embedded
            />
          </Suspense>
        </RequestContext.Provider>
      )}
    </div>
  );
}
