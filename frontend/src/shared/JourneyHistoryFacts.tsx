import { Descriptions, Table } from "antd";
import type { Journey, JourneyInstance } from "./contracts";
import { Status, time } from "./ui";

export type JourneyHistory = {
  instance: JourneyInstance;
  definition: Journey;
  traceCoverage: string;
  steps: HistoryStep[];
};
type HistoryStep = {
  transitionVersion: number;
  ordinal: number;
  nodeId: string;
  kind: string;
  status: string;
  startedAt: string;
  completedAt: string | null;
  outcome: string | null;
};
const historyCoverage: Record<string, string> = {
  COMPLETE: "完整记录",
  LEGACY_PARTIAL: "历史记录部分覆盖",
};
const historyOutcomes: Record<string, string> = {
  BENEFIT_ACCEPTED: "权益请求已受理",
  COUPON_GRANTED: "优惠券已发放",
  NOTIFIED: "站内触达已写入",
  NOTIFY_SUPPRESSED: "站内触达已抑制",
  WAIT_SCHEDULED: "已安排等待",
  DECIDED: "分支已判定",
  RULE_UNKNOWN: "规则结果未知",
  FINISHED: "旅程已结束",
  NODE_DEFERRED: "已延后重试",
  NODE_FAILED: "节点执行失败",
};

export function JourneyHistoryFacts({ history }: { history: JourneyHistory }) {
  return (
    <>
      <Descriptions
        column={1}
        items={[
          {
            key: "instance",
            label: "实例编号",
            children: history.instance.instanceId,
          },
          {
            key: "version",
            label: "固定内容版本",
            children: history.definition.version,
          },
          {
            key: "coverage",
            label: "历史覆盖",
            children: historyCoverage[history.traceCoverage] ?? "覆盖范围未知",
          },
        ]}
      />
      <Table<HistoryStep>
        rowKey="transitionVersion"
        size="small"
        pagination={false}
        scroll={{ x: 660 }}
        dataSource={history.steps}
        locale={{ emptyText: "本批没有已记录的步骤" }}
        columns={[
          { title: "节点", dataIndex: "nodeId", width: 120 },
          {
            title: "步骤状态",
            dataIndex: "status",
            width: 106,
            render: (value: string) => <Status value={value} />,
          },
          {
            title: "结果",
            dataIndex: "outcome",
            render: (value: string | null) =>
              value ? (historyOutcomes[value] ?? value) : "未提供",
          },
          {
            title: "开始时间",
            dataIndex: "startedAt",
            width: 165,
            render: time,
          },
          {
            title: "步骤版本",
            dataIndex: "transitionVersion",
            width: 85,
          },
        ]}
      />
    </>
  );
}
