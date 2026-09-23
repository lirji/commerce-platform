import { Button, Card, Drawer, Space, Table, Tabs } from "antd";
import { useState, type ReactNode } from "react";
import { encode, useResource } from "../shared/api";
import {
  ActionButton,
  CommandModal,
  Detail,
  ErrorNotice,
  PageHead,
  Status,
  type Field,
  type Values,
  instant,
  money,
  string,
  time,
} from "../shared/ui";
import type { Capabilities } from "../shared/contracts";
type Row = Record<string, unknown>;
type Spec = {
  title: string;
  description: string;
  path: string;
  id: string;
  store?: boolean;
  fields?: Field[];
  columns: [string, string][];
  build?: (v: Values, store: string) => unknown;
};
const id = (name: string, label = "标识"): Field => ({ name, label });
const name: Field = { name: "name", label: "名称" };
const version: Field = {
  name: "version",
  label: "版本",
  type: "number",
  min: 1,
  initial: 1,
};
import { dateFields, couponFields, couponBody } from "../shared/formSchemas";
export const specs: Record<string, Spec> = {
  members: {
    title: "会员档案",
    description: "维护会员与认证主体的对应关系。",
    path: "/admin/members",
    id: "memberId",
    fields: [
      id("memberId", "会员标识"),
      id("actorId", "认证主体"),
      { name: "displayName", label: "显示名称" },
      { name: "memberLevel", label: "会员等级" },
    ],
    columns: [
      ["displayName", "会员名称"],
      ["memberId", "会员标识"],
      ["actorId", "认证主体"],
      ["memberLevel", "等级"],
      ["status", "状态"],
    ],
  },
  merchants: {
    title: "商家管理",
    description: "商家与店铺保持明确归属。",
    path: "/admin/merchants",
    id: "merchantId",
    fields: [id("merchantId", "商家标识"), name],
    columns: [
      ["name", "商家"],
      ["merchantId", "标识"],
      ["status", "状态"],
    ],
  },
  stores: {
    title: "店铺管理",
    description: "商品、优惠与旅程绑定具体店铺。",
    path: "/admin/stores",
    id: "storeId",
    fields: [id("storeId", "店铺标识"), id("merchantId", "商家标识"), name],
    columns: [
      ["name", "店铺"],
      ["storeId", "标识"],
      ["merchantId", "所属商家"],
      ["status", "状态"],
    ],
  },
  skus: {
    title: "商品管理",
    description: "价格使用精确金额，历史成交快照保持不变。",
    path: "/admin/skus",
    id: "skuId",
    store: true,
    fields: [
      id("skuId", "SKU标识"),
      { name: "title", label: "商品名称" },
      { name: "unitPrice", label: "销售单价", type: "money" },
    ],
    build: (v, store) => ({ ...v, storeId: store }),
    columns: [
      ["title", "商品"],
      ["skuId", "SKU"],
      ["unitPrice", "单价"],
      ["status", "状态"],
    ],
  },
  inventory: {
    title: "库存额度",
    description: "可售、预占与已售分别记录；资金未知期间保留占用。",
    path: "/admin/inventory",
    id: "skuId",
    store: true,
    columns: [
      ["skuId", "SKU"],
      ["available", "可售"],
      ["held", "预占"],
      ["sold", "已售"],
      ["version", "版本"],
    ],
  },
  audiences: {
    title: "人群快照",
    description: "固定成员与来源时间，过期资格按未知处理。",
    path: "/admin/audiences",
    id: "audienceId",
    fields: [
      id("audienceId", "人群标识"),
      version,
      name,
      { name: "source", label: "来源说明" },
      { name: "watermark", label: "来源水位时间", type: "datetime" },
      { name: "validUntil", label: "有效期（最长24小时）", type: "datetime" },
      {
        name: "memberIds",
        label: "会员标识（每行一个，最多500）",
        type: "textarea",
      },
    ],
    build: (v) => ({
      ...v,
      watermark: instant(v.watermark),
      validUntil: instant(v.validUntil),
      memberIds: string(v.memberIds).split(/\s+/).filter(Boolean),
    }),
    columns: [
      ["name", "人群"],
      ["audienceId", "标识"],
      ["version", "版本"],
      ["memberCount", "人数"],
      ["validUntil", "有效期"],
    ],
  },
  coupons: {
    title: "优惠券定义",
    description: "控制发行额度、有效期、叠加方式与承担比例。",
    path: "/admin/coupon-definitions",
    id: "definitionId",
    store: true,
    fields: couponFields,
    build: couponBody,
    columns: [
      ["name", "优惠券"],
      ["definitionId", "标识"],
      ["discountAmount", "优惠"],
      ["minimumSpend", "门槛"],
      ["quota", "发行上限"],
      ["issued", "已领"],
    ],
  },
  definitions: {
    title: "权益定义",
    description: "内部整数权益，不属于现金余额或外部发奖。",
    path: "/admin/entitlement-definitions",
    id: "benefitId",
    store: true,
    fields: [
      id("benefitId", "权益标识"),
      version,
      name,
      {
        name: "units",
        label: "每份单位数",
        type: "number",
        min: 1,
        max: 10000,
      },
      { name: "quota", label: "发行额度", type: "number", min: 1 },
      ...dateFields,
      {
        name: "validityDays",
        label: "发放后有效天数",
        type: "number",
        min: 1,
        max: 365,
      },
    ],
    build: (v, store) => ({
      ...v,
      storeId: store,
      validFrom: instant(v.validFrom),
      validTo: instant(v.validTo),
    }),
    columns: [
      ["name", "权益"],
      ["benefitId", "标识"],
      ["units", "每份单位"],
      ["quota", "发行额度"],
      ["reserved", "预占"],
      ["issued", "已发行"],
    ],
  },
  entitlements: {
    title: "权益台账",
    description: "跟进异步发放和已消费权益的退款补偿。",
    path: "/admin/entitlements",
    id: "grantId",
    columns: [
      ["name", "权益"],
      ["memberId", "会员"],
      ["remainingUnits", "剩余单位"],
      ["debtUnits", "待补偿单位"],
      ["status", "状态"],
    ],
  },
  budgets: {
    title: "营销预算",
    description: "成交消耗与预占额度分开；退款不自动恢复营销预算。",
    path: "/admin/campaign-budgets",
    id: "budgetId",
    columns: [
      ["campaignId", "活动"],
      ["version", "版本"],
      ["cap", "预算上限"],
      ["held", "已预占"],
      ["spent", "已消耗"],
    ],
  },
  fulfillments: {
    title: "履约队列",
    description: "沙箱出库与签收事实推进订单，售后阻拦期间禁止发货。",
    path: "/admin/fulfillments",
    id: "orderId",
    columns: [
      ["orderId", "订单"],
      ["status", "状态"],
      ["trackingNo", "物流单号"],
      ["blocked", "售后阻拦"],
      ["provider", "适配渠道"],
    ],
  },
  aftersales: {
    title: "售后审批",
    description: "未发货全额退款，已发货支持按商品数量部分退货。",
    path: "/admin/aftersales",
    id: "caseId",
    columns: [
      ["caseId", "售后编号"],
      ["orderId", "订单"],
      ["refundAmount", "退款金额"],
      ["returnRequired", "需退货"],
      ["status", "状态"],
    ],
  },
  refunds: {
    title: "退款核对",
    description: "未知结果不释放资金预留，成功证据推动售后完成。",
    path: "/admin/refunds",
    id: "refundId",
    columns: [
      ["refundId", "退款编号"],
      ["orderId", "订单"],
      ["amount", "金额"],
      ["provider", "渠道"],
      ["status", "状态"],
    ],
  },
  events: {
    title: "异步事件",
    description: "查看持久投递与隔离记录，按需触发有限批次。",
    path: "/admin/events",
    id: "eventId",
    columns: [
      ["eventType", "事件类型"],
      ["aggregateId", "业务标识"],
      ["status", "状态"],
      ["attempts", "失败次数"],
      ["availableAt", "可投递时间"],
    ],
  },
  instances: {
    title: "旅程实例",
    description: "等待、检查点与重试均已持久化；取消不撤销已经发生的效果。",
    path: "/admin/journey-instances",
    id: "instanceId",
    fields: [
      id("journeyId", "旅程标识"),
      version,
      id("memberId", "会员标识"),
      id("eventKey", "触发去重标识"),
    ],
    columns: [
      ["journeyId", "旅程"],
      ["memberId", "会员"],
      ["currentNode", "待执行节点"],
      ["status", "状态"],
      ["dueAt", "下次执行"],
      ["attempts", "重试次数"],
    ],
  },
};
function cell(key: string, value: unknown): ReactNode {
  if (key === "status") return <Status value={string(value)} />;
  if (
    [
      "unitPrice",
      "discountAmount",
      "minimumSpend",
      "refundAmount",
      "amount",
      "cap",
      "held",
      "spent",
    ].includes(key) &&
    typeof value === "string"
  )
    return money(value);
  if (["validUntil", "availableAt", "createdAt", "dueAt"].includes(key))
    return time(value);
  if (typeof value === "boolean") return value ? "是" : "否";
  return value == null ? "—" : String(value);
}
export function AdminData({
  kind,
  store,
  capabilities,
  onStoresChanged,
}: {
  kind: string;
  store: string;
  capabilities: Capabilities;
  onStoresChanged: () => void;
}) {
  const spec = specs[kind];
  const [after, setAfter] = useState("");
  const [detail, setDetail] = useState<Row>();
  const query =
    spec.path +
    `?after=${encode(after)}` +
    (spec.store ? "&storeId=" + encode(store) : "");
  const resource = useResource<Row[]>(spec.store && !store ? null : query);
  const rows = resource.data?.map((r) => ({
    ...((r.content ?? {}) as Row),
    ...r,
  }));
  const refresh = () => {
    resource.refresh();
    if (kind === "stores") onStoresChanged();
  };
  function actions(r: Row) {
    const value = string(r[spec.id]);
    const target = encode(value);
    const state = string(r.status);
    return (
      <Space wrap>
        <Button size="small" onClick={() => setDetail(r)}>
          详情
        </Button>
        {kind === "fulfillments" && capabilities.sandboxEnabled && (
          <>
            {state === "READY" && !r.blocked && (
              <CommandModal
                title="沙箱发货"
                path={"/admin/fulfillments/" + target + "/ship"}
                fields={[{ name: "trackingNo", label: "物流单号" }]}
                onDone={refresh}
                buttonType="default"
              />
            )}
            {state === "SHIPPED" && !r.blocked && (
              <ActionButton
                label="沙箱签收"
                path={"/admin/fulfillments/" + target + "/deliver"}
                onDone={refresh}
              />
            )}
          </>
        )}
        {kind === "aftersales" && (
          <>
            {state === "REQUESTED" && (
              <>
                <ActionButton
                  label="批准"
                  path={"/admin/aftersales/" + target + "/approve"}
                  onDone={refresh}
                />
                <ActionButton
                  label="驳回"
                  path={"/admin/aftersales/" + target + "/reject"}
                  onDone={refresh}
                />
              </>
            )}
            {state === "WAIT_RETURN" && (
              <ActionButton
                label="确认退货入库"
                path={"/admin/aftersales/" + target + "/receive-return"}
                onDone={refresh}
              />
            )}
          </>
        )}
        {kind === "refunds" && state === "UNKNOWN" && (
          <>
            <ActionButton
              label="核对退款"
              path={"/admin/refunds/" + target + "/reconcile"}
              onDone={refresh}
            />
            {capabilities.sandboxEnabled && (
              <ActionButton
                label="沙箱：模拟退款成功"
                path={"/admin/sandbox/refunds/" + target + "/success"}
                onDone={refresh}
              />
            )}
          </>
        )}
        {kind === "events" && state === "ISOLATED" && (
          <ActionButton
            label="重放事件"
            path={"/admin/events/" + target + "/retry"}
            onDone={refresh}
          />
        )}
        {kind === "instances" && (
          <>
            {["RUNNING", "WAITING", "ISOLATED"].includes(state) && (
              <ActionButton
                label="取消后续节点"
                path={"/admin/journey-instances/" + target + "/cancel"}
                onDone={refresh}
              />
            )}
            {state === "ISOLATED" && (
              <ActionButton
                label="重试节点"
                path={"/admin/journey-instances/" + target + "/retry"}
                onDone={refresh}
              />
            )}
          </>
        )}
        {kind === "entitlements" && state === "COMPENSATION_REQUIRED" && (
          <CommandModal
            title="处理补偿"
            path={"/admin/entitlements/" + target + "/resolve"}
            fields={[
              {
                name: "resolution",
                label: "处理结论",
                type: "select",
                options: [
                  { label: "已追回", value: "RECOVERED" },
                  { label: "核销损失", value: "WRITTEN_OFF" },
                ],
              },
              { name: "reference", label: "处理凭据编号" },
            ]}
            onDone={refresh}
            buttonType="default"
          />
        )}
      </Space>
    );
  }
  return (
    <>
      <PageHead
        title={spec.title}
        description={spec.description}
        extra={
          <>
            <Button onClick={refresh}>刷新</Button>
            {spec.fields && (
              <CommandModal
                title={
                  kind === "instances"
                    ? "手工入组"
                    : "新建" + spec.title.replace(/管理|定义|快照|档案/g, "")
                }
                fields={spec.fields}
                path={spec.path}
                build={(v) => (spec.build ? spec.build(v, store) : v)}
                onDone={refresh}
                disabled={spec.store && !store}
              />
            )}
            {kind === "inventory" && (
              <CommandModal
                title="增加库存"
                path="/admin/inventory/receipts"
                fields={[
                  id("skuId", "SKU标识"),
                  {
                    name: "quantity",
                    label: "入库数量",
                    type: "number",
                    min: 1,
                  },
                ]}
                build={(v) => ({ ...v, storeId: store })}
                onDone={refresh}
                disabled={!store}
              />
            )}
            {kind === "events" && (
              <ActionButton
                label="处理一批事件"
                path="/admin/events/pump"
                onDone={refresh}
              />
            )}
            {kind === "instances" && (
              <ActionButton
                label="执行一批节点"
                path="/admin/journeys/pump"
                onDone={refresh}
              />
            )}
          </>
        }
      />
      <ErrorNotice error={resource.error} />
      <Card>
        <Table<Row>
          rowKey={(r) => string(r[spec.id])}
          dataSource={rows}
          loading={resource.loading}
          pagination={false}
          scroll={{ x: 800 }}
          columns={[
            ...spec.columns.map(([key, label]) => ({
              title: label,
              dataIndex: key,
              ellipsis: true,
              render: (v: unknown) => cell(key, v),
            })),
            { title: "操作", width: 280, render: (_, r) => actions(r) },
          ]}
        />
        <div className="pager">
          <Button disabled={!after} onClick={() => setAfter("")}>
            首页
          </Button>
          <span>当前页 {rows?.length ?? 0} 条</span>
          <Button
            disabled={(rows?.length ?? 0) < 50}
            onClick={() => setAfter(string(rows!.at(-1)![spec.id]))}
          >
            下一页
          </Button>
        </div>
      </Card>
      <Drawer
        title="业务记录详情"
        open={!!detail}
        onClose={() => setDetail(undefined)}
        width={680}
      >
        <Detail value={detail} />
      </Drawer>
    </>
  );
}
