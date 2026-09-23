import {
  Button,
  Card,
  Drawer,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Table,
} from "antd";
import { useState } from "react";
import type { Governed, Journey, JourneyNode } from "../shared/contracts";
import { useCommand, useResource } from "../shared/api";
import {
  Detail,
  ErrorNotice,
  Fields,
  PageHead,
  Status,
  initialDate,
  instant,
  localDateTime,
} from "../shared/ui";
import { Governance, RuleEditor } from "../shared/marketing";
const kinds = [
  { value: "WAIT", label: "等待" },
  { value: "DECIDE", label: "规则分支" },
  { value: "GRANT", label: "授予权益" },
  { value: "NOTIFY", label: "站内触达" },
  { value: "END", label: "结束" },
];
function NodesEditor({
  value = [],
  onChange,
}: {
  value?: JourneyNode[];
  onChange?: (v: JourneyNode[]) => void;
}) {
  const update = (index: number, patch: Partial<JourneyNode>) =>
    onChange?.(value.map((n, i) => (i === index ? { ...n, ...patch } : n)));
  const choices = value.map((n) => ({ value: n.id, label: n.id }));
  return (
    <>
      {value.map((n, i) => (
        <Card className="node-card" size="small" key={i}>
          <div className="node-header">
            <Space>
              <b>节点 {i + 1}</b>
              <Input
                aria-label={"节点" + (i + 1) + "标识"}
                value={n.id}
                onChange={(e) => update(i, { id: e.target.value })}
                style={{ width: 130 }}
              />
              <Select
                aria-label={"节点" + (i + 1) + "类型"}
                value={n.kind}
                options={kinds}
                style={{ width: 130 }}
                onChange={(kind) =>
                  onChange?.(
                    value.map((node, j) =>
                      i === j
                        ? {
                            id: node.id,
                            kind,
                            ...(kind === "WAIT" ? { seconds: 60 } : {}),
                            ...(kind === "DECIDE"
                              ? {
                                  rule: {
                                    kind: "COMPARE",
                                    field: "memberLevel",
                                    operator: "EQ",
                                    valueType: "TEXT",
                                    value: "",
                                  },
                                }
                              : {}),
                          }
                        : node,
                    ),
                  )
                }
              />
            </Space>
            <Button
              danger
              size="small"
              onClick={() => onChange?.(value.filter((_, j) => i !== j))}
            >
              移除
            </Button>
          </div>
          <div className="node-grid">
            {n.kind === "WAIT" && (
              <label>
                等待秒数
                <InputNumber
                  min={1}
                  max={604800}
                  value={n.seconds}
                  onChange={(v) => update(i, { seconds: v ?? 1 })}
                />
              </label>
            )}
            {n.kind !== "END" && n.kind !== "DECIDE" && (
              <label>
                下一节点
                <Select
                  aria-label={"节点" + (i + 1) + "下一节点"}
                  style={{ width: "100%" }}
                  value={n.next}
                  options={choices}
                  onChange={(next) => update(i, { next })}
                />
              </label>
            )}
            {n.kind === "GRANT" && (
              <>
                <label>
                  权益标识
                  <Input
                    value={n.benefit?.benefitId}
                    onChange={(e) =>
                      update(i, {
                        benefit: {
                          benefitId: e.target.value,
                          version: n.benefit?.version ?? 1,
                        },
                      })
                    }
                  />
                </label>
                <label>
                  权益版本
                  <InputNumber
                    min={1}
                    value={n.benefit?.version ?? 1}
                    onChange={(v) =>
                      update(i, {
                        benefit: {
                          benefitId: n.benefit?.benefitId ?? "",
                          version: v ?? 1,
                        },
                      })
                    }
                  />
                </label>
              </>
            )}
            {n.kind === "NOTIFY" && (
              <>
                <label>
                  消息标题
                  <Input
                    value={n.title}
                    maxLength={128}
                    onChange={(e) => update(i, { title: e.target.value })}
                  />
                </label>
                <label>
                  消息内容
                  <Input.TextArea
                    value={n.body}
                    maxLength={1000}
                    onChange={(e) => update(i, { body: e.target.value })}
                  />
                </label>
              </>
            )}
          </div>
          {n.kind === "DECIDE" && (
            <>
              <RuleEditor
                value={n.rule}
                onChange={(rule) => update(i, { rule })}
              />
              <Space>
                <span>满足时</span>
                <Select
                  style={{ width: 150 }}
                  value={n.yesNext}
                  options={choices}
                  onChange={(yesNext) => update(i, { yesNext })}
                />
                <span>不满足时</span>
                <Select
                  style={{ width: 150 }}
                  value={n.noNext}
                  options={choices}
                  onChange={(noNext) => update(i, { noNext })}
                />
              </Space>
              <p className="muted">事实未知会终止实例，不进入任一发奖分支。</p>
            </>
          )}
        </Card>
      ))}
      <Button
        disabled={value.length >= 32}
        onClick={() =>
          onChange?.([
            ...value,
            { id: "node-" + (value.length + 1), kind: "END" },
          ])
        }
      >
        添加节点
      </Button>
    </>
  );
}
export function Journeys({ store }: { store: string }) {
  const resource = useResource<Governed<Journey>[]>("/admin/journeys");
  const [open, setOpen] = useState(false);
  const [detail, setDetail] = useState<Governed<Journey>>();
  const [draft, setDraft] = useState<Journey>();
  const command = useCommand();
  return (
    <>
      <PageHead
        title="营销旅程"
        description="以支付事实或手工入组启动，逐节点可靠执行。"
        extra={
          <>
            <Button onClick={resource.refresh}>刷新</Button>
            <Button
              type="primary"
              disabled={!store}
              onClick={() => {
                setDraft(undefined);
                setOpen(true);
              }}
            >
              创建旅程
            </Button>
          </>
        }
      />
      <ErrorNotice error={resource.error} />
      <Card>
        <Table<Governed<Journey>>
          rowKey={(r) => r.content.journeyId}
          dataSource={resource.data}
          loading={resource.loading}
          pagination={false}
          columns={[
            { title: "旅程", render: (_, r) => r.content.name },
            {
              title: "触发方式",
              render: (_, r) =>
                r.content.trigger === "ORDER_PAID" ? "支付后" : "手工入组",
            },
            { title: "节点数", render: (_, r) => r.content.nodes.length },
            { title: "版本", render: (_, r) => r.content.version },
            {
              title: "状态",
              dataIndex: "status",
              render: (v: string) => <Status value={v} />,
            },
            {
              title: "操作",
              render: (_, r) => (
                <Space wrap>
                  <Button size="small" onClick={() => setDetail(r)}>
                    查看节点
                  </Button>
                  <Button
                    size="small"
                    onClick={() => {
                      setDraft({
                        ...r.content,
                        version: r.content.version + 1,
                        validFrom: localDateTime(r.content.validFrom),
                        validTo: localDateTime(r.content.validTo),
                      });
                      setOpen(true);
                    }}
                  >
                    创建新版本
                  </Button>
                  <Governance
                    base="/admin/journeys"
                    id={r.content.journeyId}
                    version={r.content.version}
                    status={r.status}
                    lockVersion={r.lockVersion}
                    onDone={resource.refresh}
                  />
                </Space>
              ),
            },
          ]}
        />
      </Card>
      <Drawer
        title="旅程节点与版本"
        open={!!detail}
        onClose={() => setDetail(undefined)}
        width={720}
      >
        {detail?.content.nodes.map((n) => (
          <Card
            key={n.id}
            title={n.id + " · " + kinds.find((k) => k.value === n.kind)?.label}
            size="small"
            className="node-card"
          >
            <Detail value={n} />
          </Card>
        ))}
      </Drawer>
      <Drawer
        title="旅程编辑器"
        size={860}
        open={open}
        onClose={() => {
          if (!command.busy) setOpen(false);
        }}
        destroyOnHidden
      >
        <ErrorNotice error={command.error} />
        <Form
          layout="vertical"
          initialValues={
            draft ?? {
              version: 1,
              trigger: "MANUAL",
              validFrom: initialDate(-60),
              validTo: initialDate(3600),
              maxDurationSeconds: 3600,
              nodes: [{ id: "end", kind: "END" }],
            }
          }
          onFinish={async (v) => {
            const input = {
              ...v,
              storeId: store,
              validFrom: instant(v.validFrom),
              validTo: instant(v.validTo),
              entry: v.nodes[0]?.id,
            };
            if ((await command.run("/admin/journeys", input)) !== undefined) {
              setOpen(false);
              resource.refresh();
            }
          }}
        >
          <div className="node-grid">
            <Fields
              fields={[
                { name: "journeyId", label: "旅程标识" },
                { name: "version", label: "版本", type: "number", min: 1 },
                { name: "name", label: "旅程名称" },
                {
                  name: "trigger",
                  label: "触发方式",
                  type: "select",
                  options: [
                    { value: "MANUAL", label: "手工入组" },
                    { value: "ORDER_PAID", label: "支付事实" },
                  ],
                },
                { name: "validFrom", label: "入组开始时间", type: "datetime" },
                { name: "validTo", label: "入组结束时间", type: "datetime" },
                {
                  name: "maxDurationSeconds",
                  label: "实例最长执行秒数",
                  type: "number",
                  min: 1,
                  max: 2592000,
                },
              ]}
            />
          </div>
          <p className="muted">
            首个节点为入口。所有节点必须可达，分支最终到达结束节点；权益有效期需覆盖整个执行期限。
          </p>
          <Form.Item name="nodes" label="节点与流转">
            <NodesEditor />
          </Form.Item>
          <Button type="primary" htmlType="submit" loading={command.busy}>
            保存旅程草稿
          </Button>
        </Form>
      </Drawer>
    </>
  );
}
