import { Card, Space } from "antd";
import {
  OperationsWorkspace,
  OperationsList,
  OperationAction,
  type OperationsProps,
} from "./OperationsWorkspace";
export default function CentralRefunds(props: OperationsProps) {
  return (
    <OperationsWorkspace
      {...props}
      family="refunds"
      title="退款核对"
      description="只读取原可信退款渠道，员工离职不解除已经受理的原资金责任。"
    >
      {(client, dirty) => (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Card title="核对原退款">
            <OperationAction
              client={client}
              dirty={dirty}
              family="refunds"
              action="reconcile"
              label="核对退款"
              keyed={false}
              fields={[{ name: "refundId", label: "原退款编号", kind: "id" }]}
              path={(v) => `/admin/refunds/${v.refundId}/reconcile`}
              note="金额与收款对象不可编辑。UNKNOWN表示渠道结果未确认；显式重试只核对同一笔退款。"
            />
          </Card>
          <OperationsList
            path="/admin/refunds"
            idField="refundId"
            columns={[
              ["refundId", "退款"],
              ["orderId", "订单"],
              ["status", "渠道状态"],
              ["amount", "退款金额"],
            ]}
            fields={[
              ["refundId", "退款编号"],
              ["caseId", "售后编号"],
              ["orderId", "订单编号"],
              ["amount", "退款金额"],
              ["currency", "币种"],
              ["provider", "原渠道"],
              ["status", "渠道状态"],
              ["version", "数据版本"],
            ]}
          />
        </Space>
      )}
    </OperationsWorkspace>
  );
}
