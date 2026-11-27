import {
  Button,
  DatePicker,
  Descriptions,
  Form,
  Input,
  Select,
  Space,
  Spin,
  Table,
  Tag,
} from "antd";
import { useState } from "react";
import type { Dayjs } from "dayjs";
import { useResource } from "../shared/api";
import { RecordModal } from "../shared/interactions";
import { ErrorNotice, PageHead, Workbench, time } from "../shared/ui";

const path = "/platform/operation-audits";
const actions: Record<string, string> = {
  "platform.category.disable": "停用类目",
  "platform.category.change": "变更类目",
  "platform.audit.list": "查询操作日志",
  "platform.audit.read": "查看审计详情",
};
const results: Record<string, [string, string]> = {
  SUCCEEDED: ["成功", "green"],
  REPLAYED: ["重复提交", "blue"],
  CONFLICT: ["版本或请求冲突", "orange"],
  DENIED: ["权限拒绝", "red"],
  REJECTED: ["校验拒绝", "orange"],
  FAILED: ["失败", "red"],
  UNKNOWN: ["待核实", "default"],
};
const fields: Record<string, string> = {
  name: "名称",
  sortOrder: "排序",
  enabled: "启停状态",
};

type Change = {
  field: string;
  before: string | number | boolean | null;
  after: Change["before"];
};
type Audit = {
  id: string;
  requestId: string;
  traceId?: string;
  action: string;
  actorId: string;
  resourceId?: string;
  occurredAt: string;
  result: string;
  errorCode?: string;
  effectId?: string;
  originalAttemptId?: string;
  beforeVersion?: number;
  afterVersion?: number;
  reason?: string;
  changes: Change[];
};
type Page = { items: Audit[]; nextCursor?: string };
type Filter = {
  from?: Dayjs;
  to?: Dayjs;
  actorId?: string;
  resourceId?: string;
  action?: string;
  result?: string;
};

function resultTag(result: string) {
  const [name, color] = results[result] ?? [result, "default"];
  return <Tag color={color}>{name}</Tag>;
}

function value(raw: Change["before"]) {
  if (typeof raw === "boolean") return raw ? "启用" : "停用";
  return raw ?? "—";
}

function query(filter: Filter, cursor: string) {
  const params = new URLSearchParams();
  const { from, to, ...criteria } = filter;
  if (from) params.set("from", from.toISOString());
  if (to) params.set("to", to.toISOString());
  for (const [name, raw] of Object.entries(criteria)) {
    const value = raw?.trim();
    if (value) params.set(name, value);
  }
  if (cursor) params.set("cursor", cursor);
  return params.toString();
}

/** 授权审计列表，按服务端游标分页并使用真实详情接口。 */
export function OperationAudits({
  openCategory,
}: {
  openCategory: (code: string) => void;
}) {
  const [form] = Form.useForm<Filter>();
  const [filter, setFilter] = useState<Filter>({});
  const [cursors, setCursors] = useState([""]);
  const [selected, setSelected] = useState<string>();
  const list = useResource<Page>(
    path + "?" + query(filter, cursors[cursors.length - 1]),
  );
  const detail = useResource<Audit>(selected ? path + "/" + selected : null);
  const row = detail.data?.id === selected ? detail.data : undefined;

  return (
    <Workbench>
      <PageHead
        eyebrow="平台工具"
        title="操作日志"
        description="默认查询最近 7 天。可输入对象编号定位业务操作。"
      />
      <Form<Filter>
        form={form}
        layout="inline"
        style={{ gap: "12px 0", marginBottom: 16 }}
        onFinish={(values) => {
          setCursors([""]);
          setFilter(values);
          list.refresh();
        }}
      >
        <Form.Item name="from" label="开始时间">
          <DatePicker
            showTime
            format="YYYY-MM-DD HH:mm"
            placeholder="选择开始时间"
          />
        </Form.Item>
        <Form.Item name="to" label="结束时间">
          <DatePicker
            showTime
            format="YYYY-MM-DD HH:mm"
            placeholder="选择结束时间"
          />
        </Form.Item>
        <Form.Item name="actorId" label="操作者">
          <Input maxLength={64} allowClear />
        </Form.Item>
        <Form.Item name="resourceId" label="对象编号">
          <Input maxLength={96} allowClear />
        </Form.Item>
        <Form.Item name="action" label="动作">
          <Select
            allowClear
            placeholder="全部业务动作"
            style={{ minWidth: 140 }}
            options={Object.entries(actions).map(([value, label]) => ({
              value,
              label,
            }))}
          />
        </Form.Item>
        <Form.Item name="result" label="结果">
          <Select
            allowClear
            placeholder="全部结果"
            style={{ minWidth: 120 }}
            options={Object.entries(results).map(([value, [label]]) => ({
              value,
              label,
            }))}
          />
        </Form.Item>
        <Form.Item>
          <Space wrap>
            <Button type="primary" htmlType="submit">
              查询
            </Button>
            <Button
              onClick={() => {
                form.resetFields();
                setFilter({});
                setCursors([""]);
                list.refresh();
              }}
            >
              重置筛选
            </Button>
          </Space>
        </Form.Item>
      </Form>
      <ErrorNotice error={list.error} onRetry={list.refresh} />
      {list.error === undefined && (
        <Table<Audit>
          rowKey="id"
          dataSource={list.data?.items ?? []}
          loading={list.loading}
          pagination={false}
          locale={{ emptyText: "当前范围没有操作日志" }}
          columns={[
            {
              title: "时间",
              dataIndex: "occurredAt",
              render: time,
              responsive: ["md"],
            },
            {
              title: "动作",
              render: (_, item) => (
                <div style={{ overflowWrap: "anywhere" }}>
                  {actions[item.action] ?? item.action}
                  <br />
                  {item.resourceId ?? "—"}
                </div>
              ),
            },
            {
              title: "操作者",
              dataIndex: "actorId",
              ellipsis: true,
              responsive: ["sm"],
            },
            { title: "结果", dataIndex: "result", render: resultTag },
            {
              title: "操作",
              render: (_, item) => (
                <Button type="link" onClick={() => setSelected(item.id)}>
                  查看详情
                </Button>
              ),
            },
          ]}
        />
      )}
      {(cursors.length > 1 || list.data?.nextCursor) && (
        <Space style={{ marginTop: 16 }}>
          <Button
            disabled={cursors.length === 1 || list.loading}
            onClick={() => setCursors(cursors.slice(0, -1))}
          >
            上一页
          </Button>
          <Button
            disabled={!list.data?.nextCursor || list.loading}
            onClick={() => {
              const next = list.data?.nextCursor;
              if (next) setCursors([...cursors, next]);
            }}
          >
            下一页
          </Button>
        </Space>
      )}
      <RecordModal
        title="操作日志详情"
        open={!!selected}
        onCancel={() => setSelected(undefined)}
        footer={null}
        width={720}
      >
        <ErrorNotice error={detail.error} onRetry={detail.refresh} />
        {detail.loading && <Spin />}
        {row && (
          <Space orientation="vertical" size="middle" style={{ width: "100%" }}>
            <Descriptions column={{ xs: 1, sm: 2 }} size="small">
              <Descriptions.Item label="时间">
                {time(row.occurredAt)}
              </Descriptions.Item>
              <Descriptions.Item label="结果">
                {resultTag(row.result)}
              </Descriptions.Item>
              <Descriptions.Item label="动作">
                {actions[row.action] ?? row.action}
              </Descriptions.Item>
              <Descriptions.Item label="操作者">
                {row.actorId}
              </Descriptions.Item>
              <Descriptions.Item label="对象编号">
                {row.resourceId ?? "—"}
              </Descriptions.Item>
              {row.effectId && (
                <Descriptions.Item label="提交证据">
                  {row.effectId}
                </Descriptions.Item>
              )}
              {row.beforeVersion != null && (
                <Descriptions.Item label="版本">
                  {row.beforeVersion} → {row.afterVersion}
                </Descriptions.Item>
              )}
              {row.errorCode && (
                <Descriptions.Item label="错误码">
                  {row.errorCode}
                </Descriptions.Item>
              )}
              {row.reason && (
                <Descriptions.Item label="变更原因" span={2}>
                  {row.reason}
                </Descriptions.Item>
              )}
              <Descriptions.Item label="请求编号" span={2}>
                {row.requestId}
              </Descriptions.Item>
              {row.traceId && (
                <Descriptions.Item label="追踪标识" span={2}>
                  {row.traceId}
                </Descriptions.Item>
              )}
            </Descriptions>
            {row.changes.length > 0 && (
              <Table<Change>
                rowKey="field"
                size="small"
                pagination={false}
                dataSource={row.changes}
                columns={[
                  {
                    title: "变更字段",
                    dataIndex: "field",
                    render: (field: string) => fields[field] ?? field,
                  },
                  { title: "变更前", dataIndex: "before", render: value },
                  { title: "变更后", dataIndex: "after", render: value },
                ]}
              />
            )}
            <Space wrap>
              {row.originalAttemptId && (
                <Button onClick={() => setSelected(row.originalAttemptId)}>
                  打开原提交
                </Button>
              )}
              {row.action.startsWith("platform.category.") &&
                row.resourceId && (
                  <Button
                    onClick={() => {
                      if (row.resourceId) openCategory(row.resourceId);
                    }}
                  >
                    查看类目
                  </Button>
                )}
            </Space>
          </Space>
        )}
      </RecordModal>
    </Workbench>
  );
}
