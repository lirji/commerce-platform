import { instant, type Field, type Values } from "./ui";
const id = (name: string, label = "标识"): Field => ({ name, label });
const name: Field = { name: "name", label: "名称" };
const version: Field = {
  name: "version",
  label: "版本",
  type: "number",
  min: 1,
  initial: 1,
};
export const dateFields: Field[] = [
  { name: "validFrom", label: "开始时间", type: "datetime" },
  { name: "validTo", label: "结束时间", type: "datetime" },
];
export const couponFields: Field[] = [
  { name: "issuanceMode", label: "发行方式", type: "select", initial: "PUBLIC", options: [{ label: "公开领取", value: "PUBLIC" }, { label: "受控发放（积分兑换等）", value: "SOURCE_ONLY" }] },
  id("definitionId", "券定义标识"),
  version,
  name,
  {
    name: "minimumSpend",
    label: "最低消费金额",
    type: "money",
    initial: "0.00",
  },
  { name: "discountAmount", label: "优惠金额", type: "money" },
  ...dateFields,
  { name: "quota", label: "发行总量", type: "number", min: 1 },
  {
    name: "stackable",
    label: "允许叠加活动",
    type: "switch",
    required: false,
    initial: false,
  },
  {
    name: "platformFundingBps",
    label: "平台承担比例（万分比）",
    type: "number",
    max: 10000,
    initial: 0,
  },
];
export const couponBody = (v: Values, store: string) => ({
  ...v,
  storeId: store,
  validFrom: instant(v.validFrom),
  validTo: instant(v.validTo),
});
