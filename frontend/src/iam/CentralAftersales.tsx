import { Card, Space } from "antd";
import {
  OperationsWorkspace,
  OperationsList,
  OperationAction,
  type OperationsProps,
} from "./OperationsWorkspace";
export default function CentralAftersales(props: OperationsProps) {
  return (
    <OperationsWorkspace
      {...props}
      family="aftersales"
      title="售后处理"
      description="批准、驳回、退货收货各自核验；退款金额来自原订单快照。"
    >
      {(client, dirty) => (
        <Space orientation="vertical" style={{ width: "100%" }}>
          <Card title="售后动作">
            <Space wrap>
              {(
                [
                  ["approve", "批准申请"],
                  ["reject", "驳回申请"],
                  ["receive-return", "确认退货收货"],
                ] as const
              ).map(([action, label]) => (
                <OperationAction
                  key={action}
                  client={client}
                  dirty={dirty}
                  family="aftersales"
                  action={action}
                  label={label}
                  fields={[
                    { name: "caseId", label: "原售后申请编号", kind: "id" },
                  ]}
                  path={(v) => `/admin/aftersales/${v.caseId}/${action}`}
                  note={
                    action === "approve"
                      ? "已发货申请先等待退货；批准不等于渠道退款已经成功。"
                      : action === "receive-return"
                        ? "核对可信收货事实后推进原退款责任，不重新计算申请金额。"
                        : "驳回只解除原售后阻拦，不覆盖订单生命周期。"
                  }
                />
              ))}
            </Space>
          </Card>
          <OperationsList
            path="/admin/aftersales"
            idField="caseId"
            columns={[
              ["caseId", "申请"],
              ["orderId", "订单"],
              ["status", "状态"],
              ["refundAmount", "退款金额"],
            ]}
            fields={[
              ["caseId", "售后编号"],
              ["orderId", "订单编号"],
              ["memberId", "会员编号"],
              ["status", "售后状态"],
              ["returnRequired", "需退货"],
              ["refundAmount", "退款金额"],
              ["refundId", "退款编号"],
              ["version", "数据版本"],
            ]}
          />
        </Space>
      )}
    </OperationsWorkspace>
  );
}
