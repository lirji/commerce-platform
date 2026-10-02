import {
  Alert,
  Button,
  Checkbox,
  Form,
  Input,
  InputNumber,
  Select,
  Space,
  Typography,
} from "antd";
import type { FormInstance } from "antd";
import { RuleEditor } from "../shared/marketing";
import type { Rule } from "../shared/contracts";
import { campaignIdentifier, type CampaignDraft } from "./campaignClient";

const BenefitKind = {
  NONE: "none",
  ENTITLEMENT: "grant",
  COUPON: "coupon",
} as const;

export type DraftFields = {
  campaignId: string;
  version: number;
  storeId: string;
  name: string;
  validFrom: string;
  validTo: string;
  minimumSpend: string;
  discountAmount: string;
  rule?: Rule;
  useFixedRule?: boolean;
  audienceId?: string;
  audienceVersion?: number;
  ruleId?: string;
  ruleVersion?: number;
  percentageBps?: number;
  platformFundingBps?: number;
  budget?: string;
  benefitKind?: (typeof BenefitKind)[keyof typeof BenefitKind];
  benefitId?: string;
  benefitVersion?: number;
  advanced?: boolean;
  includedSkuIds?: string;
  excludedSkuIds?: string;
  tiers?: {
    minimumSpend: string;
    discountAmount: string;
    percentageBps: number;
  }[];
};
const identifiers = (text?: string) =>
  (text ?? "")
    .split(/[,\n]/)
    .map((id) => id.trim())
    .filter(Boolean);
export const campaignIdRules = [
  {
    required: true,
    pattern: campaignIdentifier,
    message: "请输入1至64位字母、数字、下划线、点、冒号或连字符",
  },
];
export const positiveVersionRules = [
  {
    required: true,
    type: "integer" as const,
    min: 1,
    max: Number.MAX_SAFE_INTEGER,
    message: "请输入正安全整数版本",
  },
];
const moneyRules = [
  {
    required: true,
    pattern: /^(0|[1-9][0-9]{0,11})(\.[0-9]{1,2})?$/,
    message: "请输入非负金额，最多两位小数",
  },
];
/** 精确金额仍以字符串传输；技术枚举不含虚构门店/会员/固定资产。 */
export function buildCampaignDraft(value: DraftFields): CampaignDraft {
  const hasPolicy = !!(value.audienceId || value.ruleId || value.advanced);
  if (
    !hasPolicy &&
    (value.budget ||
      value.percentageBps ||
      value.platformFundingBps ||
      (value.benefitKind && value.benefitKind !== BenefitKind.NONE))
  )
    throw new Error("预算、比例和权益配置需引用固定资产或声明商品价格策略");
  if (value.useFixedRule && !value.ruleId)
    throw new Error("请选择填写固定规则编号与版本，或使用资格规则编辑器");
  const included = identifiers(value.includedSkuIds),
    excluded = identifiers(value.excludedSkuIds);
  if (
    value.advanced &&
    [included, excluded].some(
      (list) =>
        list.length > 100 ||
        new Set(list).size !== list.length ||
        list.some((id) => !campaignIdentifier.test(id)),
    )
  )
    throw new Error("每项商品范围最多100个合法SKU，且不能重复");
  return {
    campaignId: value.campaignId,
    version: value.version,
    storeId: value.storeId,
    name: value.name.trim(),
    validFrom: new Date(value.validFrom).toISOString(),
    validTo: new Date(value.validTo).toISOString(),
    minimumSpend: value.minimumSpend,
    discountAmount: value.discountAmount,
    rule: value.useFixedRule ? null : value.rule!,
    ...(hasPolicy
      ? {
          policy: {
            ...(value.audienceId
              ? {
                  audience: {
                    id: value.audienceId,
                    version: value.audienceVersion!,
                  },
                }
              : {}),
            ...(value.ruleId
              ? { rule: { id: value.ruleId, version: value.ruleVersion! } }
              : {}),
            terms: {
              percentageBps: value.percentageBps ?? 0,
              platformFundingBps: value.platformFundingBps ?? 0,
              budget: value.budget || null,
              ...(value.advanced
                ? {
                    pricing: {
                      includedSkuIds: included,
                      excludedSkuIds: excluded,
                      tiers: value.tiers ?? [],
                    },
                  }
                : {}),
              ...(value.benefitKind === BenefitKind.ENTITLEMENT
                ? {
                    grant: {
                      benefitId: value.benefitId!,
                      version: value.benefitVersion!,
                    },
                  }
                : {}),
              ...(value.benefitKind === BenefitKind.COUPON
                ? {
                    coupon: {
                      definitionId: value.benefitId!,
                      version: value.benefitVersion!,
                    },
                  }
                : {}),
            },
          },
        }
      : {}),
  };
}
export function CampaignDraftForm({
  form,
  disabled,
  onChange,
  onFinish,
}: {
  form: FormInstance<DraftFields>;
  disabled: boolean;
  onChange: () => void;
  onFinish: (value: DraftFields) => void;
}) {
  const fixed = Form.useWatch("useFixedRule", form),
    advanced = Form.useWatch("advanced", form),
    kind = Form.useWatch("benefitKind", form),
    ruleId = Form.useWatch("ruleId", form),
    audienceId = Form.useWatch("audienceId", form);
  return (
    <Form
      form={form}
      name="campaign-create"
      layout="vertical"
      disabled={disabled}
      onValuesChange={onChange}
      onFinish={onFinish}
      initialValues={{
        version: 1,
        minimumSpend: "0.00",
        percentageBps: 0,
        platformFundingBps: 0,
        benefitKind: BenefitKind.NONE,
        rule: {
          kind: "COMPARE",
          field: "memberLevel",
          operator: "EQ",
          valueType: "TEXT",
          value: "",
        },
      }}
    >
      <Alert
        type="info"
        showIcon
        title="保存草稿后按实际审批状态继续提交、审批和发布"
        description="门店、固定规则、人群、权益和SKU填写真实编号；服务端检查归属、有效期与已发布状态。时间按浏览器时区输入。"
      />
      <Typography.Title level={5}>基础信息</Typography.Title>
      <div className="node-grid">
        <Form.Item name="campaignId" label="活动编号" rules={campaignIdRules}>
          <Input maxLength={64} />
        </Form.Item>
        <Form.Item
          name="version"
          label="内容版本（不可变）"
          rules={positiveVersionRules}
        >
          <InputNumber
            min={1}
            max={Number.MAX_SAFE_INTEGER}
            precision={0}
            style={{ width: "100%" }}
          />
        </Form.Item>
        <Form.Item name="storeId" label="实际门店编号" rules={campaignIdRules}>
          <Input maxLength={64} />
        </Form.Item>
        <Form.Item
          name="name"
          label="活动名称"
          rules={[{ required: true, whitespace: true, max: 128 }]}
        >
          <Input maxLength={128} />
        </Form.Item>
        <Form.Item
          name="validFrom"
          label="开始时间"
          rules={[{ required: true }]}
        >
          <Input type="datetime-local" />
        </Form.Item>
        <Form.Item
          name="validTo"
          label="结束时间"
          dependencies={["validFrom"]}
          rules={[
            { required: true },
            {
              validator: (_, value) =>
                !value ||
                new Date(value).getTime() >
                  new Date(form.getFieldValue("validFrom")).getTime()
                  ? Promise.resolve()
                  : Promise.reject(new Error("结束时间须晚于开始时间")),
            },
          ]}
        >
          <Input type="datetime-local" />
        </Form.Item>
        <Form.Item
          name="minimumSpend"
          label="消费门槛（元）"
          rules={moneyRules}
        >
          <Input inputMode="decimal" />
        </Form.Item>
        <Form.Item
          name="discountAmount"
          label="优惠金额 / 比例折扣上限（元）"
          rules={[
            ...moneyRules,
            {
              validator: (_, v) =>
                !v || /[1-9]/.test(v)
                  ? Promise.resolve()
                  : Promise.reject(new Error("优惠金额必须大于零")),
            },
          ]}
        >
          <Input inputMode="decimal" />
        </Form.Item>
      </div>
      <Typography.Title level={5}>资格与固定资产</Typography.Title>
      <Form.Item name="useFixedRule" valuePropName="checked">
        <Checkbox>仅使用固定已发布规则</Checkbox>
      </Form.Item>
      {!fixed && (
        <Form.Item name="rule" label="资格规则" rules={[{ required: true }]}>
          <RuleEditor />
        </Form.Item>
      )}
      <div className="node-grid">
        <Form.Item
          name="ruleId"
          label="固定规则编号（可选）"
          rules={[{ required: !!fixed, pattern: campaignIdentifier }]}
        >
          <Input maxLength={64} />
        </Form.Item>
        <Form.Item
          name="ruleVersion"
          label="固定规则版本"
          dependencies={["ruleId"]}
          rules={[
            {
              ...positiveVersionRules[0],
              required: !!ruleId,
            },
          ]}
        >
          <InputNumber min={1} max={Number.MAX_SAFE_INTEGER} precision={0} />
        </Form.Item>
        <Form.Item
          name="audienceId"
          label="固定人群编号（可选）"
          rules={[{ pattern: campaignIdentifier }]}
        >
          <Input maxLength={64} />
        </Form.Item>
        <Form.Item
          name="audienceVersion"
          label="固定人群版本"
          dependencies={["audienceId"]}
          rules={[
            {
              ...positiveVersionRules[0],
              required: !!audienceId,
            },
          ]}
        >
          <InputNumber min={1} max={Number.MAX_SAFE_INTEGER} precision={0} />
        </Form.Item>
      </div>
      <Typography.Title level={5}>优惠与预算</Typography.Title>
      <div className="node-grid">
        <Form.Item
          name="percentageBps"
          label="折扣比例（万分比，0为固定减免）"
          rules={[{ type: "integer", min: 0, max: 10000 }]}
        >
          <InputNumber min={0} max={10000} precision={0} />
        </Form.Item>
        <Form.Item
          name="platformFundingBps"
          label="平台资方比例（万分比）"
          rules={[{ type: "integer", min: 0, max: 10000 }]}
        >
          <InputNumber min={0} max={10000} precision={0} />
        </Form.Item>
        <Form.Item
          name="budget"
          label="预算上限（元，可留空）"
          rules={[
            { ...moneyRules[0], required: false },
            {
              validator: (_, v) =>
                !v || /[1-9]/.test(v)
                  ? Promise.resolve()
                  : Promise.reject(new Error("预算必须大于零")),
            },
          ]}
        >
          <Input inputMode="decimal" />
        </Form.Item>
      </div>
      <Typography.Title level={5}>权益与商品价格配置</Typography.Title>
      <Form.Item name="benefitKind" label="关联权益类型">
        <Select
          options={[
            { value: BenefitKind.NONE, label: "不关联" },
            { value: BenefitKind.ENTITLEMENT, label: "权益定义" },
            { value: BenefitKind.COUPON, label: "优惠券定义" },
          ]}
        />
      </Form.Item>
      {kind !== BenefitKind.NONE && kind && (
        <div className="node-grid">
          <Form.Item
            name="benefitId"
            label={
              kind === BenefitKind.COUPON ? "优惠券定义编号" : "权益定义编号"
            }
            rules={campaignIdRules}
          >
            <Input maxLength={64} />
          </Form.Item>
          <Form.Item
            name="benefitVersion"
            label="权益定义版本"
            rules={positiveVersionRules}
          >
            <InputNumber min={1} max={Number.MAX_SAFE_INTEGER} precision={0} />
          </Form.Item>
        </div>
      )}
      {kind === BenefitKind.COUPON && (
        <Typography.Paragraph type="secondary">
          优惠券关联是否启用由服务端配置裁决。
        </Typography.Paragraph>
      )}
      <Form.Item name="advanced" valuePropName="checked">
        <Checkbox>启用商品范围与阶梯价格</Checkbox>
      </Form.Item>
      {advanced && (
        <>
          <Form.Item
            name="includedSkuIds"
            label="参与SKU（逗号或换行分隔，留空为全部）"
          >
            <Input.TextArea rows={3} maxLength={6500} />
          </Form.Item>
          <Form.Item name="excludedSkuIds" label="排除SKU（排除优先）">
            <Input.TextArea rows={3} maxLength={6500} />
          </Form.Item>
          <Form.List name="tiers">
            {(fields, { add, remove }) => (
              <Space orientation="vertical" style={{ width: "100%" }}>
                {fields.map((field) => (
                  <CardlessTier
                    key={field.key}
                    name={field.name}
                    remove={() => remove(field.name)}
                  />
                ))}
                <Button
                  disabled={fields.length >= 8}
                  onClick={() => add({ percentageBps: 0 })}
                >
                  添加价格阶梯（最多8项）
                </Button>
              </Space>
            )}
          </Form.List>
          <Typography.Paragraph type="secondary">
            阶梯门槛须严格递增，由服务端按精确金额验证。
          </Typography.Paragraph>
        </>
      )}
    </Form>
  );
}
function CardlessTier({ name, remove }: { name: number; remove: () => void }) {
  return (
    <div className="node-grid">
      <Form.Item
        name={[name, "minimumSpend"]}
        label={`阶梯${name + 1}消费门槛（元）`}
        rules={moneyRules}
      >
        <Input inputMode="decimal" />
      </Form.Item>
      <Form.Item
        name={[name, "discountAmount"]}
        label="阶梯优惠上限（元）"
        rules={moneyRules}
      >
        <Input inputMode="decimal" />
      </Form.Item>
      <Form.Item
        name={[name, "percentageBps"]}
        label="阶梯优惠比例（万分比）"
        rules={[{ required: true, type: "integer", min: 0, max: 10000 }]}
      >
        <InputNumber min={0} max={10000} precision={0} />
      </Form.Item>
      <Button onClick={remove}>移除阶梯</Button>
    </div>
  );
}
