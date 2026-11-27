# B 端页面检查清单

核对入口以 `frontend/src/app/App.tsx`、`frontend/src/iam/catalog.json` 和 `frontend/src/iam/navigation.ts` 为准。计入 29 个旧运营入口、1 个门店经营角色入口、3 个平台角色入口和 36 个中央授权或协作入口。订单完整详情、商品及成长页签、目录操作和任务回执随父入口验收。

## 旧运营

| 路径 | 页面 | 核对重点 |
| --- | --- | --- |
| `/#dashboard` | 经营总览 | 总览聚合、指标联动与门店待办 |
| `/#members` | 会员档案 | 主要列表、原查询及表单或详情入口 |
| `/#growth` | 会员成长 | 主要列表、原查询及表单或详情入口 |
| `/#member-tags` | 会员标签字典 | 主要列表、原查询及表单或详情入口 |
| `/#segments` | 动态人群 | 主要列表、原查询及表单或详情入口 |
| `/#audiences` | 人群快照 | 主要列表、原查询及表单或详情入口 |
| `/#skus` | 商品管理 | 规格、商品、类目、展示、价格和批量作业 |
| `/#inventory` | 库存额度 | 库存行明细与零可售提示 |
| `/#merchants` | 商家管理 | 主体列表与门店关系 |
| `/#stores` | 店铺管理 | 状态快筛、开店、授权与关联入口 |
| `/#store-grants` | 经营授权 | 主要列表、原查询及表单或详情入口 |
| `/#campaigns` | 活动管理 | 活动目录、审批、试算与版本表单 |
| `/#effects` | 营销效果 | 活动已付比例和各效果窗口 |
| `/#rules` | 动态规则 | 主要列表、原查询及表单或详情入口 |
| `/#budgets` | 营销预算 | 版本预算、金额及占用 |
| `/#coupons` | 优惠券 | 主要列表、原查询及表单或详情入口 |
| `/#coupon-deliveries` | 定向发券 | 批次发放构成、撤回结果和分页回执 |
| `/#definitions` | 权益定义 | 主要列表、原查询及表单或详情入口 |
| `/#entitlements` | 权益台账 | 权益状态、来源与原补偿流程 |
| `/#journeys` | 营销旅程 | 主要列表、原查询及表单或详情入口 |
| `/#instances` | 旅程实例 | 主要列表、原查询及表单或详情入口 |
| `/#orders` | 订单工作台 | 状态快筛、日期范围、原单核对和编号复制 |
| `/#fulfillments` | 履约队列 | 履约状态、命令、原单与售后阻拦 |
| `/#aftersales` | 售后审批 | 待审批、退货、退款中与原单核对 |
| `/#refunds` | 退款核对 | 未知与成功、原售后链路 |
| `/#pages` | 低代码页面 | 主要列表、原查询及表单或详情入口 |
| `/#events` | 异步事件 | 主要列表、原查询及表单或详情入口 |
| `/#recovery` | 任务恢复 | 主要列表、原查询及表单或详情入口 |
| `/#replay` | 历史重放 | 主要列表、原查询及表单或详情入口 |

## 门店与平台角色

| 路径 | 页面 | 核对重点 |
| --- | --- | --- |
| `/#skus` | 门店商品经营 | 共用商品工作区，限定角色和授权门店 |
| `/#platform-runtime` | 运行健康 | 聚合采样、队列、实际积压与重试 |
| `/#platform-categories` | 平台分类字典 | 类目关系、状态、详情与版本维护 |
| `/#platform-operation-audits` | 操作日志 | 独立时间条件、容量和已访页、详情 |

## 中央授权与协作

| 路径 | 页面 | 核对重点 |
| --- | --- | --- |
| `/collaboration/products` | 商品协作 | 授权商品与门店统计、游标和导出 |
| `/operations/aftersales` | 售后 | 待审批、退货、退款中与原单核对 |
| `/operations/audiences` | 人群快照 | 主要列表、原查询及表单或详情入口 |
| `/operations/campaign-budgets` | 活动预算 | 版本预算、金额及占用 |
| `/operations/campaigns` | 活动管理 | 活动目录、审批、试算与版本表单 |
| `/operations/catalog` | 商品经营 | 复用商品经营与授权范围 |
| `/operations/coupon-definitions` | 优惠券定义 | 主要列表、原查询及表单或详情入口 |
| `/operations/coupon-deliveries` | 定向发券 | 批次发放构成、撤回结果和分页回执 |
| `/operations/dashboard` | 经营总览 | 总览聚合、指标联动与门店待办 |
| `/operations/directory` | 商家与门店 | 商家目录、门店状态和开店作业 |
| `/operations/entitlement-definitions` | 权益定义 | 主要列表、原查询及表单或详情入口 |
| `/operations/entitlements` | 权益台账 | 权益状态、来源与原补偿流程 |
| `/operations/events` | 事件处理 | 主要列表、原查询及表单或详情入口 |
| `/operations/fulfillments` | 履约 | 履约状态、命令、原单与售后阻拦 |
| `/operations/inventory` | 库存作业 | 库存行明细与零可售提示 |
| `/operations/journey-instances` | 旅程实例 | 主要列表、原查询及表单或详情入口 |
| `/operations/journey-scans` | 生命周期扫描 | 主要列表、原查询及表单或详情入口 |
| `/operations/journeys` | 旅程定义 | 主要列表、原查询及表单或详情入口 |
| `/operations/marketing-effects` | 营销效果 | 分系列窗口、活动已付比例和原数据 |
| `/operations/marketing-executions` | 营销执行 | 主要列表、原查询及表单或详情入口 |
| `/operations/member-behavior` | 会员行为 | 主要列表、原查询及表单或详情入口 |
| `/operations/member-cycle-benefits` | 周期权益 | 主要列表、原查询及表单或详情入口 |
| `/operations/member-cycles` | 会员周期 | 主要列表、原查询及表单或详情入口 |
| `/operations/member-growth` | 成长与等级 | 主要列表、原查询及表单或详情入口 |
| `/operations/member-points` | 积分账户 | 主要列表、原查询及表单或详情入口 |
| `/operations/member-tags` | 会员标签 | 主要列表、原查询及表单或详情入口 |
| `/operations/members` | 会员档案 | 主要列表、原查询及表单或详情入口 |
| `/operations/ops-pages` | 运营页面 | 主要列表、原查询及表单或详情入口 |
| `/operations/orders` | 订单 | 状态快筛、日期范围、原单核对和编号复制 |
| `/operations/payments` | 支付 | 支付队列、原单和独立动作 |
| `/operations/point-offers` | 积分兑换 | 主要列表、原查询及表单或详情入口 |
| `/operations/products` | 授权商品 | 授权商品与门店统计、游标和导出 |
| `/operations/refunds` | 退款 | 未知与成功、原售后链路 |
| `/operations/rules` | 动态规则 | 主要列表、原查询及表单或详情入口 |
| `/operations/runtime` | 运行恢复 | 主要列表、原查询及表单或详情入口 |
| `/operations/segments` | 动态人群 | 主要列表、原查询及表单或详情入口 |

## 导航边界

- 旧登录、未知路由、身份失效与手机侧栏仍走原壳层。
- `/operations` 和 `/iam/callback` 由本人菜单与会话负责。中央兼容入口跟随实际声明。读取、页面导航与写入权限仍独立。
- 会员自助页面沿用原入口。会员端共享订单回归随项目 CI 执行。
