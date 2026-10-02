/** 已接线的中央页面目录仅表示入口，不表示持有任何业务授权。 */
export const centralGroups = [
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
      ["/operations/campaigns", "活动管理"],
      ["/operations/campaign-budgets", "活动预算"],
    ],
  },
];
export const centralRoutes = [
  ...centralGroups.flatMap((g) => g.pages.map(([path]) => path)),
  "/collaboration/products",
];
