import { Button, Card, Form, Input, Space } from "antd";
import { useState } from "react";
import { useResource } from "../shared/api";
import { ErrorNotice } from "../shared/ui";
import {
  OperationsWorkspace,
  OperationAction,
  OperationDetails,
  type OperationsProps,
  type OperationRow,
} from "./OperationsWorkspace";
import { identifier } from "./operationsClient";
function PaymentRead() {
  const [order, setOrder] = useState("");
  const view = useResource<OperationRow>(
    order ? `/admin/orders/${order}/payment` : null,
  );
  return (
    <Card title="按原订单读取支付">
      <Form layout="inline" onFinish={(v) => setOrder(v.orderId)}>
        <Form.Item
          name="orderId"
          label="订单编号"
          rules={[
            {
              required: true,
              pattern: identifier,
              message: "请输入实际订单编号",
            },
          ]}
        >
          <Input maxLength={64} />
        </Form.Item>
        <Button htmlType="submit" loading={view.loading}>
          读取支付
        </Button>
        <Button onClick={view.refresh} disabled={!order}>
          刷新
        </Button>
      </Form>
      <ErrorNotice error={view.error} onRetry={view.refresh} />
      {view.data && (
        <OperationDetails
          row={view.data}
          fields={[
            ["paymentId", "支付编号"],
            ["orderId", "订单编号"],
            ["amount", "支付金额"],
            ["currency", "币种"],
            ["provider", "原渠道"],
            ["status", "渠道状态"],
            ["version", "数据版本"],
          ]}
        />
      )}
    </Card>
  );
}
export default function CentralPayments(props: OperationsProps) {
  return (
    <OperationsWorkspace
      {...props}
      family="payments"
      title="支付核对"
      description="支付读取和核对单独授权，不能由订单读取推导资金操作资格。"
    >
      {(client, dirty) => (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Card title="核对原支付">
            <OperationAction
              client={client}
              dirty={dirty}
              family="payments"
              action="reconcile"
              label="核对支付"
              keyed={false}
              fields={[{ name: "orderId", label: "原订单编号", kind: "id" }]}
              path={(v) => `/admin/orders/${v.orderId}/payment/reconcile`}
              note="读取可信渠道的原金额、币种与状态。UNKNOWN不表示已付款；重试核对同一笔原支付。"
            />
          </Card>
          <PaymentRead />
        </Space>
      )}
    </OperationsWorkspace>
  );
}
