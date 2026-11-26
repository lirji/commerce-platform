import { useListFilters } from "../shared/listFilters";
import {
  OrderEventLink,
  TradeLinks,
  useWorkspaceHref,
} from "../shared/workspaceLinks";
import { useCursorState } from "../shared/routeState";
import { RecordModal, RowActions } from "../shared/interactions";
import { compactTradeFields, TradeRowFacts } from "../shared/TradeRowFacts";
import { StockCounts } from "../shared/StockCounts";
import {
  tradeFieldValue,
  tradeNumberColumnWidth,
  tradeNumberFields,
} from "../shared/tradeNumbers";
import { Button, Grid, Space, Spin, Table, Typography } from "antd";
import { MemberActions } from "./MemberActions";
import { LegacyJourneyHistory } from "./LegacyJourneyHistory";
import { useState, type ReactNode } from "react";
import { encode, useResource } from "../shared/api";
import {
  ActionButton,
  CommandModal,
  Detail,
  ErrorNotice,
  ListPanel,
  PageHead,
  PrimaryCell,
  RecordHero,
  Status,
  Workbench,
  type Field,
  type Values,
  instant,
  formatField,
  money,
  string,
  time,
} from "../shared/ui";
import {
  ENTITLEMENT_ORDER_SOURCE,
  type Capabilities,
} from "../shared/contracts";
type Row = Record<string, unknown>;
type Spec = {
  title: string;
  description: string;
  path: string;
  id: string;
  store?: boolean;
  actionWidth?: number;
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
  "member-tags": {
    title: "会员标签字典",
    description:
      "标签标识供人群和促销规则稳定引用；在会员成长页维护会员标签关联。",
    path: "/admin/member-tags",
    id: "tagId",
    fields: [id("tagId", "标签标识"), name],
    columns: [["name", "标签"]],
  },
  "store-grants": {
    title: "商家与门店经营授权",
    description:
      "仅向已有运营身份授予商品经营权。商家授权包含其未来新增门店；不授予会员库、资金、订单或平台发布权限。",
    path: "/admin/store-grants",
    id: "grantId",
    fields: [
      id("grantId", "授权标识"),
      id("actorId", "运营主体"),
      {
        name: "resourceType",
        label: "资源范围",
        type: "select",
        options: [
          { label: "单个门店", value: "STORE" },
          { label: "商家及所有门店", value: "MERCHANT" },
        ],
      },
      id("resourceId", "门店或商家标识"),
      { name: "reason", label: "授权原因" },
    ],
    build: (v) => ({ ...v, permission: "CATALOG" }),
    columns: [
      ["actorId", "运营主体"],
      ["resourceType", "范围"],
      ["resourceId", "资源"],
      ["active", "授权状态"],
      ["version", "版本"],
      ["reason", "原因"],
    ],
  },
  members: {
    title: "会员档案",
    actionWidth: 200,
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
      ["displayName", "会员"],
      ["status", "状态"],
      ["memberLevel", "等级"],
      ["actorId", "认证主体"],
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
      ["watermark", "来源时点"],
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
      ["version", "内容版本"],
      ["issuanceMode", "发行方式"],
      ["validTo", "定义截止时间"],
      ["issued", "累计已发行"],
      ["quota", "发行上限"],
      ["discountAmount", "优惠"],
      ["minimumSpend", "门槛"],
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
      ["version", "固定版本"],
      ["units", "每份单位"],
      ["validTo", "定义截止"],
      ["validityDays", "发放后有效天数"],
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
      ["sourceType", "来源类型"],
      ["sourceId", "来源编号"],
      ["status", "状态"],
      ["remainingUnits", "剩余单位"],
      ["debtUnits", "待补偿单位"],
      ["expiresAt", "有效期"],
      ["memberId", "会员"],
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
      ["available", "可用额度"],
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
      ["memberId", "会员"],
      ["refundAmount", "退款金额"],
      ["refundId", "退款编号"],
      ["returnRequired", "需退货"],
      ["status", "状态"],
    ],
  },
  refunds: {
    title: "退款核对",
    description: "未知结果不释放资金预留，成功证据推动售后完成。",
    path: "/admin/refunds",
    id: "refundId",
    actionWidth: 180,
    columns: [
      ["refundId", "退款编号"],
      ["orderId", "订单"],
      ["caseId", "售后编号"],
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
      ["failureClass", "失败分类"],
      ["availableAt", "下次可投递"],
      ["attempts", "失败次数"],
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
      ["instanceId", "实例"],
      ["journeyId", "旅程"],
      ["journeyVersion", "固定旅程版本"],
      ["status", "状态"],
      ["dueAt", "下次处理时间"],
      ["memberId", "会员"],
      ["currentNode", "待执行节点"],
      ["attempts", "重试次数"],
    ],
  },
};
const eyebrows: Record<string, string> = {
  members: "会员经营",
  "member-tags": "会员经营",
  audiences: "会员经营",
  merchants: "商品与门店",
  stores: "商品与门店",
  "store-grants": "商品与门店",
  skus: "商品与门店",
  inventory: "商品与门店",
  coupons: "营销与旅程",
  definitions: "营销与旅程",
  entitlements: "营销与旅程",
  budgets: "营销与旅程",
  instances: "营销与旅程",
  fulfillments: "交易与交付",
  aftersales: "交易与交付",
  refunds: "交易与交付",
  events: "平台工具",
};
function cell(key: string, value: unknown): ReactNode {
  if (key === "status") return <Status value={string(value)} />;
  if (key === "resourceType") {
    if (value === "STORE") return "单个门店";
    if (value === "MERCHANT") return "商家及其门店";
  }
  if (key === "active" && typeof value === "boolean")
    return value ? "生效" : "已撤销";
  if (key === "issuanceMode" || key === "sourceType")
    return formatField(key, value);
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
  if (
    [
      "watermark",
      "validUntil",
      "validTo",
      "availableAt",
      "createdAt",
      "dueAt",
      "expiresAt",
    ].includes(key)
  )
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
  const isTrade = ["fulfillments", "aftersales", "refunds"].includes(kind);
  const narrow = Grid.useBreakpoint().md === false;
  const compact = narrow && isTrade;
  const compactStock = narrow && spec === specs.inventory;
  const href = useWorkspaceHref();
  const [after, setAfter] = useCursorState("after", "");
  const filters = useListFilters(
    `AdminData.${kind}`,
    spec.path,
    () => setAfter(""),
    compact,
  );
  const [selected, setDetail] = useState<Row>();
  const tradeDetail = useResource<Row>(
    isTrade && selected
      ? `${spec.path}/${encode(string(selected[spec.id]))}`
      : null,
  );
  const detail = isTrade
    ? tradeDetail.data?.[spec.id] === selected?.[spec.id]
      ? tradeDetail.data
      : undefined
    : selected;
  const query =
    spec.path +
    `?after=${encode(after)}&${filters.query}` +
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
  const rowCell = (key: string, row: Row) => {
    if (
      spec === specs.budgets &&
      ["cap", "held", "spent", "available"].includes(key)
    ) {
      return (key === "cap" || key === "available") && row.cap === null
        ? "未设上限"
        : money(row[key]);
    }
    return cell(key, tradeFieldValue(spec.path, key, row));
  };
  function actions(r: Row) {
    const value = string(r[spec.id]);
    const target = encode(value);
    const state = string(r.status);
    return (
      <RowActions>
        {kind === "members" ? (
          <MemberActions
            row={r}
            onDone={refresh}
            onRecord={() => setDetail(r)}
          />
        ) : (
          <Button type="link" onClick={() => setDetail(r)}>
            详情
          </Button>
        )}
        {kind === "store-grants" && (
          <CommandModal
            title={r.active ? "撤销授权" : "恢复授权"}
            buttonType="link"
            path={"/admin/store-grants/" + target + "/status"}
            fields={[{ name: "reason", label: "变更原因" }]}
            build={(v) => ({
              ...v,
              active: !r.active,
              expectedVersion: r.version,
            })}
            onDone={refresh}
          />
        )}
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
      </RowActions>
    );
  }
  const numberFields = tradeNumberFields[spec.path];
  const detailId = detail
    ? string(tradeFieldValue(spec.path, spec.id, detail))
    : "";
  const displayedDetail = detail && numberFields ? { ...detail } : detail;
  if (displayedDetail && detail && numberFields) {
    for (const [idField, numberField] of Object.entries(numberFields)) {
      displayedDetail[idField] = tradeFieldValue(spec.path, idField, detail);
      delete displayedDetail[numberField];
    }
  }
  const detailTitle = detail
    ? string(
        detail.name || detail.title || detail.displayName || detailId,
      )
    : spec.title;
  return (
    <Workbench>
      <PageHead
        eyebrow={eyebrows[kind] ?? "经营工作台"}
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
            {kind === "member-tags" && (
              <Typography.Paragraph>
                标签编号可用于会员关联及规则条件。会员关联可在会员成长的「成长与账本」中查看。
              </Typography.Paragraph>
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
      <ErrorNotice error={resource.error} onRetry={resource.refresh} />
      <ListPanel
        toolbar={filters.toolbar}
        pageSize={filters.limit}
        loading={resource.loading}
        error={resource.error}
        count={rows?.length ?? 0}
        after={after}
        cursorName={"after"}
        onHome={() => setAfter("")}
        onNext={() => setAfter(string(rows!.at(-1)![spec.id]))}
      >
        <Table<Row>
          rowKey={(r) => string(r[spec.id])}
          dataSource={rows}
          loading={resource.loading}
          pagination={false}
          scroll={{ x: numberFields ? (kind === "refunds" ? 1150 : 1360) : 800 }}
          columns={[
            ...spec.columns
              .filter(
                ([key]) =>
                  (!compact || !compactTradeFields.has(key)) &&
                  (!compactStock ||
                    !["available", "held", "sold"].includes(key)),
              )
              .map(([key, label], index) => ({
                title: label,
                dataIndex: key,
                className: numberFields?.[key] ? "trade-business-number-cell" : undefined,
                ellipsis: !numberFields?.[key] && !((compact || compactStock) && index === 0),
                width:
                  numberFields?.[key] || ((compact || compactStock) && index === 0)
                    ? tradeNumberColumnWidth
                    : kind === "refunds" && key === "provider"
                      ? 118
                      : undefined,
                align: [
                  "available",
                  "held",
                  "sold",
                  "units",
                  "quota",
                  "issued",
                  "reserved",
                  "remainingUnits",
                  "debtUnits",
                  "memberCount",
                  "unitPrice",
                  "discountAmount",
                  "minimumSpend",
                  "refundAmount",
                  "amount",
                  "cap",
                  "spent",
                  "attempts",
                ].includes(key)
                  ? ("right" as const)
                  : ("left" as const),
                render: (_: unknown, r: Row) =>
                  compactStock && index === 0 ? (
                    <div className="primary-cell">
                      <strong>{string(r.skuId)}</strong>
                      <StockCounts row={r} />
                    </div>
                  ) : compact && index === 0 ? (
                    <div className="trade-row-identity">
                      <Button type="link" onClick={() => setDetail(r)}>
                        {rowCell(key, r)}
                      </Button>
                      <TradeRowFacts row={r} />
                    </div>
                  ) : index === 0 ? (
                    <PrimaryCell
                      title={rowCell(key, r)}
                      subtitle={
                        key === spec.id ? undefined : string(r[spec.id])
                      }
                      status={string(r.status)}
                    />
                  ) : (
                    rowCell(key, r)
                  ),
              })),
            {
              title: "操作",
              width: spec.actionWidth ?? 280,
              fixed: numberFields && !compact ? ("right" as const) : undefined,
              align: "right" as const,
              className: "row-actions-cell",
              render: (_, r) => actions(r),
            },
          ]}
        />
      </ListPanel>
      <RecordModal
        className="record-modal"
        title={detail ? detailTitle : "业务记录详情"}
        open={!!selected}
        onCancel={() => setDetail(undefined)}
        width={560}
      >
        {isTrade && (
          <ErrorNotice
            error={
              tradeDetail.error ??
              (tradeDetail.data && !detail
                ? new Error("返回记录与所选编号不一致")
                : undefined)
            }
            onRetry={tradeDetail.refresh}
          />
        )}
        {isTrade && selected && !tradeDetail.data && !tradeDetail.error && (
          <Spin description="正在读取当前业务记录" />
        )}
        {detail && (
          <>
            <RecordHero
              eyebrow={spec.title}
              title={detailTitle}
              id={
                <Typography.Text copyable={{ text: detailId }}>
                  {detailId}
                </Typography.Text>
              }
              status={detail.status ? string(detail.status) : undefined}
              metrics={spec.columns
                .filter(([key]) =>
                  [
                    "unitPrice",
                    "amount",
                    "refundAmount",
                    "cap",
                    "discountAmount",
                    "available",
                    "memberCount",
                    "remainingUnits",
                  ].includes(key),
                )
                .slice(0, 3)
                .map(([key, label]) => ({
                  label,
                  value: rowCell(key, detail),
                }))}
            />
            {isTrade && (
              <TradeLinks orderId={detail.orderId} pages={["orders"]} />
            )}
            {spec === specs.entitlements &&
              detail.sourceType === ENTITLEMENT_ORDER_SOURCE && (
                <TradeLinks orderId={detail.orderId} pages={["orders"]} />
              )}
            {spec === specs.instances &&
              typeof detail.instanceId === "string" && (
                <LegacyJourneyHistory
                  key={detail.instanceId}
                  instanceId={detail.instanceId}
                />
              )}
            {kind === "events" && (
              <OrderEventLink
                eventType={detail.eventType}
                aggregateId={detail.aggregateId}
              />
            )}
            {kind === "stores" && typeof detail.storeId === "string" && (
              <Space wrap>
                <a href={href("skus", { store: detail.storeId })}>
                  查看门店商品
                </a>
                <a href={href("inventory", { store: detail.storeId })}>
                  查看门店库存
                </a>
              </Space>
            )}
            {kind === "merchants" && typeof detail.merchantId === "string" && (
              <a
                href={href("stores", {
                  "AdminData.stores.query": JSON.stringify({
                    merchantId: detail.merchantId,
                  }),
                })}
              >
                查看下属门店
              </a>
            )}
            {kind === "members" && typeof detail.memberId === "string" && (
              <Space wrap>
                <a
                  href={href("growth", {
                    member: detail.memberId,
                    tab: "wallet",
                  })}
                >
                  查看会员成长
                </a>
                <a href={href("growth", { member: detail.memberId })}>
                  查看会员行为
                </a>
              </Space>
            )}
            <Detail value={detail} displayValue={displayedDetail} />
          </>
        )}
      </RecordModal>
    </Workbench>
  );
}
