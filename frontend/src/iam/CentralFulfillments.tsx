import { Card, Space } from "antd";
import {
  OperationsWorkspace,
  OperationsList,
  OperationAction,
  type OperationsProps,
} from "./OperationsWorkspace";
export default function CentralFulfillments(props: OperationsProps) {
  return (
    <OperationsWorkspace
      {...props}
      family="fulfillments"
      title="发货与送达"
      description="每次动作按订单的真实门店单独判权，保留售后阻拦和原履约事实。"
    >
      {(client, dirty) => (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Card title="履约操作">
            <Space wrap>
              <OperationAction
                client={client}
                dirty={dirty}
                family="fulfillments"
                action="ship"
                label="登记发货"
                fields={[
                  { name: "orderId", label: "订单编号", kind: "id" },
                  { name: "trackingNo", label: "运单编号", kind: "text" },
                ]}
                path={(v) => `/admin/fulfillments/${v.orderId}/ship`}
                body={(v) => ({ trackingNo: v.trackingNo })}
                note="服务端核对原仓配事实与售后阻拦；原键重试保持同一订单和运单。"
              />
              <OperationAction
                client={client}
                dirty={dirty}
                family="fulfillments"
                action="deliver"
                label="确认送达"
                fields={[{ name: "orderId", label: "原订单编号", kind: "id" }]}
                path={(v) => `/admin/fulfillments/${v.orderId}/deliver`}
              />
            </Space>
          </Card>
          <OperationsList
            path="/admin/fulfillments"
            idField="orderId"
            columns={[
              ["orderId", "订单"],
              ["status", "履约状态"],
              ["trackingNo", "运单"],
              ["blocked", "售后阻拦"],
            ]}
            fields={[
              ["orderId", "订单编号"],
              ["status", "履约状态"],
              ["trackingNo", "运单编号"],
              ["provider", "仓配渠道"],
              ["blocked", "售后阻拦"],
              ["version", "数据版本"],
            ]}
          />
        </Space>
      )}
    </OperationsWorkspace>
  );
}
