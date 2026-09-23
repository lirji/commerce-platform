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
import { JourneyScans } from "./JourneyScans";
import { Governance, RuleEditor } from "../shared/marketing";
const kinds = [
  { value: "WAIT", label: "等待" },
  { value: "DECIDE", label: "规则分支" },
  { value: "GRANT", label: "授予权益" },
  { value: "COUPON", label: "发放受控券" },
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
            {n.kind === "COUPON" && <><label>券定义标识<Input aria-label={"节点"+(i+1)+"券标识"} value={n.coupon?.definitionId} onChange={e=>update(i,{coupon:{definitionId:e.target.value,version:n.coupon?.version??1}})}/></label><label>券版本<InputNumber min={1} value={n.coupon?.version??1} onChange={v=>update(i,{coupon:{definitionId:n.coupon?.definitionId??"",version:v??1}})}/></label></>}
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
const lifecycleTriggers=["BIRTHDAY","DORMANT","REPURCHASE","CART_ABANDONED"];
const triggerLabels:Record<Journey["trigger"],string>={MANUAL:"手工入组",ORDER_PAID:"支付后",MEMBER_REGISTERED:"会员注册",LEVEL_CHANGED:"等级变化",SEGMENT_ENTERED:"人群入组",BIRTHDAY:"生日关怀",DORMANT:"沉睡唤醒",REPURCHASE:"复购提醒",CART_ABANDONED:"加购挽回"};
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
        description="以会员、动态人群、支付事实或手工入组启动，带频控逐节点执行。"
        extra={
          <>
            <Select<string> aria-label="生命周期模板" placeholder="从生命周期模板创建" style={{width:210}} value={undefined} disabled={!store} options={lifecycleTriggers.map(value=>({value,label:triggerLabels[value as Journey["trigger"]]}))} onChange={trigger=>{setDraft({journeyId:"",version:1,storeId:store,name:triggerLabels[trigger as Journey["trigger"]],trigger:trigger as Journey["trigger"],validFrom:initialDate(0),validTo:initialDate(86400*30),maxDurationSeconds:86400,entry:"notify",nodes:[{id:"notify",kind:"NOTIFY",next:"end",title:"会员专属关怀",body:"感谢你的关注，欢迎查看本期会员活动。"},{id:"end",kind:"END"}],controls:{maxEntries:1,entryWindowSeconds:86400,notificationLimit:1,notificationWindowSeconds:86400},lifecycle:{thresholdDays:30,cartDelaySeconds:3600,scanIntervalSeconds:3600,conversionWindowDays:7}});setOpen(true);}}/>
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
                triggerLabels[r.content.trigger],
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
      <JourneyScans/>
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
          initialValues={{
            ...(draft ?? {
              version: 1,
              trigger: "MANUAL",
              validFrom: initialDate(-60),
              validTo: initialDate(3600),
              maxDurationSeconds: 3600,
              nodes: [{ id: "end", kind: "END" }],
            }),
            thresholdDays:draft?.lifecycle?.thresholdDays??30,cartDelaySeconds:draft?.lifecycle?.cartDelaySeconds??3600,scanIntervalSeconds:draft?.lifecycle?.scanIntervalSeconds??3600,conversionWindowDays:draft?.lifecycle?.conversionWindowDays??7,
            frequency: draft ? !!draft.controls : true,
            segmentId:draft?.controls?.segmentId??"",entryRule:draft?.controls?.entryRule,
            maxEntries:draft?.controls?.maxEntries??1,entryWindowSeconds:draft?.controls?.entryWindowSeconds??86400,
            notificationLimit:draft?.controls?.notificationLimit??3,notificationWindowSeconds:draft?.controls?.notificationWindowSeconds??86400,
          }}
          onFinish={async (v) => {
            const {frequency,segmentId,entryRule,maxEntries,entryWindowSeconds,notificationLimit,notificationWindowSeconds,thresholdDays,cartDelaySeconds,scanIntervalSeconds,conversionWindowDays,lifecycle:storedLifecycle,...definition}=v;
            void storedLifecycle;
            const input = {
              ...definition,
              lifecycle:lifecycleTriggers.includes(v.trigger)?{thresholdDays,cartDelaySeconds,scanIntervalSeconds,conversionWindowDays}:undefined,
              controls:frequency||!["MANUAL","ORDER_PAID"].includes(v.trigger)?{segmentId:v.trigger==="SEGMENT_ENTERED"?segmentId:undefined,entryRule,maxEntries,entryWindowSeconds,notificationLimit,notificationWindowSeconds}:null,
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
                    { value: "MEMBER_REGISTERED", label: "会员注册" },
                    { value: "LEVEL_CHANGED", label: "会员等级变化" },
                    { value: "SEGMENT_ENTERED", label: "动态人群新入组" },
                    ...lifecycleTriggers.map(value=>({value,label:triggerLabels[value as Journey["trigger"]]})),
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
          <Form.Item noStyle shouldUpdate={(a,b)=>a.trigger!==b.trigger}>{({getFieldValue})=>lifecycleTriggers.includes(getFieldValue("trigger"))?<><div className="form-section-title">生命周期条件</div><Fields fields={[
            {name:"thresholdDays",label:"沉睡 / 复购间隔天数",type:"number",min:1,max:365},
            {name:"cartDelaySeconds",label:"加购后等待秒数",type:"number",min:60,max:604800},
            {name:"scanIntervalSeconds",label:"会员检查间隔秒数",type:"number",min:300,max:86400},
            {name:"conversionWindowDays",label:"成交观察天数",type:"number",min:1,max:30}
          ]}/><p className="muted">生日使用会员资料月日；沉睡无成交会员按入会天数判断；复购须有历史净消费。加购与订单按当前门店核对。</p></>:null}</Form.Item>
          <div className="form-section-title">入组资格与频控</div>
          <Fields fields={[
            {name:"frequency",label:"启用频控（会员事件触发必须启用）",type:"switch",required:false},
            {name:"segmentId",label:"绑定动态人群标识（仅人群入组触发填写）",required:false},
            {name:"maxEntries",label:"窗口内最大入组次数",type:"number",min:1,max:100},
            {name:"entryWindowSeconds",label:"入组窗口秒数（UTC固定时间桶）",type:"number",min:60,max:2592000},
            {name:"notificationLimit",label:"窗口内最多通知次数",type:"number",min:1,max:100},
            {name:"notificationWindowSeconds",label:"通知窗口秒数",type:"number",min:60,max:2592000}
          ]}/>
          <Form.Item name="entryRule" label="入组会员规则（可选）"><RuleEditor memberOnly/></Form.Item>
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
