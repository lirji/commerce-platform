import { Button, Card, Form, Input, Space } from "antd";
import { useState } from "react";
import { Dashboard } from "../features/Dashboard";
import {
  OperationsWorkspace,
  type OperationsProps,
} from "./OperationsWorkspace";
import { identifier } from "./operationsClient";
export default function CentralDashboard(props: OperationsProps) {
  const [store, setStore] = useState("");
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
            <Form layout="inline" onFinish={(v) => setStore(v.storeId)}>
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
            store={store}
            navigate={(page) => {
              const routes: Record<string, string> = {
                growth: "member-growth",
                skus: "products",
                effects: "marketing-effects",
              };
              location.href = `/operations/${routes[page] ?? page}?tenant_id=${encodeURIComponent(props.context.tenant)}`;
            }}
          />
        </Space>
      )}
    </OperationsWorkspace>
  );
}
