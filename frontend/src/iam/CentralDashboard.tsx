import { Button, Card, Form, Input, Space } from "antd";
import { useMemo } from "react";
import { Dashboard } from "../features/Dashboard";
import { ExceptionQueues } from "../features/ExceptionQueues";
import { RequestContext } from "../shared/api";
import { useRouteState } from "../shared/routeState";
import { useWorkspaceHref } from "../shared/workspaceLinks";
import {
  OperationsWorkspace,
  type OperationsProps,
} from "./OperationsWorkspace";
import { identifier, operationsClient } from "./operationsClient";
export default function CentralDashboard(props: OperationsProps) {
  const [query, setStore] = useRouteState("store_id", "");
  const store = identifier.test(query) ? query : "";
  const href = useWorkspaceHref();
  const queueClients = useMemo(
    () => ({
      orders: operationsClient(props.context, "orders", () => undefined),
      fulfillments: operationsClient(
        props.context,
        "fulfillments",
        () => undefined,
      ),
      aftersales: operationsClient(
        props.context,
        "aftersales",
        () => undefined,
      ),
      refunds: operationsClient(props.context, "refunds", () => undefined),
    }),
    [props.context.token, props.context.tenant],
  );
  const routes: Record<string, string> = {
    growth: "member-growth",
    skus: "catalog",
    effects: "marketing-effects",
  };
  return (
    <OperationsWorkspace
      {...props}
      family="dashboard"
      title="经营总览"
      description="总览独立核验会员、商品与营销效果来源，任何缺失权限都不会被总览资格覆盖。"
    >
      {() => (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Card>
            <Form
              layout="inline"
              initialValues={{ storeId: store }}
              onFinish={(v) => setStore(v.storeId)}
            >
              <Form.Item
                name="storeId"
                label="实际门店编号"
                rules={[
                  {
                    required: true,
                    pattern: identifier,
                    message: "请输入实际门店编号",
                  },
                ]}
              >
                <Input maxLength={64} />
              </Form.Item>
              <Button htmlType="submit">查看总览</Button>
            </Form>
          </Card>
          <Dashboard
            key={store}
            embedded
            store={store}
            exceptions={
              <ExceptionQueues
                store={store}
                provide={(family, child) => (
                  <RequestContext.Provider value={queueClients[family]}>
                    {child}
                  </RequestContext.Provider>
                )}
              />
            }
            navigate={(page) => {
              const target = href(
                routes[page] ?? page,
                page === "skus" ? { store_id: store } : {},
              );
              if (target) location.assign(target);
            }}
            canNavigate={(page) => !!href(routes[page] ?? page)}
          />
        </Space>
      )}
    </OperationsWorkspace>
  );
}
