# 统一电商平台运营与商业化页面优化方案

**让运营人员在一个工作区判断哪里需要处理、当前数据能说明什么、下一步该到哪个原对象。** 首批选经营总览、交易异常、活动投放与营销效果。用项目已有的门店范围、队列、成交投影和固定版本组织首屏，把跨期对比、跨域聚合和运营告警留到口径及查询契约明确后实施。

[73 个入口逐页方案](PAGE_DESIGN.md) · [重点页面低保真草图](WIREFRAMES.md) · [已有运营改造进度](../../delivery/operations-experience/PROGRESS_STATE.md)

## 基线与证据

2026-11-26，源码从 `544ee2a` 核对，应用业务制品为已部署的 `da04c1e`。现有旧管理员 29 个、会员 7 个、平台运维 1 个、中央企业与协作 36 个，共 73 个角色与业务页面入口。旧门店运营共享商品经营组件，单列权限变体。总览、精准范围、固定版本及原业务对象下钻以 S1–S20 已交付的实现为起点。[原审计与入口清单](../../audit/operations-experience/01-system-overview.md) · [既有实施切片](../operations-experience/IMPLEMENTATION_SLICES.md)

- 当前程序共 86 次页面读取，包含旧管理员与会员的 36 个桌面业务入口、中央的 36 个匿名业务页面壳、组织与回调入口及代表性窄屏复验。页面请求都返回 200，记录无页面异常和业务写入。中央功能可见性按源码、已发布契约和已有岗位证据设计。S9 有隔离 PKCE、双店及撤权检验，平台运维及外部协作本次按源码设计。原始记录见 `.local/commerce-operations-product-design/browser.json`。[现有验收记录](../../delivery/operations-experience/TEST_RESULT.md)
- 代表性现象已在当前运行界面观察到。总览已将异常置顶，净收、会员总数和在售规格紧接展示，范围提示分布在卡内。390px 订单首屏看得到筛选及订单号，店名和状态需要横向滚动。历史样本不足时营销效果表为空，长口径说明与四个页签占据首屏。这些观察来自演示租户，只用于界面事实。
- 每个入口按实际注册组件、主要 API 与 Owner 约束映射。行动路径和建议指标按接口、返回字段与持久化关系核对。用户行为改善、正式经营数据和可售渠道仍需试点验证，本次结论是设计交付。

## 角色、任务和完成标志

| 角色与作业 | 首先看到 | 完成标志 |
| --- | --- | --- |
| 店长巡店 | 当前授权门店、四类异常、近 30 个 UTC 自然日投影、数据时间 | 点入同门店、同条件明细，并能跟进原业务；比较经营规模时分清店铺和租户范围 |
| 商品与库存运营 | SPU 与 SKU、门店、渠道、当前状态、可用/预占/已售及受控计划 | 对正确规格、门店和版本完成可回读的调价或收货，原报价按历史快照 |
| 会员运营与客服 | 实际会员、等级/状态、近期行为时间窗及资产 | 用同一会员号进入档案、行为或台账，原调整有持久回执 |
| 营销运营与财务 | 同版活动的资格、优惠、有效期、预算、实际发放和观察成交 | 发布读取生效版；投放读取原批次与实际结果；效果有观察起点、覆盖与成本口径 |
| 订单、履约、售后及退款岗位 | 原订单与门店、真实下一动作、渠道证据 | 跟进同一个授权原对象，读取实际支付、发货、收件或退款结果 |
| 平台值班与外部协作 | 真实故障类别与原任务，或本人可见的商品范围 | 使用原工作恢复审计；导出按原条件、权限及有效期取得结果 |
| 会员买家 | 门店、可购规格、实际应付、订单或资产状态 | 服务端生成报价和原订单，钱包与售后读取本人实际结果 |

### 导航与页面归属

保持现有入口及已发布的稳定菜单标识。页内布局围绕对象和作业调整，公开路由与分享参数使用现有兼容机制。发布菜单名称、成员范围与能力变化仍走现有目录发布和中央授权流程。

| 主工作区 | 页面之间的职责 | 建议组织 |
| --- | --- | --- |
| 经营总览 | 查看门店经营与待办，进入对应 Owner 操作 | 先异常，再成交和库存，租户会员规模单列。已有真实四类队列继续使用 |
| 商品与门店 | 目录确立主体，商品维护规格及渠道，库存处理数量 | 从商家进所属门店，再进入该店商品/库存。授权商品与商品经营按现有 Owner 和岗位区分 |
| 会员经营 | 档案找到会员，行为解释最近事实，成长与积分记录账户变化 | 会员是跨页定位键，标签定义、资产和周期策略保留各自范围 |
| 营销资产与效果 | 定义、实际投放、结果核对分别完成不同任务 | 活动版本连接规则、快照、券、预算和执行。报表按活动成交、旅程观察、券批次分别解释 |
| 交易与交付 | 原订单提供交易上下文，各域独立处理 | 订单作入口，付款核对、履约、售后和退款按原编号进入。异常可从总览直达 |
| 平台工具与协作 | 事件读取、任务恢复、历史重放，以及按范围协作 | 恢复看原工作与审计，重放先看安全分类和试运行；平台和外部身份使用各自工作区 |

## 可直接使用的数据与业务判断

同一行的字段用于回答同一决策问题。范围使用已授权组织、租户、门店及固定版本。金额按服务端十进制及币种格式化，写入和精确金额计算由 Owner 决定。下表的请求均为现有入口，省略公共 `/v1` 前缀。

| 商业问题 | 首批展示与落点 | 权威来源和边界 |
| --- | --- | --- |
| 本店先处理什么 | 四类待办显示真实首批数。数量达到上限显示“至少 10 条”，进入同门店的实际明细 | `/admin/orders` CLOSING、`/admin/fulfillments` blocked、`/admin/aftersales` REQUESTED、`/admin/refunds` UNKNOWN。每类独立读取与失败状态。[查询契约](../frontend-usability-queries.md) |
| 经营规模和净收走势 | 本店已投影已付订单、实收、成功退款、净收。用一个图切换实收/退款/净收，时间轴可键盘探索 | `/admin/dashboard` 以 UTC 下单日聚合近 30 个自然日。净收为该队列实收减截至投影已知成功退款，含窗口外发生的归属退款。租户会员规模、门店在售 SKU 单列。[DashboardController](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/DashboardController.java) · [效果投影表](../../../commerce-app/src/main/resources/db/migration/V22__marketing_effect_projection.sql) |
| 活动版本带来多少可观测成交 | 活动及固定版、已投影订单/已付、实收、退款、净收、成交优惠和平台/商家承担。行下钻保留活动、版本和原测量窗口 | `/admin/marketing-effects` 用 93 日以内的 UTC 下单窗口及系列游标。金额对应已消费或已重建的订单。可提出同一行的已付占比，分母标“已投影下单”，分母为零显示无可比样本。[MarketingEffectsApi](../../../marketing-automation/src/main/java/com/lrj/commerce/insight/api/MarketingEffectsApi.java) |
| 投放还有多少额度 | 同一活动内容版的预算上限、预占、已耗、币种和有效余额。未设上限单独标示 | `/admin/campaign-budgets` 返回 `cap / held / spent`。如需正式余额字段及告警排序，由预算 Owner 从同一快照输出 `cap − held − spent`，并按原事务口径核验。现金退款与预算释放分别展示。[预算 Mapper](../../../marketing-runtime/src/main/resources/mappers/campaign/BudgetMapper.xml) |
| 旅程到了哪个阶段、效果观察是否成熟 | 版本、观察天数、入组与完整观察人数、触达/发放、已付人数及净收。旅程实例详情显示固定版本证据 | `/admin/marketing-effects/journeys` 按 UTC 入组窗口，每会员同版首次入组为锚。已付人数可以包含未满观察窗口的会员。跨版本样本可重复，展示逐行数据与成熟度；成熟队列转化率须同一分子分母契约。原步骤走 `/admin/journey-instances/{id}/history`。[效果 Mapper](../../../marketing-automation/src/main/resources/mappers/insight/EffectsMapper.xml) · [旅程 API](../../../marketing-automation/src/main/java/com/lrj/commerce/journey/api/JourneyApi.java) |
| 发券批次有没有实际使用及异常 | 发放与撤回分组，展示已发、跳过、撤回、保留及实际用券的已付订单、券优惠与退款 | `/admin/coupon-deliveries` 为批次事实，`/admin/marketing-effects/deliveries` 按批次 UTC 创建时间选择，按实际钱包券编号关联订单。历史批次创建时点缺失时不参与该窗。核销率需明确唯一券实例、已发集合和统计时窗。[发券 API](../../../marketing-automation/src/main/java/com/lrj/commerce/journey/delivery/api/CouponDeliveryApi.java) · [效果 Mapper](../../../marketing-automation/src/main/resources/mappers/insight/EffectsMapper.xml) |
| 商品是否可买、库存是否可补 | 门店和 SKU、商品名、当前销售状态；库存横向对照可用、预占、已售；收货看原值、增量和回执 | `/operations/catalog-search` 与 `/admin/inventory` 分属商品与库存。展示规格启用、当前渠道价及现有库存事实。可下单需通过服务端报价与库存校验，旧订单沿原报价快照。[商品 API](../../../catalog/src/main/java/com/lrj/commerce/catalog/assortment/api/CatalogApi.java) · [库存 API](../../../inventory/src/main/java/com/lrj/commerce/inventory/api/InventoryApi.java) |
| 单个会员近期发生什么 | 会员状态与等级；查看近 30 日浏览、加购、完成订单、净消费及可用数据时间 | `/admin/member-behavior/{member}` 属会员行为读权限。展示目前能核对的观察字段，经营规模来自独立成员统计。分层覆盖与漏斗属于后续读模型。[会员行为 API](../../../member/src/main/java/com/lrj/commerce/member/behavior/api/MemberBehaviorApi.java) |
| 订单上的优惠和发放是否落地 | 同一原订单与活动版，分别显示促销参与状态、权益实际状态、最近事件及失败类 | `/admin/marketing-executions` 查询原号及最新事件失败类。持久参与、权益详情及事件的状态独立，晚到旧事件不能替换最新版本。[营销执行 API](../../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/execution/api/CampaignExecutionApi.java) · [接口契约](../frontend-usability-queries.md) |
| 门店经营范围内能交付什么 | 商品范围可信总数、实际导出行数、状态、有效期和原筛选 | `/operations/scoped/product` 与原导出任务，生成和下载独立核权。15 分钟有效期和 1,000 行上限对应现有流程。历史快照清理、审批、脱敏仍按治理方案签收。[范围与导出服务](../../../commerce-app/src/main/java/com/lrj/commerce/app/iam/CentralScopeService.java) · [S9 验收方案](../../delivery/operations-experience/S9_ACCEPTANCE.md) |

### 按模块核对原业务数据

| 模块 | 读写归属及与页面相关的字段 |
| --- | --- |
| 主体、店铺和经营授权 | 商家与店铺的编号、归属、名称和状态从目录读取。旧授予按商家或店铺，中央商品范围按具体授权路径与当前组织读证明。商家下属新店与单店是两种语义。[基础表迁移](../../../commerce-app/src/main/resources/db/migration/V1__commerce_foundation.sql) · [中央目录](../../../frontend/src/iam/CentralDirectory.tsx) |
| 商品、库存和结算 | 规格与商品关系、条码、库存可用/预占/已售、渠道价窗口各自有权威源。库存字段属于可售额度，实体库存和渠道仓配依独立证据。原订单取冻结报价和原 SKU，而当前销售以实际有效价读取。[商品 API](../../../catalog/src/main/java/com/lrj/commerce/catalog/assortment/api/CatalogApi.java) · [库存 Mapper](../../../inventory/src/main/resources/mappers/inventory/InventoryMapper.xml) · [下单契约](../../../order-runtime/src/main/java/com/lrj/commerce/ordering/order/api/OrderApi.java) |
| 会员、周期及权益 | 会员档案提供 `memberId`、状态和等级。行为、成长、积分与有效权益分别提供列表和账本。当前周期绑定政策版本，礼包的定义与补发回执分别提供。会员自助只能读取本人数据。[会员 API](../../../member/src/main/java/com/lrj/commerce/member/profile/api/MemberApi.java) · [周期 API](../../../member/src/main/java/com/lrj/commerce/member/cycle/api/MemberCycleApi.java) · [周期权益 API](../../../benefit/src/main/java/com/lrj/commerce/benefit/memberbenefit/api/MemberBenefitApi.java) |
| 交易协作 | 订单视图持有原订单、应付、门店及时间。支付与退款记录各带原 ID、金额、提供方和状态；申请时间、商品行及门店在售后原详情读取。编辑必须重新验证当前状态、原记录及岗位能力。[订单 API](../../../order-runtime/src/main/java/com/lrj/commerce/ordering/order/api/OrderApi.java) · [支付](../../../payment/src/main/java/com/lrj/commerce/payment/charge/api/PaymentApi.java) · [售后](../../../aftersales/src/main/java/com/lrj/commerce/aftersales/api/AftersaleApi.java) · [退款](../../../payment/src/main/java/com/lrj/commerce/payment/refund/api/RefundApi.java) |
| 活动、规则、人群和版本 | 活动内容、规则、快照及计划可一致追溯固定版本。人群统计依已有 `memberCount`、数据时点和窗口，快照有效性由 Owner 校验。旅程效果按固定定义的门店写入，扩展跨店报表前应验证历史数据也符合该不变量。[营销资产迁移](../../../commerce-app/src/main/resources/db/migration/V8__marketing_assets_and_review.sql) · [旅程 Mapper](../../../marketing-automation/src/main/resources/mappers/journey/JourneyMapper.xml) |
| 投放、恢复与异步事件 | 发券批次和成员结果各自持久化，停止/撤回分别以原方向的实际结果表示。事件按状态、原聚合号和版本派生可读事实，恢复用原工作类型、工作号及审计，重放受安全分类约束。[发券迁移](../../../commerce-app/src/main/resources/db/migration/V31__targeted_coupon_delivery.sql) · [事件聚合索引](../../../commerce-app/src/main/resources/db/migration/V74__event_aggregate_list_index.sql) · [运行恢复设计](../unified-commerce/BACKEND_ARCHITECTURE.md) |

### 需要新增契约的商业问题

| 可选能力 | 对运营的价值 | 开始条件和验收 |
| --- | --- | --- |
| 经营同期对比 | 判断本期净收、支付订单和退款是否偏离常态 | 定本地业务日或沿用 UTC、对照窗口、同一投影水位与补齐覆盖。日级聚合行可复用；跨窗、跨店总览由 Owner 批量查询。空窗口、跨时区、迟到退款均保留语义 |
| 异常时长、店铺工作量 | 判断优先级、是否超过处理窗口 | 由各 Owner 提供进入异常的实际时间，区分下单时间、申请时间与异常时长；同权限和状态的总数独立查询。S9 的容量证据用于设立测试基线 |
| 活动健康摘要、预算余额及名单可用性 | 运营在发布前同屏识别时效、资格和资金约束 | 四类事实各自授权，固定版按一次有界批读返回可用摘要。活动与预算版本不一致或快照到期时给明确状态，发布资格由原命令再次判定 |
| 有效售卖、库存预警和渠道价格对照 | 发现可用量不足、有库存未售或异常渠道价 | 确认门店与渠道。建立 SKU 批量只读摘要、阈值负责人和可证实的库存事实。单一页的 SKU 行数无法代表全店库存或周转天数 |
| 可比较的营销转换与成本 | 判断已观察到的投放结果是否值得复盘 | 需同分母的成熟样本、唯一权益或券实例、按渠道约定归因。利润需货品、支付与渠道成本等数据，因果增量需可信对照或实验。业务收益与优惠出资分别定义 |

## 布局、交互与数据归属

- 复用当前 React、TypeScript、Vite、Ant Design 和同一套 `theme.ts`/工作台样式。旧版沿用 hash 路由，中央沿用 `catalog.json` 与本人授权交集。主题采用当前工作台色板、14px 正文和 32px 紧凑控件。数字右对齐且可比较，真实商品图来自现有媒体字段及权限。
- B 端整体为「范围条 → 当前作业 → 判断事实 → 操作或详情」。只有能导向核对或处置的数据进入首屏。默认排序固定为实体首列、关键状态、金额/数量、时间与低频动作。窄屏结果用一个可读主单元给名称/编号、状态和关键值。次列保留表格区域可横滚与键盘提示，页面级横向溢出作为缺陷检查。
- 存在消费任务、较简单对象状态对应短确认或只读居中弹窗。列表行快速查看保留现有容器与返回焦点。订单完整证据、版本配置和长期运行排查适合独立路由，需分享时用已允许的原对象参数，给清楚的返回方向。审批事实靠近动作，字典/礼券/发货用各自短表单。单一原记录的长历史在真正需要纵向作业时才进入独立深链。
- 状态归属由 `useRouteState`、`useResource`、现有专项客户端、Ant Design Form 分别承担。URL 恢复有效查询和游标，分页语义沿原端点。账号、组织、环境与工作门店改变时，重新加载授权并取消旧请求，旧行选择和旧对象动作全部作废。页面私有表单输入仅在安全范围内按原离开保护与恢复能力保留。
- 已应用与草稿条件分开显示。改过滤条件后重置游标，列表返回恢复原有效条件、位置和焦点。只提供接口可以落实的条件，显示“本批”与可信总数的区别。切换失败或撤权时更新可读提示与可用动作，旧数据不得参与新的范围判断。
- 查询分别显示加载、已核对为零、筛选无结果、未覆盖、401 会话、403 无权、5xx 失败重读。命令显示校验、接受/持久化结果、冲突和未知。后续核对沿用原对象和原幂等意图。认可发布、发券或导出任务，需看到实际持久状态和回执；权限不足、部分失败及资金待核对各有独立反馈。

## 先后顺序与验收目标

| 批次 | 实施对象和可交付结果 | 观察结果 |
| --- | --- | --- |
| P1·经营首页与交易 | L01/C01 与 C28/C30–C32、L22–L25 优先。调整范围条、异常读、主列和回到原记录，采用现有接口与现有权限 | 冷启动从总览进入同店待办；要找到原订单、判断未知并返回列表。对照同条件请求、授权拒绝、无结果、503，记录点击和用时基线 |
| P1·营销商业试点 | C14–C17、C20、C26–C27 和对应 L 页。先做按版读、同屏成本与已观察结果、实际批次去向 | 运营能回答对哪些人、当前可用钱、发放执行和观察结果；用过期快照、版本不一致、部分失败及投影缺失验证 |
| P1·商品与会员高频 | L07/L08/C02/C05、L02/C06/C09 与会员结算/订单/售后；适用的 SKU 批量摘要、会员上下文与只写岗位 | 同名规格有原编号，双查询各自生效，收货能核原 SKU。会员换对象清掉旧游标，订单报价以服务端为准 |
| P2·其他目录、配置与平台 | 按 [逐页清单](PAGE_DESIGN.md) 改善草稿、固定版本、短表单、运行详情、导出和历史任务。依风险和资源逐个 Owner 交付 | 覆盖原角色与低风险/高风险操作；用实际负例复核读写权限、结果、已受理与回读 |
| 契约与治理批次 | 导出快照清理、真实渠道、经营日与告警阈值、批量摘要、趋势及可比较分母 | 每项先以权威口径、真实权限、稳定分页和容量证明，再安排页面呈现。独立签收以隔离与正式凭据状态为准 |

首批代表用例覆盖店长定位一条关单异常、售后审查已发货申请、营销人员核对候选发布版、财务解释某活动版的净收、商品岗对照一个规格的售卖与库存。对每条任务收集首次定位用时、跨页次数、错误对象数和是否能在返回后继续，先在同岗位同样本建立基线，再设定改善目标。收益预期是缩短找原单和复核配置的路径，避免把投影空白、失败、预占和未知当成确定结果。没有实际操作记录时，指标只作为试点计划。

视觉及行为复测代表视口为 1440、1024、390 与 320px。逐页以 [清单](PAGE_DESIGN.md) 核对主操作、宽表、长名称、当前范围、实际结果和键盘焦点。写入仅在获准隔离数据中进行。首批页面通过后再推广到相同组件消费者，清单中的身份和写入差异各自验收。
