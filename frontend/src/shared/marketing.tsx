import {
  Button,
  Card,
  Drawer,
  Form,
  Input,
  Modal,
  Select,
  Space,
  Table,
} from "antd";
import { useState } from "react";
import type { Campaign, Governed, Rule } from "./contracts";
import { encode, useCommand, useResource } from "./api";
import {
  ActionButton,
  Detail,
  ErrorNotice,
  Fields,
  PageHead,
  Status,
  initialDate,
  instant,
  money,
  localDateTime,
  type Values,
} from "./ui";
/** 受限可视规则树只生成契约允许的节点，不执行字符串表达式。 */
export function RuleEditor({
  value,
  onChange,
  depth = 0,
  memberOnly = false,
}: {
  value?: Rule;
  onChange?: (value: Rule) => void;
  depth?: number;
  memberOnly?: boolean;
}) {
  const rule = value ?? {
    kind: "COMPARE",
    field: "memberLevel",
    operator: "EQ",
    valueType: "TEXT",
    value: "",
  };
  const change = (patch: Partial<Rule>) => onChange?.({ ...rule, ...patch });
  return (
    <div className="rule-node">
      <Space wrap>
        <Select
          aria-label="规则类型"
          value={rule.kind}
          style={{ width: 130 }}
          options={["COMPARE", "ALL", "ANY", "NOT"]
            .filter((k) => depth < 6 || k === "COMPARE")
            .map((k) => ({
              value: k,
              label: {
                COMPARE: "条件比较",
                ALL: "全部满足",
                ANY: "任一满足",
                NOT: "取反",
              }[k],
            }))}
          onChange={(kind) =>
            onChange?.(
              kind === "COMPARE"
                ? {
                    kind,
                    field: "memberLevel",
                    operator: "EQ",
                    valueType: "TEXT",
                    value: "",
                  }
                : {
                    kind,
                    children: [
                      {
                        kind: "COMPARE",
                        field: "memberLevel",
                        operator: "EQ",
                        valueType: "TEXT",
                        value: "",
                      },
                    ],
                  },
            )
          }
        />
        {rule.kind === "COMPARE" && (
          <>
            <Select
              aria-label="规则字段"
              value={rule.field}
              style={{ width: 130 }}
              options={[
                { value: "memberLevel", label: "会员等级" },
                { value: "orderAmount", label: "订单金额" },
                { value: "memberGrowth", label: "会员成长" },
                { value: "memberNetSpend", label: "完成净消费" },
                { value: "memberTags", label: "会员标签" },
                { value: "memberStatus", label: "会员状态" },
              ].filter(f=>!memberOnly||f.value!=="orderAmount")}
              onChange={(field) =>
                change({
                  field,
                  valueType: ["orderAmount","memberGrowth","memberNetSpend"].includes(field) ? "DECIMAL" : "TEXT",
                  operator: field === "memberTags" ? "CONTAINS" : ["orderAmount","memberGrowth","memberNetSpend"].includes(field) ? "GTE" : "EQ",
                })
              }
            />
            <Select
              aria-label="比较方式"
              value={rule.operator}
              style={{ width: 110 }}
              options={(rule.field === "memberTags" ? ["CONTAINS"] : ["memberLevel","memberStatus"].includes(rule.field??"")
                ? ["EQ"]
                : ["EQ", "GT", "GTE", "LT", "LTE"]
              ).map((x) => ({
                value: x,
                label: {
                  CONTAINS: "包含标签",
                  EQ: "等于",
                  GT: "大于",
                  GTE: "大于等于",
                  LT: "小于",
                  LTE: "小于等于",
                }[x],
              }))}
              onChange={(operator) => change({ operator })}
            />
            <Input
              aria-label="比较值"
              value={rule.value}
              placeholder="输入条件值"
              style={{ width: 140 }}
              onChange={(e) => change({ value: e.target.value })}
            />
          </>
        )}
      </Space>
      {rule.kind !== "COMPARE" && (
        <>
          {(rule.children ?? []).map((child, i) => (
            <div key={i}>
              <RuleEditor
                value={child}
                depth={depth + 1}
                memberOnly={memberOnly}
                onChange={(next) =>
                  change({
                    children: rule.children!.map((r, j) =>
                      i === j ? next : r,
                    ),
                  })
                }
              />
              {rule.kind !== "NOT" && (rule.children?.length ?? 0) > 1 && (
                <Button
                  size="small"
                  onClick={() =>
                    change({
                      children: rule.children!.filter((_, j) => i !== j),
                    })
                  }
                >
                  移除此条件
                </Button>
              )}
            </div>
          ))}
          {rule.kind !== "NOT" && (rule.children?.length ?? 0) < 16 && (
            <Button
              size="small"
              onClick={() =>
                change({
                  children: [
                    ...(rule.children ?? []),
                    {
                      kind: "COMPARE",
                      field: "memberLevel",
                      operator: "EQ",
                      valueType: "TEXT",
                      value: "",
                    },
                  ],
                })
              }
            >
              添加条件
            </Button>
          )}
        </>
      )}
    </div>
  );
}
export function CampaignEditor({
  store,
  onDone,
  path = "/admin/campaigns",
  wrap = false,
  label = "新建活动",
  initialCampaign,
}: {
  store: string;
  onDone: () => void;
  path?: string;
  wrap?: boolean;
  label?: string;
  initialCampaign?: Campaign;
}) {
  const [open, setOpen] = useState(false);
  const command = useCommand();
  const [form] = Form.useForm();
  function build(v: Values): Campaign {
    const audience = String(v.audienceId ?? "").trim();
    const ruleRef = String(v.ruleId ?? "").trim();
    return {
      campaignId: String(v.campaignId),
      version: Number(v.version),
      storeId: store,
      name: String(v.name),
      validFrom: instant(v.validFrom),
      validTo: instant(v.validTo),
      minimumSpend: String(v.minimumSpend),
      discountAmount: String(v.discountAmount),
      rule: v.rule as Rule,
      ...(audience || ruleRef || v.advanced
        ? {
            policy: {
              ...(audience
                ? {
                    audience: {
                      id: audience,
                      version: Number(v.audienceVersion),
                    },
                  }
                : {}),
              ...(ruleRef
                ? { rule: { id: ruleRef, version: Number(v.ruleVersion) } }
                : {}),
              terms: {
                percentageBps: Number(v.percentageBps ?? 0),
                platformFundingBps: Number(v.platformFundingBps ?? 0),
                budget: v.budget ? String(v.budget) : null,
                ...(v.advanced ? {pricing:{
                  includedSkuIds:String(v.includedSkuIds??"").split(",").map(v=>v.trim()).filter(Boolean),
                  excludedSkuIds:String(v.excludedSkuIds??"").split(",").map(v=>v.trim()).filter(Boolean),
                  tiers:String(v.tiers??"").split("\n").filter(v=>v.trim()).map(line=>{
                    const [minimumSpend,discountAmount,bps,...extra]=line.split(",").map(v=>v.trim());
                    if(extra.length||!minimumSpend||!discountAmount||bps===undefined||!Number.isInteger(Number(bps)))throw new Error("阶梯每行填写 门槛,优惠上限,比例万分比");
                    return {minimumSpend,discountAmount,percentageBps:Number(bps)};
                  }),
                }}:{}),
                ...(v.benefitId
                  ? {
                      grant: {
                        benefitId: String(v.benefitId),
                        version: Number(v.benefitVersion),
                      },
                    }
                  : {}),
              },
            },
          }
        : {}),
    };
  }
  return (
    <>
      <Button
        type="primary"
        disabled={!store}
        onClick={() => {
          command.clear();
          if(initialCampaign){
            const c=initialCampaign;const terms=c.policy?.terms;
            form.setFieldsValue({...c,version:c.version+1,validFrom:localDateTime(c.validFrom),validTo:localDateTime(c.validTo),
              audienceId:c.policy?.audience?.id??"",audienceVersion:c.policy?.audience?.version??1,
              ruleId:c.policy?.rule?.id??"",ruleVersion:c.policy?.rule?.version??1,
              percentageBps:terms?.percentageBps??0,platformFundingBps:terms?.platformFundingBps??0,budget:terms?.budget??"",
              benefitId:terms?.grant?.benefitId??"",benefitVersion:terms?.grant?.version??1,
              advanced:!!terms?.pricing,includedSkuIds:terms?.pricing?.includedSkuIds.join(",")??"",
              excludedSkuIds:terms?.pricing?.excludedSkuIds.join(",")??"",
              tiers:terms?.pricing?.tiers.map(t=>`${t.minimumSpend},${t.discountAmount},${t.percentageBps}`).join("\n")??""});
          }
          setOpen(true);
        }}
      >
        {label}
      </Button>
      <Drawer
        size={760}
        title={label}
        open={open}
        onClose={() => {
          if (!command.busy) setOpen(false);
        }}
        destroyOnHidden
      >
        <ErrorNotice error={command.error} />
        <Form
          form={form}
          layout="vertical"
          initialValues={{
            version: 1,
            validFrom: initialDate(-60),
            validTo: initialDate(3600),
            minimumSpend: "0.00",
            percentageBps: 0,
            platformFundingBps: 0,
            audienceVersion: 1,
            ruleVersion: 1,
            benefitVersion: 1,
            rule: {
              kind: "COMPARE",
              field: "memberLevel",
              operator: "EQ",
              valueType: "TEXT",
              value: "",
            },
          }}
          onFinish={async (v) => {
            if (
              !v.audienceId &&
              !v.ruleId &&
              !v.advanced &&
              (v.budget ||
                v.benefitId ||
                v.percentageBps ||
                v.platformFundingBps)
            ) {
              form.setFields([
                {
                  name: "audienceId",
                  errors: ["预算、比例或权益配置需要引用人群或规则资产"],
                },
              ]);
              return;
            }
            let input: Campaign;
            try { input=build(v); } catch(e){form.setFields([{name:"tiers",errors:[e instanceof Error?e.message:"配置格式无效"]}]);return;}
            if (
              (await command.run(path, wrap ? { campaign: input } : input)) !==
              undefined
            ) {
              setOpen(false);
              form.resetFields();
              onDone();
            }
          }}
        >
          <div className="node-grid">
            <Fields
              fields={[
                { name: "campaignId", label: "活动标识" },
                { name: "version", label: "版本", type: "number", min: 1 },
                { name: "name", label: "活动名称" },
                { name: "minimumSpend", label: "消费门槛", type: "money" },
                {
                  name: "discountAmount",
                  label: "优惠金额 / 比例折扣上限",
                  type: "money",
                },
                { name: "validFrom", label: "开始时间", type: "datetime" },
                { name: "validTo", label: "结束时间", type: "datetime" },
              ]}
            />
          </div>
          <Form.Item label="资格规则" name="rule" rules={[{ required: true }]}>
            <RuleEditor />
          </Form.Item>
          <div className="form-section-title">精细促销（可选，需审批发布）</div>
          <Fields fields={[
            {name:"advanced",label:"启用商品范围与阶梯",type:"switch",required:false},
            {name:"includedSkuIds",label:"参与SKU（逗号分隔，留空为全部）",required:false},
            {name:"excludedSkuIds",label:"排除SKU（逗号分隔，排除优先）",required:false},
            {name:"tiers",label:"优惠阶梯",type:"textarea",required:false,help:"每行：门槛,优惠上限,优惠比例万分比。比例0表示固定减免，1000表示减10%；按门槛升序，最多8档。留空使用上方基础优惠。"}
          ]}/>
          <div className="form-section-title">可信资产与预算（可选）</div>
          <p className="muted">
            引用人群或规则资产后走审批发布流程。百分比、预算、资方与权益配置随该受治理版本一起生效。
          </p>
          <div className="node-grid">
            <Fields
              fields={[
                { name: "audienceId", label: "人群标识", required: false },
                {
                  name: "audienceVersion",
                  label: "人群版本",
                  type: "number",
                  min: 1,
                },
                { name: "ruleId", label: "规则资产标识", required: false },
                {
                  name: "ruleVersion",
                  label: "规则资产版本",
                  type: "number",
                  min: 1,
                },
                {
                  name: "percentageBps",
                  label: "优惠比例（万分比，0为固定金额）",
                  type: "number",
                  max: 10000,
                },
                {
                  name: "platformFundingBps",
                  label: "平台承担比例（万分比）",
                  type: "number",
                  max: 10000,
                },
                {
                  name: "budget",
                  label: "营销预算上限",
                  type: "money",
                  required: false,
                },
                {
                  name: "benefitId",
                  label: "支付后授予权益标识",
                  required: false,
                },
                {
                  name: "benefitVersion",
                  label: "权益版本",
                  type: "number",
                  min: 1,
                },
              ]}
            />
          </div>
          <Button type="primary" htmlType="submit" loading={command.busy}>
            保存活动草稿
          </Button>
        </Form>
      </Drawer>
    </>
  );
}
export function Governance({
  base,
  id,
  version,
  status,
  lockVersion,
  onDone,
  legacy = false,
}: {
  base: string;
  id: string;
  version: number;
  status: string;
  lockVersion: number;
  onDone: () => void;
  legacy?: boolean;
}) {
  const actions =
    status === "DRAFT"
      ? legacy
        ? ["publish"]
        : ["submit"]
      : status === "IN_REVIEW"
        ? ["approve", "reject"]
        : ["APPROVED", "PAUSED"].includes(status)
          ? ["publish"]
          : status === "PUBLISHED"
            ? ["pause"]
            : [];
  return (
    <Space wrap>
      {actions.map((action) => (
        <ActionButton
          key={action}
          label={
            {
              submit: "提交审批",
              approve: "批准",
              reject: "驳回",
              publish: "发布",
              pause: "暂停",
            }[action]!
          }
          path={base + "/" + encode(id) + "/" + version + "/" + action}
          body={{ expectedVersion: lockVersion }}
          onDone={onDone}
        />
      ))}
    </Space>
  );
}
