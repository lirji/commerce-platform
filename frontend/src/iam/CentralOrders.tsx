import { Card, Space } from "antd";
import {
  OperationsWorkspace,
  OperationsList,
  OperationAction,
  type OperationsProps,
} from "./OperationsWorkspace";
const fields: readonly [string, string][] = [
  ["orderId", "订单编号"],
  ["memberId", "会员编号"],
  ["storeId", "实际门店"],
  ["merchantId", "商家编号"],
  ["status", "订单状态"],
  ["paymentKind", "支付方式"],
  ["payable", "应付金额"],
  ["version", "数据版本"],
  ["createdAt", "创建时间"],
  ["expiresAt", "到期时间"],
];
export default function CentralOrders(props: OperationsProps) {
  return (
    <OperationsWorkspace
      {...props}
      family="orders"
      title="订单与到期"
      description="从真实订单归属核对经营范围，到期仍遵守原订单与支付生命周期。"
    >
      {(client, dirty) => (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Card title="独立订单操作">
            <Space wrap>
              <OperationAction
                client={client}
                dirty={dirty}
                family="orders"
                action="expire"
                label="处理当前到期批次"
                path={() => "/admin/orders/expire"}
                note="本次最多处理20笔当前可操作的到期订单。支付中订单先关闭渠道，不能直接判为未付款。"
              />
              <OperationAction
                client={client}
                dirty={dirty}
                family="orders"
                action="expiry-retry"
                label="重试订单到期"
                fields={[
                  { name: "orderId", label: "原停止订单编号", kind: "id" },
                ]}
                path={(v) => `/admin/orders/${v.orderId}/expiry/retry`}
                note="只重新启用原停止订单的到期责任；不会新增订单或跳过支付核对。"
              />
            </Space>
          </Card>
          <OperationsList
            path="/admin/orders"
            idField="orderId"
            columns={[
              ["orderId", "订单"],
              ["storeId", "门店"],
              ["status", "状态"],
              ["payable", "应付金额"],
              ["memberId", "会员编号"],
              ["merchantId", "商家编号"],
              ["paymentKind", "付款方式"],
              ["createdAt", "创建时间"],
              ["expiresAt", "付款截止"],
            ]}
            fields={fields}
            detailPath={(id) => `/admin/orders/${id}`}
          />
        </Space>
      )}
    </OperationsWorkspace>
  );
}
