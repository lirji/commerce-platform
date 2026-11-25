# 系统功能地图与审计覆盖

审计基线 `a5f1b3704ddb2ae52e51992a8f58fef355d35f48`，2026-11-25。以注册入口、实际渲染组件、后端 Controller/Owner、迁移与现有测试交叉核对。盘点 **73 个角色×业务页面入口**（29 个旧管理员，7 个会员，1 个平台运维，36 个中央与协作），旧门店运营的 `/#skus` 另计为同组件角色变体。登录、恢复、未知页面、`/operations` 组织入口和 OIDC 回调作为边界单独检查。页签、详情、编辑、预览与确认归入母页。

## 功能地图与菜单

| 领域 | 页面能力 | 权威关系与数据证据 |
| --- | --- | --- |
| 商家、门店、授权 | 目录创建、归属、授权商品和协作 | 商家 → 门店 → 商品。旧经营授权为 `store_operator_grant`，中央身份使用独立能力和范围读证明。见 [目录与凭据基表](../../../commerce-app/src/main/resources/db/migration/V1__commerce_foundation.sql)、[中央范围](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java)。 |
| 商品和库存 | SPU、SKU、图文、条码、结构化类目/模板、渠道定价、上下架计划、收货 | SKU 与门店共同限定库存。版本事实由商品和库存各自负责，历史报价保持快照。见 [商品模块](../../../catalog/src/main/java)、[库存 Mapper](../../../inventory/src/main/resources/mappers/inventory/InventoryMapper.xml)。 |
| 会员 | 档案、行为、标签、成长、积分、周期、礼包/兑换 | 会员号串联画像、成长账本和积分账本。策略、账本、兑换回执属于不同事实。见 [会员模块](../../../member/src/main/java)、[权益模块](../../../benefit/src/main/java)。 |
| 活动、人群、规则与资产 | 规则、快照、曝光资格、活动审核、模拟、券和权益定义、预算 | 固定活动/规则/快照定义版本。预算按活动内容版跟踪上限、已预占与已支出。见 [营销运行](../../../marketing-runtime/src/main/java)、[预算 Mapper](../../../marketing-runtime/src/main/resources/mappers/campaign/BudgetMapper.xml)。 |
| 发放、旅程及效果 | 动态人群运行、定向券发放/撤回、旅程实例/扫描/事件、效果投影 | 人群定义 → 运行 → 固定输出快照，批次绑定券与固定快照，旅程固定定义版本执行。效果以投影为准。见 [任务与洞察域](../../../marketing-automation/src/main/java)。 |
| 交易、支付、履约与售后 | 单店报价、订单、支付核对、发货签收、退货退款与补偿 | 报价 → 订单 → 支付事实，履约受售后阻拦，原申请与原退款一一追踪。见 [订单运行](../../../order-runtime/src/main/java)、[支付](../../../payment/src/main/java)、[履约](../../../fulfillment/src/main/java)、[售后](../../../aftersales/src/main/java)。 |
| 低代码、平台运行 | 白名单页面、版本发布/回退、运行监测、事件、恢复、历史重放 | 页面内容/控制版本、任务结果、消费者可重放性质各有 Owner。见 [运行域](../../../platform-runtime/src/main/java)、[页面接口](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OpsPageController.java)。 |

主系统为 React/TypeScript/Vite + Ant Design 同源工作空间，Java 21 / Spring Boot 多模块单进程与 MySQL/Flyway。后台以持久事件、任务及回执模型支撑恢复。旧管理员的 hash 工作台、会员工作台、平台运维使用旧身份链，中央使用企业会话、本人菜单、租户上下文与业务授权。中央菜单显示由编译声明与本人导航交集决定，最终操作和资源范围由后端校验。中央 `/operations/products` 和 `/operations/catalog` 分别是范围商品目录和门店 SKU 经营台，`/collaboration/products` 共享授权商品组件，它们需在菜单与标题明确用途。 [旧路由](../../../frontend/src/app/App.tsx#L92)、[中央菜单](../../../frontend/src/iam/catalog.json)、[中央导航过滤](../../../frontend/src/iam/businessNavigation.ts)、[授权路由](../../../commerce-app/src/main/java/com/lrj/commerce/app/configuration/security/SecurityConfiguration.java#L34)。

交易试点另有店铺/目录、商城报价和已签定价到原生订单的服务契约，本次纳入接口与风险核对，项目内未发现对应独立试点前端页面。平台现有数据以单店及当前明确的沙箱、渠道与库存语义分析。跨店合并结算、分账、多币种、统一 WMS 数据与任意业务线筛选不列为现有能力。 [试点记录](../../../docs/delivery/lc02-store-pilot/TEST_RESULT.md)。

## 业务对象与状态校核

| 作业 | 业务完成依据 | 现有能力的验证边界 |
| --- | --- | --- |
| 会员与商品维护 | 会员归属、档案版本、规格及发布状态以各 Owner 读取为准。日常价格、渠道价格、历史锁价是三类事实。 | 旧目录及样本实测，中央细粒度授权、批量调价只核源码。 [档案查询](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java#L52)、[渠道价服务](../../../catalog/src/main/java/com/lrj/commerce/catalog/pricing/application/ChannelPriceService.java)。 |
| 报价、订单、支付、发货、售后 | 订单状态从待付款、支付中、关闭中、已付款、履约、完成或取消流转。支付渠道未知仍须核原事实。售后申请可进入待退货、退款中，待原退款成功推进完成。 | 旧订单详情、支付读取及售后详情实测。支付、收货和退款命令与真实渠道待验证。 [订单状态](../../../order/src/main/java/com/lrj/commerce/order/api/OrderState.java)、[售后服务](../../../aftersales/src/main/java/com/lrj/commerce/aftersales/application/AftersaleService.java#L157)。 |
| 活动、预算、券和权益 | 草拟、送审、审核、发布/暂停受状态控制。模拟返回固定版本判定。预算实际受交易资金约束。权益的预留、发放、消费和补偿独立。 | 活动、预算、券和权益目录及活动配置实测，仅静态核验发布、预算预占和真实额度。 [活动](../../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/management/application/CampaignService.java#L170)、[权益](../../../benefit/src/main/java/com/lrj/commerce/benefit/entitlement/application/EntitlementService.java)。 |
| 发券、人群、旅程和恢复 | 持久化批次、逐人结果、扫描、入组、旅程步骤、恢复审计和试运行给独立事实，事件可靠处理或回填影响统计范围。 | 样本列表、状态和门面访问实测，空批次和无隔离任务时只核界面与源码。中央授权来源、任务恢复和积极业务结果待隔离复验。 [批次](../../../marketing-automation/src/main/java/com/lrj/commerce/journey/delivery/api/CouponDeliveryApi.java)、[旅程](../../../marketing-automation/src/main/java/com/lrj/commerce/journey/api/JourneyApi.java)、[恢复](../../../platform-runtime/src/main/java/com/lrj/commerce/runtime/recovery/RuntimeRecovery.java)。 |

## 页面与接口核对表

接口统一省略公共 `/v1` 前缀，表中列首个主要读取端点，详细交互、数据与涉及的控制面见各页。菜单分组以当前代码为准，报告中的中央业务名称采用原菜单，页面标题可能不同。

### 旧运营台

| 菜单组 | 页面 | 路由 | 组件 | 主要读接口 | 审计证据 |
| --- | --- | --- | --- | --- | --- |
| 经营总览 | [L01 经营总览](02-operation-experience-audit.md#l01) | `/#dashboard` | [Dashboard](../../../frontend/src/features/Dashboard.tsx#L60) | `/admin/dashboard` | 本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察 |
| 会员经营 | [L02 会员档案](02-operation-experience-audit.md#l02) | `/#members` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/members` | 本地桌面 · 业务 GET 已观察 · 首个可见表 1 行 |
| 会员经营 | [L03 会员成长](02-operation-experience-audit.md#l03) | `/#growth` | [MemberGrowth](../../../frontend/src/features/MemberGrowth.tsx#L62) | `/admin/member-growth/{member}/ledger` | 本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察 |
| 会员经营 | [L04 会员标签字典](02-operation-experience-audit.md#l04) | `/#member-tags` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/member-tags` | 本地桌面 · 业务 GET 已观察 · 首个可见表 0 行 |
| 会员经营 | [L05 动态人群](02-operation-experience-audit.md#l05) | `/#segments` | [Segments](../../../frontend/src/features/Segments.tsx#L54) | `/admin/segments` | 本地桌面 · 业务 GET 已观察 · 首个可见表 0 行 |
| 会员经营 | [L06 人群快照](02-operation-experience-audit.md#l06) | `/#audiences` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/audiences` | 本地桌面 · 业务 GET 已观察 · 首个可见表 1 行 |
| 商品与门店 | [L07 商品管理](02-operation-experience-audit.md#l07) | `/#skus` | [ProductOperations](../../../frontend/src/features/ProductOperations.tsx#L83) | `/operations/catalog-search` | 本地桌面 · 业务 GET 已观察 · 首个可见表 6 行 |
| 商品与门店 | [L08 库存额度](02-operation-experience-audit.md#l08) | `/#inventory` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/inventory` | 本地桌面 · 业务 GET 已观察 · 首个可见表 6 行 |
| 商品与门店 | [L09 商家管理](02-operation-experience-audit.md#l09) | `/#merchants` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/merchants` | 本地桌面 · 业务 GET 已观察 · 首个可见表 1 行 |
| 商品与门店 | [L10 店铺管理](02-operation-experience-audit.md#l10) | `/#stores` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/stores` | 本地桌面 · 业务 GET 已观察 · 首个可见表 1 行 |
| 商品与门店 | [L11 经营授权](02-operation-experience-audit.md#l11) | `/#store-grants` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/store-grants` | 本地桌面 · 业务 GET 已观察 · 首个可见表 0 行 |
| 营销与旅程 | [L12 活动管理](02-operation-experience-audit.md#l12) | `/#campaigns` | [Marketing](../../../frontend/src/features/Marketing.tsx#L30) | `/admin/campaigns` | 本地桌面 · 业务 GET 已观察 · 首个可见表 2 行 |
| 营销与旅程 | [L13 营销效果](02-operation-experience-audit.md#l13) | `/#effects` | [MarketingEffects](../../../frontend/src/features/MarketingEffects.tsx#L45) | `/admin/marketing-effects` | 本地桌面 · 业务 GET 已观察 · 首个可见表 0 行 |
| 营销与旅程 | [L14 动态规则](02-operation-experience-audit.md#l14) | `/#rules` | [Marketing](../../../frontend/src/features/Marketing.tsx#L30) | `/admin/rules` | 本地桌面 · 业务 GET 已观察 · 首个可见表 1 行 |
| 营销与旅程 | [L15 营销预算](02-operation-experience-audit.md#l15) | `/#budgets` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/campaign-budgets` | 本地桌面 · 业务 GET 已观察 · 首个可见表 2 行 |
| 营销与旅程 | [L16 优惠券](02-operation-experience-audit.md#l16) | `/#coupons` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/coupon-definitions` | 本地桌面 · 业务 GET 已观察 · 首个可见表 1 行 |
| 营销与旅程 | [L17 定向发券](02-operation-experience-audit.md#l17) | `/#coupon-deliveries` | [CouponDeliveries](../../../frontend/src/features/CouponDeliveries.tsx#L58) | `/admin/coupon-deliveries` | 本地桌面 · 业务 GET 已观察 · 首个可见表 0 行 |
| 营销与旅程 | [L18 权益定义](02-operation-experience-audit.md#l18) | `/#definitions` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/entitlement-definitions` | 本地桌面 · 业务 GET 已观察 · 首个可见表 1 行 |
| 营销与旅程 | [L19 权益台账](02-operation-experience-audit.md#l19) | `/#entitlements` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/entitlements` | 本地桌面 · 业务 GET 已观察 · 首个可见表 2 行 |
| 营销与旅程 | [L20 营销旅程](02-operation-experience-audit.md#l20) | `/#journeys` | [Journeys](../../../frontend/src/features/Journeys.tsx#L280) | `/admin/journeys` | 本地桌面 · 业务 GET 已观察 · 首个可见表 2 行 |
| 营销与旅程 | [L21 旅程实例](02-operation-experience-audit.md#l21) | `/#instances` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/journey-instances` | 本地桌面 · 业务 GET 已观察 · 首个可见表 3 行 |
| 交易与交付 | [L22 订单工作台](02-operation-experience-audit.md#l22) | `/#orders` | [Orders](../../../frontend/src/features/Orders.tsx#L43) | `/admin/orders` | 本地桌面 · 业务 GET 已观察 · 首个可见表 5 行 |
| 交易与交付 | [L23 履约队列](02-operation-experience-audit.md#l23) | `/#fulfillments` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/fulfillments` | 本地桌面 · 业务 GET 已观察 · 首个可见表 4 行 |
| 交易与交付 | [L24 售后审批](02-operation-experience-audit.md#l24) | `/#aftersales` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/aftersales` | 本地桌面 · 业务 GET 已观察 · 首个可见表 2 行 |
| 交易与交付 | [L25 退款核对](02-operation-experience-audit.md#l25) | `/#refunds` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/refunds` | 本地桌面 · 业务 GET 已观察 · 首个可见表 2 行 |
| 平台工具 | [L26 低代码页面](02-operation-experience-audit.md#l26) | `/#pages` | [OpsPages](../../../frontend/src/features/OpsPages.tsx#L253) | `/admin/ops-pages` | 本地桌面 · 业务 GET 已观察 · 首个可见表 1 行 |
| 平台工具 | [L27 异步事件](02-operation-experience-audit.md#l27) | `/#events` | [AdminData](../../../frontend/src/features/AdminData.tsx#L408) | `/admin/events` | 本地桌面 · 业务 GET 已观察 · 首个可见表 24 行 |
| 平台工具 | [L28 任务恢复](02-operation-experience-audit.md#l28) | `/#recovery` | [Recovery](../../../frontend/src/features/RuntimeOperations.tsx#L180) | `/admin/runtime/stopped` | 本地桌面 · 业务 GET 已观察 · 首个可见表 0 行 |
| 平台工具 | [L29 历史重放](02-operation-experience-audit.md#l29) | `/#replay` | [Replay](../../../frontend/src/features/RuntimeOperations.tsx#L566) | `/admin/runtime/replays` | 本地桌面 · 业务 GET 已观察 · 首个可见表 0 行 |

### 会员端

| 菜单组 | 页面 | 路由 | 组件 | 主要读接口 | 审计证据 |
| --- | --- | --- | --- | --- | --- |
| 会员端 | [M01 逛店铺](02-operation-experience-audit.md#m01) | `/#shop` | [Shop](../../../frontend/src/features/Shop.tsx#L34) | `/catalog/search` | 本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察 |
| 会员端 | [M02 我的订单](02-operation-experience-audit.md#m02) | `/#orders` | [Orders](../../../frontend/src/features/Orders.tsx#L43) | `/orders` | 本地桌面 · 业务 GET 已观察 · 首个可见表 5 行 |
| 会员端 | [M03 优惠券中心](02-operation-experience-audit.md#m03) | `/#coupons` | [MemberWallet](../../../frontend/src/features/MemberWallet.tsx#L23) | `/coupons` | 本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察 |
| 会员端 | [M04 我的权益](02-operation-experience-audit.md#m04) | `/#benefits` | [MemberWallet](../../../frontend/src/features/MemberWallet.tsx#L23) | `/entitlements` | 本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察 |
| 会员端 | [M05 我的成长](02-operation-experience-audit.md#m05) | `/#growth` | [MemberGrowth](../../../frontend/src/features/MemberGrowth.tsx#L62) | `/members/me/growth/ledger` | 本地桌面 · 业务 GET 已观察 · 首个可见表 0 行 |
| 会员端 | [M06 售后记录](02-operation-experience-audit.md#m06) | `/#aftersales` | [MemberWallet](../../../frontend/src/features/MemberWallet.tsx#L23) | `/aftersales` | 本地桌面 · 业务 GET 已观察 · 首个可见表 2 行 |
| 会员端 | [M07 站内消息](02-operation-experience-audit.md#m07) | `/#notifications` | [MemberWallet](../../../frontend/src/features/MemberWallet.tsx#L23) | `/notifications` | 本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察 |

### 平台运维

| 菜单组 | 页面 | 路由 | 组件 | 主要读接口 | 审计证据 |
| --- | --- | --- | --- | --- | --- |
| 平台运维 | [P01 运行健康](02-operation-experience-audit.md#p01) | `/#platform-runtime` | [PlatformRuntime](../../../frontend/src/features/PlatformRuntime.tsx#L67) | `/platform/runtime` | 源码 · 平台身份待实测 |

### 中央企业与外部协作

| 菜单组 | 页面 | 路由 | 组件 | 主要读接口 | 审计证据 |
| --- | --- | --- | --- | --- | --- |
| 经营总览 | [C01 经营总览](02-operation-experience-audit.md#c01) | `/operations/dashboard` | [CentralDashboard](../../../frontend/src/iam/CentralDashboard.tsx#L9) | `/admin/dashboard` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 商品与门店 | [C02 商品经营](02-operation-experience-audit.md#c02) | `/operations/catalog` | [CentralCatalog](../../../frontend/src/iam/CentralCatalog.tsx#L56) | `/operations/catalog-search` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 商品与门店 | [C03 授权商品](02-operation-experience-audit.md#c03) | `/operations/products` | [Products](../../../frontend/src/iam/CentralProducts.tsx#L435) | `/operations/scoped/product` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 商品与门店 | [C04 商家与门店](02-operation-experience-audit.md#c04) | `/operations/directory` | [DirectorySection](../../../frontend/src/iam/CentralDirectory.tsx#L171) | `/admin/merchants` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 商品与门店 | [C05 库存作业](02-operation-experience-audit.md#c05) | `/operations/inventory` | [Inventory](../../../frontend/src/iam/CentralInventory.tsx#L201) | `/admin/inventory` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 会员经营 | [C06 会员档案](02-operation-experience-audit.md#c06) | `/operations/members` | [MemberList](../../../frontend/src/iam/CentralMembers.tsx#L207) | `/admin/members` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 会员经营 | [C07 成长与等级](02-operation-experience-audit.md#c07) | `/operations/member-growth` | [WalletDetails](../../../frontend/src/iam/CentralGrowth.tsx#L227) | `/admin/member-growth/{member}/ledger` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 会员经营 | [C08 会员标签](02-operation-experience-audit.md#c08) | `/operations/member-tags` | [TagDictionary](../../../frontend/src/iam/CentralTags.tsx#L197) | `/admin/member-tags` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 会员经营 | [C09 会员行为](02-operation-experience-audit.md#c09) | `/operations/member-behavior` | [BehaviorDetail](../../../frontend/src/iam/CentralBehavior.tsx#L272) | `/admin/member-behavior/{member}` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 会员经营 | [C10 会员周期](02-operation-experience-audit.md#c10) | `/operations/member-cycles` | [Policies](../../../frontend/src/iam/CentralCycles.tsx#L285) | `/admin/member-cycles/policies` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 会员经营 | [C11 周期权益](02-operation-experience-audit.md#c11) | `/operations/member-cycle-benefits` | [Bundles](../../../frontend/src/iam/CentralCycles.tsx#L452) | `/admin/member-cycle-benefits` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 会员经营 | [C12 积分账户](02-operation-experience-audit.md#c12) | `/operations/member-points` | [WalletDetails](../../../frontend/src/iam/CentralPoints.tsx#L261) | `/admin/member-points/{member}/ledger` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 会员经营 | [C13 积分兑换](02-operation-experience-audit.md#c13) | `/operations/point-offers` | [OfferList](../../../frontend/src/iam/CentralPointOffers.tsx#L153) | `/admin/point-offers` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C14 活动管理](02-operation-experience-audit.md#c14) | `/operations/campaigns` | [Directory](../../../frontend/src/iam/CentralCampaigns.tsx#L145) | `/admin/campaigns` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C15 活动预算](02-operation-experience-audit.md#c15) | `/operations/campaign-budgets` | [Budgets](../../../frontend/src/iam/CentralCampaigns.tsx#L1032) | `/admin/campaign-budgets` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C16 人群快照](02-operation-experience-audit.md#c16) | `/operations/audiences` | [Directory](../../../frontend/src/iam/CentralAudiences.tsx#L47) | `/admin/audiences` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C17 动态人群](02-operation-experience-audit.md#c17) | `/operations/segments` | [Directory](../../../frontend/src/iam/CentralSegments.tsx#L359) | `/admin/segments` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C18 动态规则](02-operation-experience-audit.md#c18) | `/operations/rules` | [Directory](../../../frontend/src/iam/CentralRules.tsx#L123) | `/admin/rules` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C19 优惠券定义](02-operation-experience-audit.md#c19) | `/operations/coupon-definitions` | [DefinitionList](../../../frontend/src/iam/CentralCouponDefinitions.tsx#L145) | `/admin/coupon-definitions` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C20 定向发券](02-operation-experience-audit.md#c20) | `/operations/coupon-deliveries` | [Directory](../../../frontend/src/iam/CentralCouponDeliveries.tsx#L397) | `/admin/coupon-deliveries` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C21 权益定义](02-operation-experience-audit.md#c21) | `/operations/entitlement-definitions` | [DefinitionList](../../../frontend/src/iam/CentralEntitlementDefinitions.tsx#L141) | `/admin/entitlement-definitions` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C22 权益台账](02-operation-experience-audit.md#c22) | `/operations/entitlements` | [Directory](../../../frontend/src/iam/CentralEntitlements.tsx#L121) | `/admin/entitlements` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C23 旅程定义](02-operation-experience-audit.md#c23) | `/operations/journeys` | [Workspace](../../../frontend/src/iam/CentralJourneys.tsx#L154) | `/admin/journeys` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C24 旅程实例](02-operation-experience-audit.md#c24) | `/operations/journey-instances` | [Workspace](../../../frontend/src/iam/CentralJourneys.tsx#L154) | `/admin/journey-instances` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C25 生命周期扫描](02-operation-experience-audit.md#c25) | `/operations/journey-scans` | [Workspace](../../../frontend/src/iam/CentralJourneys.tsx#L154) | `/admin/journey-scans` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C26 营销效果](02-operation-experience-audit.md#c26) | `/operations/marketing-effects` | [Workspace](../../../frontend/src/iam/CentralJourneys.tsx#L154) | `/admin/marketing-effects` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 营销资产 | [C27 营销执行](02-operation-experience-audit.md#c27) | `/operations/marketing-executions` | [Workspace](../../../frontend/src/iam/CentralJourneys.tsx#L154) | `/admin/marketing-executions` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 交易协作 | [C28 订单](02-operation-experience-audit.md#c28) | `/operations/orders` | [CentralOrders](../../../frontend/src/iam/CentralOrders.tsx#L20) | `/admin/orders` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 交易协作 | [C29 支付](02-operation-experience-audit.md#c29) | `/operations/payments` | [PaymentRead](../../../frontend/src/iam/CentralPayments.tsx#L13) | `/admin/orders/{order}/payment` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 交易协作 | [C30 履约](02-operation-experience-audit.md#c30) | `/operations/fulfillments` | [CentralFulfillments](../../../frontend/src/iam/CentralFulfillments.tsx#L8) | `/admin/fulfillments` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 交易协作 | [C31 售后](02-operation-experience-audit.md#c31) | `/operations/aftersales` | [CentralAftersales](../../../frontend/src/iam/CentralAftersales.tsx#L8) | `/admin/aftersales` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 交易协作 | [C32 退款](02-operation-experience-audit.md#c32) | `/operations/refunds` | [CentralRefunds](../../../frontend/src/iam/CentralRefunds.tsx#L8) | `/admin/refunds` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 运营与运行 | [C33 运营页面](02-operation-experience-audit.md#c33) | `/operations/ops-pages` | [PageRead](../../../frontend/src/iam/CentralOpsPages.tsx#L572) | `/admin/ops-pages` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 运营与运行 | [C34 事件处理](02-operation-experience-audit.md#c34) | `/operations/events` | [CentralEvents](../../../frontend/src/iam/CentralEvents.tsx#L145) | `/admin/events` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 运营与运行 | [C35 运行恢复](02-operation-experience-audit.md#c35) | `/operations/runtime` | [RuntimeRead](../../../frontend/src/iam/CentralRuntime.tsx#L63) | `/admin/runtime/stopped` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |
| 外部协作 | [C36 商品协作](02-operation-experience-audit.md#c36) | `/collaboration/products` | [Products](../../../frontend/src/iam/CentralProducts.tsx#L435) | `/operations/scoped/product` | 壳层 HTTP 200 · IAM 未启用，岗位交互待实测 |

## 浏览器验证、证据及覆盖边界

- 既有本机 **8602** 容器健康，运行镜像的 revision 与审计基线完全一致。Chrome/Chromium 自动化使用已有独立演示租户中的管理员与会员身份。桌面 1440×900 下 29+7 页实际加载，390×900 下复查 9 个旧版业务页面。另复现订单过滤与支付读取、商品两种查询、活动与售后详情、买家商品详情共 5 条只读路径。全轮页面观察无 JS 页面异常、无文档级横向溢出，订单等仍有预期内的表格容器横滚。390px 订单筛选占据首屏，需要向下滚动才能到达第一行。
- 36 个中央业务路径加 OIDC 回调桌面壳层 HTTP 200，显示统一身份未启用，组织首页 `GET /operations` 返回 401。源码中它在前端 `centralEntry` 生效，固定页面控制器和 GET 白名单均未包含。真实岗位的 PKCE、获准菜单、业务表格和操作授权待在已启用的隔离环境复验。 [前端入口](../../../frontend/src/iam/navigation.ts#L6)、[页面控制器](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralPageController.java#L10)、[鉴权白名单](../../../commerce-app/src/main/java/com/lrj/commerce/app/configuration/security/SecurityConfiguration.java#L36)。
- 平台运维 `/#platform-runtime` 和旧 OPERATOR 商品变体源码已覆盖，本机没有可用的对应演示凭据。试点业务写、真实渠道、获准岗位权限组合、跨租户、多店对照、峰值 SQL 与可访问性全量验收均待验证。浏览器采用 GET/HEAD 限制。买家打开商品详情触发的行为采集 POST 计入拦截证据，其余浏览器写请求为零。
- 逐页对照覆盖检查使用 `App.tsx` 的菜单和角色分支、`catalog.json` 的 36 个中央业务路由、组件渲染分支及 Controller 注解。本地原始审计证据存放在 `.local/operations-experience-audit/`，其中 `browser.json`、`scenarios.json`、截屏、组件表列/端点/迁移索引可复核。文档内的链接指向版本化证据，原始演示数据随本机留存。

## 阅读顺序

- [02-operation-experience-audit.md](02-operation-experience-audit.md)
- [03-table-and-filter-optimization.md](03-table-and-filter-optimization.md)
- [04-business-workflow-optimization.md](04-business-workflow-optimization.md)
- [05-operation-risk-audit.md](05-operation-risk-audit.md)
- [06-global-experience-standard.md](06-global-experience-standard.md)
- [07-optimization-backlog.md](07-optimization-backlog.md)
- [08-implementation-roadmap.md](08-implementation-roadmap.md)
- [09-executive-summary.md](09-executive-summary.md)
