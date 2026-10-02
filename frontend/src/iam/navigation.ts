/** 已接线的中央页面目录仅表示入口，不表示持有任何业务授权。 */
export const centralGroups = [
  {key:"overview", label:"经营总览", pages:[["/operations/dashboard","经营总览"]]},
  {key:"trade", label:"交易协作", pages:[["/operations/orders","订单"],["/operations/payments","支付"],["/operations/fulfillments","履约"],["/operations/aftersales","售后"],["/operations/refunds","退款"]]},
  {key:"platform", label:"运营与运行", pages:[["/operations/ops-pages","运营页面"],["/operations/events","事件处理"],["/operations/runtime","运行恢复"]]},

  {
    key: "member",
    label: "会员经营",
    pages: [
      ["/operations/members", "会员档案"],
      ["/operations/member-behavior", "会员行为"],
      ["/operations/member-growth", "成长与等级"],
      ["/operations/member-tags", "会员标签"],
      ["/operations/member-points", "积分账户"],
      ["/operations/member-cycles", "会员周期"],
      ["/operations/member-cycle-benefits", "周期权益"],
      ["/operations/point-offers", "积分兑换"],
    ],
  },
  {
    key: "catalog",
    label: "商品与门店",
    pages: [
      ["/operations/products", "授权商品"],
      ["/operations/catalog", "商品经营"],
      ["/operations/inventory", "库存作业"],
      ["/operations/directory", "商家与门店"],
    ],
  },
  {
    key: "marketing",
    label: "营销资产",
    pages: [
      ["/operations/coupon-definitions", "优惠券定义"],
      ["/operations/entitlement-definitions", "权益定义"],
      ["/operations/entitlements", "权益台账"],
      ["/operations/rules", "动态规则"],
      ["/operations/audiences", "人群快照"],
      ["/operations/segments", "动态人群"],
      ["/operations/coupon-deliveries", "定向发券"],
      ["/operations/journeys", "旅程定义"],
      ["/operations/journey-instances", "旅程实例"],
      ["/operations/journey-scans", "生命周期扫描"],
      ["/operations/marketing-effects", "营销效果"],
      ["/operations/marketing-executions", "营销执行"],
      ["/operations/campaigns", "活动管理"],
      ["/operations/campaign-budgets", "活动预算"],
    ],
  },
];
export const centralRoutes = [
  ...centralGroups.flatMap((g) => g.pages.map(([path]) => path)),
  "/collaboration/products",
];
