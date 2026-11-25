# 逐页运营体验审计

“实测”指本地 8602 读取或页面交互，“确认”指源码可直接证明，“潜在”指尚需数据或岗位复验，“建议”指拟建设或业务待确认。角色为作业视角，正式授权仍由项目权限决定。页面逐条包含当前用途、用户影响、证据、建议与验收，每个目录的字段顺序、过滤与次级列表见 [字段与查询](03-table-and-filter-optimization.md)。

## 导航、登录和角色边界

| 页面/变体 | 当前与影响 | 方案、优先级和验收 |
| --- | --- | --- |
| `/` 旧凭据登录、恢复、无权限页 | 单标签会话，刷新以 `/v1/me` 或平台身份核验，ADMIN 29 个静态菜单，OPERATOR 只有商品，会员7项。查找可用全局搜索和分组，岗位粒度以中央身份为准。 | P2/T1。区分凭据失效和服务不可用。按现有角色用深链恢复，401 清会话，范围切换取消旧响应。 [旧壳层](../../../frontend/src/app/App.tsx#L154)。 |
| `/#skus` 门店运营变体 | 同一商品组件，单功能导航，只能经营获准的店。平台身份、中央商品协作是独立入口。 | P1/T1。说明可见店及作用范围，门店撤权后旧选择和草稿不保留，列表与命令仍各自核权。 [角色分支](../../../frontend/src/app/App.tsx#L472)。 |
| `/operations` 中央组织入口 | 前端声明组织入口并按本人菜单挑首个获准页，当前同源直接 GET 401，固定页面映射和安全白名单均缺此路径。 | P1/T2，使用同源 HTTP 路由补齐，涉及后端页面壳映射和精确 GET 白名单。验收匿名只拿到登录壳、授权后只进本人可用菜单、业务 API 匿名仍拒绝。 [入口](../../../frontend/src/iam/navigation.ts#L6)、[映射](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralPageController.java#L10)、[白名单](../../../commerce-app/src/main/java/com/lrj/commerce/app/configuration/security/SecurityConfiguration.java#L36)。 |
| `/iam/callback`、中央会话与无菜单提示 | 固定回跳允许列表，清除一次性授权码，按组织与可见导航限制挂页。当前制品展示 IAM 未启用。 | P2/T1。缺组织时给可操作入口，分清无授权、授权服务不可用及旧页恢复。用真实 PKCE、失效和换组织验证。 [会话](../../../frontend/src/iam/session.ts#L16)、[中央壳](../../../frontend/src/iam/CentralProducts.tsx#L253)。 |

## 逐页记录

<a id="l01"></a>
### L01 经营总览 · `/#dashboard`

- **角色与目标**　经营负责人，看门店成交趋势并发现待办。
- **当前**　门店成交、租户会员、商品和日明细，支持指标切换。本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察。 [Dashboard](../../../frontend/src/features/Dashboard.tsx#L60)、[Dashboard](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/DashboardController.java#L16)。
- **问题及影响**　会员总数与门店成交作用范围不同，快捷入口以功能介绍为主。
- **改造及验收（P1/T2）**　卡片旁写作用范围和投影时点，平台来源有权限才给售后、履约、发券待办和同范围下钻。选门店后成交跳转携带同范围和日期，会员卡明确租户，无来源权限不展示计数。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l01)。
- **本页动作或表单证据**　[刷新总览](../../../frontend/src/features/Dashboard.tsx#L88)、[查看会员档案](../../../frontend/src/features/Dashboard.tsx#L247)、[进入营销效果分析](../../../frontend/src/features/Dashboard.tsx#L277)、[查看事件处理](../../../frontend/src/features/Dashboard.tsx#L280)。

<a id="l02"></a>
### L02 会员档案 · `/#members`

- **角色与目标**　会员运营，查人、判断状态、编辑档案。
- **当前**　姓名、认证主体、等级、状态，行详情可编辑、变更状态与查历史。本地桌面 · 业务 GET 已观察 · 首个可见表 1 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Commerce](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java#L17)。
- **问题及影响**　档案与成长操作分散，需重新定位会员，原始认证主体常驻。
- **改造及验收（P1/T1）**　姓名和会员编号主单元，认证主体受限后移，详情提供带同一会员的成长与行为入口。按名称查到会员，从详情带入同一会员，返回保留筛选，冻结操作仍要求实际资格。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l02)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l03"></a>
### L03 会员成长 · `/#growth`

- **角色与目标**　会员运营，判断等级、行为、积分和周期权益。
- **当前**　手工选择会员，七个经营区，成长调整、政策、标签、行为、积分、兑换及周期互通。本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察。 [MemberGrowth](../../../frontend/src/features/MemberGrowth.tsx#L62)、[MemberGrowth](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java#L13)、[MemberOperations](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/MemberOperationsController.java#L11)、[MemberCycle](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/cycle/MemberCycleController.java#L11)、[MemberPoints](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/points/MemberPointsController.java#L11)、[PointOffer](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/pointoffer/PointOfferController.java#L12)、[MemberBehavior](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/behavior/MemberBehaviorController.java#L17)。
- **问题及影响**　首屏没有当前会员摘要，等级政策与会员处置并列，手工输入易带错对象。
- **改造及验收（P1/T2）**　先完成选人和会员摘要，按问题进入各工作区，成长调整显示旧余额、增量与预计值，经服务端回读。切人清空账本游标和未确认选择。政策页保留版本语义，调整回执区分预测与持久余额。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l03)。
- **本页动作或表单证据**　[刷新账本](../../../frontend/src/features/MemberGrowth.tsx#L204)、[最早记录](../../../frontend/src/features/MemberGrowth.tsx#L230)、[下一页](../../../frontend/src/features/MemberGrowth.tsx#L236)、[标签首页](../../../frontend/src/features/MemberGrowth.tsx#L331)、[下一页标签](../../../frontend/src/features/MemberGrowth.tsx#L337)。

<a id="l04"></a>
### L04 会员标签字典 · `/#member-tags`

- **角色与目标**　会员运营，查看和建立标签。
- **当前**　字典创建、编号名称搜索，会员关联在成长页。本地桌面 · 业务 GET 已观察 · 首个可见表 0 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[MemberOperations](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/MemberOperationsController.java#L11)、[TagActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/TagActionsController.java#L14)。
- **问题及影响**　字典和关联入口语义分散，标签编号复用容易记错。
- **改造及验收（P2/T1）**　名称加可复制编号，说明定义与会员关联区别，使用范围等有依据后展示。同名标签按编号确认，创建后显示实际定义，无使用量数据时留空。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l04)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l05"></a>
### L05 动态人群 · `/#segments`

- **角色与目标**　人群运营，建立和刷新可复用的动态人群。
- **当前**　规则编辑、刷新、定时、运行历史和重试，显示成员命中、快照、入组事件。本地桌面 · 业务 GET 已观察 · 首个可见表 0 行。 [Segments](../../../frontend/src/features/Segments.tsx#L54)、[Segment](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/segment/SegmentController.java#L12)、[SegmentActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/segment/SegmentActionsController.java#L14)。
- **问题及影响**　目录缺当前输出新鲜度，定义、调度与每次运行版本要阅读多个区。
- **改造及验收（P1/T2）**　在人群卡上显示最近可用快照及观测时点，详情区分定义、周期、运行与公告。失败或过期时保留最后可用快照并标明，重试定位原运行，显示等待与已发布。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l05)。
- **本页动作或表单证据**　[刷新列表](../../../frontend/src/features/Segments.tsx#L86)、[发布人群定义](../../../frontend/src/features/Segments.tsx#L87)、[任务与受众版本](../../../frontend/src/features/Segments.tsx#L168)、[复制为新定义](../../../frontend/src/features/Segments.tsx#L177)、[刷新进度](../../../frontend/src/features/Segments.tsx#L217)。

<a id="l06"></a>
### L06 人群快照 · `/#audiences`

- **角色与目标**　营销运营，选定一版成员快照。
- **当前**　按编号/名称/来源查，粘贴会员编号创建，显示人数和截止。本地桌面 · 业务 GET 已观察 · 首个可见表 1 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[MarketingAsset](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java#L15)、[AudienceActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/AudienceActionsController.java#L14)。
- **问题及影响**　缺来源水位及明确过期状态，复制成员不等于新建会员。
- **改造及验收（P1/T2）**　加数据时点与过期标识，导入前显示去重后数量及未知会员数，服务端以实际结果为准。到期快照能查历史且选择时注明不可当作当前资格，重复号显示核对结果。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l06)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l07"></a>
### L07 商品管理 · `/#skus`

- **角色与目标**　商品运营，维护销售规格、SPU、类目模板和计划。
- **当前**　规格关键词/条码、类目价格状态筛选、调价、渠道价、条码、批量和历史。本地桌面 · 业务 GET 已观察 · 首个可见表 6 行。 [ProductOperations](../../../frontend/src/features/ProductOperations.tsx#L83)、[ProductOperations](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/product/ProductOperationsController.java#L12)、[CatalogScheduling](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/job/CatalogSchedulingController.java#L13)、[CatalogMerchandising](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java#L11)。
- **问题及影响**　全局商品资料搜索与默认规格筛选同时显示，两个独立请求，容易误认筛选作用于规格。
- **证据细节**　本机 6 条 SKU 在上方搜索无匹配值后仍有 6 条，下方发送同关键词立即筛为 0 条。两次不同 API 请求见 [路径复现](04-business-workflow-optimization.md)。
- **改造及验收（P1/T1）**　把 SPU 查询移到 SPU 页签内，SKU 首列商品/规格/条码摘要，变更预览展示范围和旧新值。SPU 查询不在 SKU 页签可见，筛 SKU 按服务端结果并清空旧批量选择，历史报价仍冻结。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l07)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/ProductOperations.tsx#L170)、[渠道价](../../../frontend/src/features/ProductOperations.tsx#L291)、[条码资料](../../../frontend/src/features/ProductOperations.tsx#L294)、[修订记录](../../../frontend/src/features/ProductOperations.tsx#L297)、[展示资料](../../../frontend/src/features/ProductOperations.tsx#L360)。

<a id="l08"></a>
### L08 库存额度 · `/#inventory`

- **角色与目标**　仓配运营，查库存并登记收货。
- **当前**　指定门店，可售、预占、已售、版本，以及手填 SKU 与数量的增量入库。本地桌面 · 业务 GET 已观察 · 首个可见表 6 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Order](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/order/OrderController.java#L13)、[InventoryActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/order/InventoryActionsController.java#L13)。
- **问题及影响**　只见规格编号，最新库存和入库动作难核对对象和变化量。
- **改造及验收（P1/T2）**　按行带 SKU 门店，表首列用授权商品名+规格，确认展示当前、增加量与预计，回执展示实际。输入无效 SKU 或超范围失败，收货回执返回实际变化，支付未知保留结算相关占用，已收回实物按售后规则独立入库。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l08)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l09"></a>
### L09 商家管理 · `/#merchants`

- **角色与目标**　商家运营，查商家并找下属门店。
- **当前**　编号及名称查询、状态、创建和行详情。本地桌面 · 业务 GET 已观察 · 首个可见表 1 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Commerce](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java#L17)、[DirectoryActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/DirectoryActionsController.java#L14)。
- **问题及影响**　需复制编号到门店查询，缺实际授权门店数量。
- **改造及验收（P2/T1）**　提供带已验证商家范围的门店入口，名称和编号合并，数量等 Owner 可聚合再出。从商家进入门店只携带该商家范围，返回保留原列表。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l09)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l10"></a>
### L10 店铺管理 · `/#stores`

- **角色与目标**　门店运营，按归属查店及建立门店。
- **当前**　店名、店号、所属商家编号、状态，创建后刷新选店。本地桌面 · 业务 GET 已观察 · 首个可见表 1 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Commerce](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java#L17)、[DirectoryActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/DirectoryActionsController.java#L14)。
- **问题及影响**　商家只有 ID，创建门店手工填归属，店铺页缺商品、库存的当前上下文入口。
- **改造及验收（P1/T2）**　显示授权商家名及 ID，选择器先校验归属，查看店铺商品自动携带范围。同名门店仍能核对商家及编号，无商家目录权可按准许的精确编号提交。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l10)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l11"></a>
### L11 经营授权 · `/#store-grants`

- **角色与目标**　商家管理员，授予或关闭门店商品经营权限。
- **当前**　人和资源编号、范围、启用、版本、原因，商家级覆盖未来门店。本地桌面 · 业务 GET 已观察 · 首个可见表 0 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[StoreAccess](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/store/StoreAccessController.java#L15)。
- **问题及影响**　影响范围文本位置离确认较远，资源编号难核对。
- **改造及验收（P1/T2）**　重点区分单门店与商家范围，预览真实可见店数、未来门店规则和受授人，核对撤销回执。商家范围确认明确未来店铺，授权记录不显示资金权限，越权拒绝。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l11)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l12"></a>
### L12 活动管理 · `/#campaigns`

- **角色与目标**　营销运营与审核，草拟、预览、审核、发布活动。
- **当前**　列表含门槛、优惠和版本，详情内预览、审批发布与暂停。本地桌面 · 业务 GET 已观察 · 首个可见表 2 行。 [Marketing](../../../frontend/src/features/Marketing.tsx#L30)、[Commerce](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java#L17)、[CampaignActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/CampaignActionsController.java#L14)。
- **问题及影响**　活动、预算、优惠关联需另查，预览原价和应付缺对照预算、版本锁。
- **改造及验收（P1/T2）**　加所选版本的预算和绑定资产链接，发布前区分模拟判定、最终生效窗口与预算状态。详情打开确认实际内容版本，预算下钻保持同版，预览可读但不会保存草稿。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l12)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/Marketing.tsx#L64)、[新建规则](../../../frontend/src/features/Marketing.tsx#L66)、[查看配置](../../../frontend/src/features/Marketing.tsx#L216)、[保存规则](../../../frontend/src/features/Marketing.tsx#L279)。

<a id="l13"></a>
### L13 营销效果 · `/#effects`

- **角色与目标**　营销分析，核对活动、旅程和券效果。
- **当前**　按店与时间看订单、退款和净收，有旅程、券批次比较及有界补齐。本地桌面 · 业务 GET 已观察 · 首个可见表 0 行。 [MarketingEffects](../../../frontend/src/features/MarketingEffects.tsx#L45)、[MarketingEffects](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/insight/MarketingEffectsController.java#L13)、[Journey](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java#L12)。
- **问题及影响**　UTC下单、入组与批次起点放在一起，运营难确认窗口，部分补齐可能被当成全量。
- **改造及验收（P1/T1）**　统一顶部范围摘要，页签用各自事件起点，强调覆盖和最后核对，保留历史补齐进度说明。同一页签显示所用时间基准，不能以回填受理充当数据完整。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l13)。
- **本页动作或表单证据**　[刷新结果](../../../frontend/src/features/MarketingEffects.tsx#L76)、[查询（最多93天）](../../../frontend/src/features/MarketingEffects.tsx#L102)、[回到首页](../../../frontend/src/features/MarketingEffects.tsx#L175)、[下一页](../../../frontend/src/features/MarketingEffects.tsx#L178)、[回到首页](../../../frontend/src/features/MarketingEffects.tsx#L233)。

<a id="l14"></a>
### L14 动态规则 · `/#rules`

- **角色与目标**　营销规则配置，配置资格规则并发布指定版。
- **当前**　规则名称、内容版本、状态、字段条件编辑、发布。本地桌面 · 业务 GET 已观察 · 首个可见表 1 行。 [Marketing](../../../frontend/src/features/Marketing.tsx#L30)、[MarketingAsset](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java#L15)、[RuleActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/RuleActionsController.java#L13)。
- **问题及影响**　技术字段和组合逻辑对运营解释不足，发布时缺引用对象摘要。
- **改造及验收（P2/T2）**　以业务句解释 AND/OR 与空值，表结构化预览，引用查询待 Owner 与权限设计。管理者能在发布前正确复述匹配条件，原内容版本不被覆盖。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l14)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/Marketing.tsx#L64)、[新建规则](../../../frontend/src/features/Marketing.tsx#L66)、[查看配置](../../../frontend/src/features/Marketing.tsx#L216)、[保存规则](../../../frontend/src/features/Marketing.tsx#L279)。

<a id="l15"></a>
### L15 营销预算 · `/#budgets`

- **角色与目标**　营销财务，核对活动版本的资金占用。
- **当前**　活动编号和版本、上限、预占、已消耗，只读。本地桌面 · 业务 GET 已观察 · 首个可见表 2 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[MarketingAsset](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java#L15)。
- **问题及影响**　缺可用金额、活动名称和资金语义提示在行内，无法按异常快速定位。
- **改造及验收（P1/T2）**　联合只读 Owner 快照输出余额，解释退款不回补，带版本返回活动。上限等式以服务端精确金额核对，如投影延迟明确时点，不会因退款自动加预算。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l15)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l16"></a>
### L16 优惠券 · `/#coupons`

- **角色与目标**　券运营，查发行额度与配置定义。
- **当前**　券名、定义、门槛、优惠、上限和已领，创建含有效期、叠加、发行方式与承担比例。本地桌面 · 业务 GET 已观察 · 首个可见表 1 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Coupon](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/coupon/CouponController.java#L12)、[CouponDefinitionActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/coupon/CouponDefinitionActionsController.java#L14)。
- **问题及影响**　目录缺有效期与发行方式，容易选错定向券。
- **改造及验收（P1/T1）**　显示发行方式、窗口和已发/上限，承担比例在配置确认与详情展开。同名两版通过门店/定义版本区分，未授权读者无法从选券间接看到其它门店。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l16)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l17"></a>
### L17 定向发券 · `/#coupon-deliveries`

- **角色与目标**　券运营，发放并核对批次与逐人结果。
- **当前**　选择受控券和快照，批次含处理、发放、跳过、撤销、截止与错误，支持停止和撤销。本地桌面 · 业务 GET 已观察 · 首个可见表 0 行。 [CouponDeliveries](../../../frontend/src/features/CouponDeliveries.tsx#L58)、[CouponDelivery](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/delivery/CouponDeliveryController.java#L12)、[CouponDeliveryActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/delivery/CouponDeliveryActionsController.java#L14)。
- **问题及影响**　停发和撤券已经区分，但名单预期、券成本上限、作用门店需要分开核对。
- **改造及验收（P1/T2）**　执行前同屏显示资格、固定版本和人数上限，结果按真实原因下钻。停止后未发不再发，撤券显示已用占用等保留，受理与最终发放分别展示。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l17)。
- **本页动作或表单证据**　[刷新批次](../../../frontend/src/features/CouponDeliveries.tsx#L121)、[发券回执](../../../frontend/src/features/CouponDeliveries.tsx#L253)、[刷新回执](../../../frontend/src/features/CouponDeliveries.tsx#L310)、[回执首页](../../../frontend/src/features/CouponDeliveries.tsx#L347)、[下一页收件人](../../../frontend/src/features/CouponDeliveries.tsx#L353)。

<a id="l18"></a>
### L18 权益定义 · `/#definitions`

- **角色与目标**　权益运营，配置内部权益及额度。
- **当前**　权益、编号、每份单位、额度、预占、已发行，新增带日期。本地桌面 · 业务 GET 已观察 · 首个可见表 1 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Entitlement](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java#L12)、[EntitlementActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementActionsController.java#L13)。
- **问题及影响**　有效期和定义版本不在默认主单元，单位可能被当成金额。
- **改造及验收（P2/T1）**　明确“单位数”和“发放后有效天数”，补版本和窗口作为辅助信息。同名两定义可区分，空有效期、尚未生效与已过期可以辨认。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l18)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l19"></a>
### L19 权益台账 · `/#entitlements`

- **角色与目标**　权益支持，排查发放、消费和退款补偿。
- **当前**　按权益订单会员查询，看到剩余与待补偿，行详情可处置。本地桌面 · 业务 GET 已观察 · 首个可见表 2 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Entitlement](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java#L12)、[EntitlementActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementActionsController.java#L13)。
- **问题及影响**　来源订单只是编号，来自活动/兑换/周期时路径不明显。
- **改造及验收（P1/T1）**　来源类型与业务编号并列，补偿处置前显示权益单位、实际原因及权限。订单型、兑换型和周期型均显示真实来源，无目标权限时仅显示允许显示的编号。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l19)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l20"></a>
### L20 营销旅程 · `/#journeys`

- **角色与目标**　旅程运营与审核，编排、发布和检查旅程。
- **当前**　定义列表、流程图、固定版本、复杂节点表单、频控、扫描子台。本地桌面 · 业务 GET 已观察 · 首个可见表 2 行。 [Journeys](../../../frontend/src/features/Journeys.tsx#L280)、[Journey](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java#L12)、[JourneyAccess](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyAccessController.java#L13)。
- **问题及影响**　触发、版本状态和扫描职责集中，节点 JSON 或标识类参数需要校验和举例。
- **改造及验收（P1/T2）**　保持图用于预览，编辑按触发、资格、节点、频控分组，从版本直达该版本扫描或实例。暂停影响未来入组的语义清楚，已有实例仍能追踪原版本，草稿与发布动作分开。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l20)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/Journeys.tsx#L350)、[创建旅程](../../../frontend/src/features/Journeys.tsx#L351)、[查看节点](../../../frontend/src/features/Journeys.tsx#L407)、[创建新版本](../../../frontend/src/features/Journeys.tsx#L417)、[保存旅程草稿](../../../frontend/src/features/Journeys.tsx#L487)。

<a id="l21"></a>
### L21 旅程实例 · `/#instances`

- **角色与目标**　营销运行支持，追踪某人为何未完成。
- **当前**　显示旅程、会员、当前节点、状态、到期和重试，支持有权限的处置。本地桌面 · 业务 GET 已观察 · 首个可见表 3 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Journey](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java#L12)、[JourneyAccess](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyAccessController.java#L13)。
- **问题及影响**　状态与实际固定版/失败原因分离，易反查错版。
- **改造及验收（P1/T2）**　主列合并实例和固定版本，详情提供节点与历史结果，工单引用实际实例。取消只影响后续，已提交副作用仍显示，失败能定位到最近节点或覆盖缺失。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l21)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l22"></a>
### L22 订单工作台 · `/#orders`

- **角色与目标**　交易运营，查订单、支付与交易状态。
- **当前**　列表后有快捷和完整详情、商品行、付款方式、支付核对。本地桌面 · 业务 GET 已观察 · 首个可见表 5 行。 [Orders](../../../frontend/src/features/Orders.tsx#L43)、[Console](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java#L16)、[Order](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/order/OrderController.java#L13)。
- **问题及影响**　壳层门店切换不进订单查询，店铺列只显示 ID，支付多一次展开，缺售后及履约关联。
- **证据细节**　本机按原编号精确命中 1 条，进入详情、查询支付并返回后关键词保留。列表 GET 始终没有 `storeId`，两店后果待确认。 [只读复现](04-business-workflow-optimization.md)。
- **改造及验收（P1/T2）**　先明确租户/门店实际范围，加真实店名和已授权来源状态，完整详情作为查询与下钻入口。多店环境按明确范围查询，详情返回保留条件和焦点，权限不足不泄露渠道。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l22)。
- **本页动作或表单证据**　[返回订单列表](../../../frontend/src/features/Orders.tsx#L114)、[查看详情](../../../frontend/src/features/Orders.tsx#L195)、[完整详情](../../../frontend/src/features/Orders.tsx#L198)、[打开完整详情](../../../frontend/src/features/Orders.tsx#L219)。

<a id="l23"></a>
### L23 履约队列 · `/#fulfillments`

- **角色与目标**　仓配运营，检查拣发与售后阻拦。
- **当前**　订单、进度、运单、阻拦、渠道，行内发货签收。本地桌面 · 业务 GET 已观察 · 首个可见表 4 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Aftersale](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java#L14)。
- **问题及影响**　阻拦只描述布尔，不能按阻拦筛选，上下文不足导致回订单核对。
- **改造及验收（P1/T2）**　突出阻拦并提供原售后/订单带条件链接，给待发和被拦两个快捷视图。存在阻拦时不可执行发货且显示可用排查入口，权限不足不展示源对象。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l23)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l24"></a>
### L24 售后审批 · `/#aftersales`

- **角色与目标**　售后运营，收件、审核申请和跟退款。
- **当前**　申请、订单、会员、退款金额与编号、需退货和状态，条件操作。本地桌面 · 业务 GET 已观察 · 首个可见表 2 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Aftersale](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java#L14)。
- **问题及影响**　列表没有申请时间/优先级，详情旧路径来自列表行，查退款与订单需手动带号。
- **证据细节**　已查看已完成申请详情。弹层标题、主卡、关联记录多处重复同一申请号，订单号只作为文本，首屏缺退货状态以外的可执行关联。 [行操作](../../../frontend/src/features/AdminData.tsx#L438)。
- **改造及验收（P1/T2）**　区分待审批、待收件和退款核对，详情分申请、商品行、退款，审批预览标明后果。未发货和已发货两种结果文案不同，刷新后识别原退款，有权才能下钻。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l24)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l25"></a>
### L25 退款核对 · `/#refunds`

- **角色与目标**　财务售后，按渠道核对原退款结果。
- **当前**　退款、订单、金额、渠道和状态，支持重新核对/沙箱事实条件入口。本地桌面 · 业务 GET 已观察 · 首个可见表 2 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Aftersale](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java#L14)。
- **问题及影响**　申请编号/最近核对时点不明显，未知与失败潜在误读。
- **改造及验收（P1/T2）**　未知以“待渠道核对”显示，补原售后、最近核对与原因，沙箱视觉与正式业务隔离。UNKNOWN 核对前后保留原退款和金额，成功证据推动售后状态，无自动重新退款按钮。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l25)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l26"></a>
### L26 低代码页面 · `/#pages`

- **角色与目标**　内容运营与审核，配置带真实数据表格、审批发布回退。
- **当前**　最新版本、组件数、草稿、预览/发布页、历史、白名单命令。本地桌面 · 业务 GET 已观察 · 首个可见表 1 行。 [OpsPages](../../../frontend/src/features/OpsPages.tsx#L253)、[OpsPage](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OpsPageController.java#L12)。
- **问题及影响**　配置编辑多结构化字段，预览、发布、页面命令容易混淆，旧版本失去目标上下文。
- **改造及验收（P1/T2）**　版面编辑显示真实组件说明和操作清单，历史差异和发布窗口同屏。预览访问权限与页面操作权限分开，回退选择明确原版本，不改变业务对象。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l26)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/OpsPages.tsx#L278)、[创建运营页面](../../../frontend/src/features/OpsPages.tsx#L279)、[打开 / 版本](../../../frontend/src/features/OpsPages.tsx#L328)、[创建新版本](../../../frontend/src/features/OpsPages.tsx#L334)、[预览真实数据](../../../frontend/src/features/OpsPages.tsx#L394)。

<a id="l27"></a>
### L27 异步事件 · `/#events`

- **角色与目标**　运行运营，查找堆积、隔离、业务对象并按权限推进。
- **当前**　事件类型、业务编号、状态、失败次数及待投递时间。本地桌面 · 业务 GET 已观察 · 首个可见表 24 行。 [AdminData](../../../frontend/src/features/AdminData.tsx#L408)、[Console](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java#L16)、[OperationsAccess](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OperationsAccessController.java#L12)。
- **问题及影响**　技术类型无法直接表达业务影响，原因与重试事实分散。
- **改造及验收（P1/T2）**　事件编码保留给审计，按授权显示订单/旅程等业务类型、可处置状态与下一投递。自动重试、隔离、人工重启分别展示，记录失败原因和回执，按业务编号可查到。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l27)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/AdminData.tsx#L591)。

<a id="l28"></a>
### L28 任务恢复 · `/#recovery`

- **角色与目标**　平台值班，查停工队列与按原任务处置。
- **当前**　工作类型、停工明细、最多50项的目标和结果、恢复审计。本地桌面 · 业务 GET 已观察 · 首个可见表 0 行。 [Recovery](../../../frontend/src/features/RuntimeOperations.tsx#L180)、[RuntimeRecovery](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java#L15)。
- **问题及影响**　恢复跨类型对象的副作用与资格需要查看详情或凭经验理解，结果仍需回查。
- **改造及验收（P1/T2）**　确认只展示当前类型、目标、失败原因及资格，结果提供原作业链接和拒绝原因。每条获得独立接受/拒绝回执，重复提交核对旧回执，删除资格后不扩大范围。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l28)。
- **本页动作或表单证据**　[刷新工作](../../../frontend/src/features/RuntimeOperations.tsx#L228)、[查看证据](../../../frontend/src/features/RuntimeOperations.tsx#L348)、[确认恢复操作](../../../frontend/src/features/RuntimeOperations.tsx#L416)、[原样重试恢复](../../../frontend/src/features/RuntimeOperations.tsx#L431)。

<a id="l29"></a>
### L29 历史重放 · `/#replay`

- **角色与目标**　平台值班，检查试运行、创建、控制和追踪历史重放。
- **当前**　目录、试运行、消费者安全矩阵、范围事件与控制。本地桌面 · 业务 GET 已观察 · 首个可见表 0 行。 [Replay](../../../frontend/src/features/RuntimeOperations.tsx#L566)、[RuntimeRecovery](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java#L15)。
- **问题及影响**　技术概念多，容易混淆历史补齐与重复执行，试运行结果应锁定同预估范围。
- **改造及验收（P1/T2）**　先选目标再显示安全矩阵与范围，创建前逐项核对原试运行指纹，任务记录可追溯。变更范围使预估失效，不支持重复执行的消费者不能在 UI 或服务端允许。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#l29)。
- **本页动作或表单证据**　[刷新任务](../../../frontend/src/features/RuntimeOperations.tsx#L651)、[查看任务](../../../frontend/src/features/RuntimeOperations.tsx#L707)、[验证范围与安全门](../../../frontend/src/features/RuntimeOperations.tsx#L849)、[创建重放任务](../../../frontend/src/features/RuntimeOperations.tsx#L952)、[原样重试创建](../../../frontend/src/features/RuntimeOperations.tsx#L966)。

<a id="p01"></a>
### P01 运行健康 · `/#platform-runtime`

- **角色与目标**　平台运维，查看运行车道与数据清理健康。
- **当前**　只读工作队列、依赖、重放和保留指标。源码 · 平台身份待实测。 [PlatformRuntime](../../../frontend/src/features/PlatformRuntime.tsx#L67)、[PlatformRuntime](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/health/PlatformRuntimeController.java#L11)。
- **问题及影响**　量纲和告警处置需领域知识，不应用租户工具替代平台级监控。
- **改造及验收（P2/T1）**　把问题车道和影响时间置前，指标含单位及采集时点，受权限约束的处置手册引用。没有读权限不显示平台指标，依赖 UNKNOWN 与正常为零区分。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#p01)。

<a id="m01"></a>
### M01 逛店铺 · `/#shop`

- **角色与目标**　会员买家，找商品、报价并进入订单。
- **当前**　卡片、门店、类目、名称条码、价区间、详情、购物袋和真实报价。本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察。 [Shop](../../../frontend/src/features/Shop.tsx#L34)、[Commerce](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java#L17)、[ProductOperations](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/product/ProductOperationsController.java#L12)、[CheckoutPilotQuote](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CheckoutPilotQuoteController.java#L15)。
- **问题及影响**　买家看到的价格可能只是检索价，报价冻结、优惠与抵扣的解释需要靠近结算。
- **改造及验收（P1/T1）**　突出当前店和报价时间，解释目录价与应付，把不可用券和价格变化原因靠近输入。只读检查可从商品卡到详情与关闭，写入验收另用隔离环境，失效报价需重新取价。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#m01)。
- **本页动作或表单证据**　[购物袋 ·](../../../frontend/src/features/Shop.tsx#L131)、[返回店铺](../../../frontend/src/features/Shop.tsx#L147)、[加入购物袋](../../../frontend/src/features/Shop.tsx#L214)、[全部商品](../../../frontend/src/features/Shop.tsx#L247)、[加入购物袋](../../../frontend/src/features/Shop.tsx#L331)。

<a id="m02"></a>
### M02 我的订单 · `/#orders`

- **角色与目标**　会员买家，查本人订单、支付及申请售后。
- **当前**　订单列表、快捷/完整详情，按状态发起支付、取消和申请售后。本地桌面 · 业务 GET 已观察 · 首个可见表 5 行。 [Orders](../../../frontend/src/features/Orders.tsx#L43)、[Order](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/order/OrderController.java#L13)、[Payment](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java#L14)、[Aftersale](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java#L14)。
- **问题及影响**　主屏店铺仅编号，订单完成、支付中和售后进度分散。
- **改造及验收（P1/T2）**　显示店名和下一步，带单号直达本人售后，资金未明时给查询而不是重复下单。只能看到本人订单，取消后渠道未明显示处理中，返回保留筛选。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#m02)。
- **本页动作或表单证据**　[返回订单列表](../../../frontend/src/features/Orders.tsx#L114)、[查看详情](../../../frontend/src/features/Orders.tsx#L195)、[完整详情](../../../frontend/src/features/Orders.tsx#L198)、[打开完整详情](../../../frontend/src/features/Orders.tsx#L219)。

<a id="m03"></a>
### M03 优惠券中心 · `/#coupons`

- **角色与目标**　会员买家，领取、查现有券和可用条件。
- **当前**　领取优惠卡与本人钱包表，定义搜索、钱包关键词/状态和核销情形。本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察。 [MemberWallet](../../../frontend/src/features/MemberWallet.tsx#L23)、[Coupon](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/coupon/CouponController.java#L12)。
- **问题及影响**　同名券有效期和适用店需清楚，钱包状态容易混同活动下线。
- **改造及验收（P2/T1）**　按可用/已用等展示真实状态，优惠卡说明领取与使用期限，返回购物袋保留门店。同名券按窗口和适用店区分，预占与已用单独显示。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#m03)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/MemberWallet.tsx#L103)。

<a id="m04"></a>
### M04 我的权益 · `/#benefits`

- **角色与目标**　会员买家，看自己权益及剩余。
- **当前**　卡片展示权益和状态，按关键词/状态分批浏览。本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察。 [MemberWallet](../../../frontend/src/features/MemberWallet.tsx#L23)、[Entitlement](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java#L12)、[MemberBenefit](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/benefit/MemberBenefitController.java#L11)。
- **问题及影响**　来源、欠项与剩余易被误读成现金，过期和已用需区别。
- **改造及验收（P2/T1）**　卡面写剩余单位、到期与下一步，关联本人可读来源。过期、消耗、补偿准确展示单位，外部权益不被承诺。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#m04)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/MemberWallet.tsx#L103)。

<a id="m05"></a>
### M05 我的成长 · `/#growth`

- **角色与目标**　会员买家，看等级、积分、周期并兑换。
- **当前**　行为、积分、兑换、周期与成长流水，有会员可见的账户动作。本地桌面 · 业务 GET 已观察 · 首个可见表 0 行。 [MemberGrowth](../../../frontend/src/features/MemberGrowth.tsx#L62)、[MemberGrowth](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java#L13)、[MemberPoints](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/points/MemberPointsController.java#L11)、[MemberCycle](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/cycle/MemberCycleController.java#L11)、[PointOffer](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/pointoffer/PointOfferController.java#L12)、[MemberBehavior](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/behavior/MemberBehaviorController.java#L17)。
- **问题及影响**　显示多个相近余额与时间口径，积分冻结、待偿和到期解释分散。
- **改造及验收（P2/T1）**　摘要区分等级成长与积分，实际兑换结果、消费条件及周期到期突出。切换子区仍显示本人，抵扣百分比和积分余额清楚，兑换结果以回执为准。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#m05)。
- **本页动作或表单证据**　[刷新账本](../../../frontend/src/features/MemberGrowth.tsx#L204)、[最早记录](../../../frontend/src/features/MemberGrowth.tsx#L230)、[下一页](../../../frontend/src/features/MemberGrowth.tsx#L236)、[标签首页](../../../frontend/src/features/MemberGrowth.tsx#L331)、[下一页标签](../../../frontend/src/features/MemberGrowth.tsx#L337)。

<a id="m06"></a>
### M06 售后记录 · `/#aftersales`

- **角色与目标**　会员买家，跟踪本人申请、退款和返还积分。
- **当前**　订单/售后状态筛选，显示退款与名义返还积分。本地桌面 · 业务 GET 已观察 · 首个可见表 2 行。 [MemberWallet](../../../frontend/src/features/MemberWallet.tsx#L23)、[Aftersale](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java#L14)。
- **问题及影响**　两个返还概念需以渠道和积分结果区分，列表与订单分离。
- **改造及验收（P1/T1）**　给真实阶段和本人订单链接，注明名义返还与实际冲抵的边界。退款中不显示成功，返回本人原订单，空状态可说明从订单申请。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#m06)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/MemberWallet.tsx#L103)。

<a id="m07"></a>
### M07 站内消息 · `/#notifications`

- **角色与目标**　会员买家，查看旅程发送的站内消息。
- **当前**　消息卡和日期，游标翻页。本地桌面 · 业务 GET 已观察 · 首屏/卡片已观察。 [MemberWallet](../../../frontend/src/features/MemberWallet.tsx#L23)、[Journey](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java#L12)。
- **问题及影响**　查看消息与进入相关业务分离，目前查无已读状态契约。
- **改造及验收（P3/T2）**　仅显示有真实授权入口的相关订单/权益，标题时间和主题清晰，已读 另立需求。通知无业务引用时完整展示正文，异常目标跳转不泄漏他人对象。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#m07)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/features/MemberWallet.tsx#L103)。

<a id="c01"></a>
### C01 经营总览 · `/operations/dashboard`

- **角色与目标**　中央经营负责人，在组织授权内看经营指标。
- **当前**　包装旧总览，组合仪表盘、会员、商品、效果能力。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [CentralDashboard](../../../frontend/src/iam/CentralDashboard.tsx#L9)、[Dashboard](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/DashboardController.java#L16)。
- **问题及影响**　聚合是全有或全无且混合店/租户口径，导航菜单和可打开数据可能不同。
- **改造及验收（P1/T2）**　解释组合能力与范围，保留拒绝隔离，如需分卡只读须独立权限和契约。缺任一必要能力时不泄露其他数字，入口链接指向有效本人导航。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c01)。
- **本页动作或表单证据**　[查看总览](../../../frontend/src/iam/CentralDashboard.tsx#L35)。

<a id="c02"></a>
### C02 商品经营 · `/operations/catalog`

- **角色与目标**　中央商品运营，在已授权门店维护规格价格和结构。
- **当前**　中央门店范围加载后复用完整商品组件。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [CentralCatalog](../../../frontend/src/iam/CentralCatalog.tsx#L56)、[ProductOperations](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/product/ProductOperationsController.java#L12)、[CatalogScheduling](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/job/CatalogSchedulingController.java#L13)、[CatalogMerchandising](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/catalog/merchandising/CatalogMerchandisingController.java#L11)。
- **问题及影响**　与授权商品目录命名相近，同样存在全局 SPU 和 SKU 查询分离。
- **改造及验收（P1/T1）**　明确此处是 SKU/价格/计划，移 SPU 筛选到页签，两个入口使用真实权限交集。不同角色只出现获准操作，同款规格筛选仅影响规格列表。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c02)。
- **本页动作或表单证据**　[退出登录](../../../frontend/src/iam/CentralCatalog.tsx#L79)、[返回登录](../../../frontend/src/iam/CentralCatalog.tsx#L85)、[进入门店](../../../frontend/src/iam/CentralCatalog.tsx#L121)。

<a id="c03"></a>
### C03 授权商品 · `/operations/products`

- **角色与目标**　中央商品与外部协作管理员，跨获准门店查、改商品与导出。
- **当前**　名称搜索、总数、门店计数、详情、受控编辑及限时导出。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Products](../../../frontend/src/iam/CentralProducts.tsx#L435)、[CentralScope](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java#L12)。
- **问题及影响**　“商品经营”标题与 C02 相近，只按名称查，难快速定位同名跨店商品。
- **改造及验收（P1/T2）**　明确授权目录与全商品经营差别，增加门店和准确编号条件后保持查询与导出同一快照。选择同名两店时不混，撤权后失败关闭，归档可区分受理、可下载、失效。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c03)。
- **本页动作或表单证据**　[退出本应用](../../../frontend/src/iam/CentralProducts.tsx#L507)、[刷新商品](../../../frontend/src/iam/CentralProducts.tsx#L524)、[查看资料](../../../frontend/src/iam/CentralProducts.tsx#L557)、[回到首页](../../../frontend/src/iam/CentralProducts.tsx#L577)、[下一页](../../../frontend/src/iam/CentralProducts.tsx#L583)。

<a id="c04"></a>
### C04 商家与门店 · `/operations/directory`

- **角色与目标**　中央目录运营，按组织查找并创建商家、门店。
- **当前**　商家和门店双区，各有独立游标和创建权限。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [DirectorySection](../../../frontend/src/iam/CentralDirectory.tsx#L171)、[Commerce](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java#L17)、[DirectoryActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/DirectoryActionsController.java#L14)。
- **问题及影响**　两区复用同一查询 URL 键，有条件跨区污染的源码风险，无商家名称关联。
- **证据细节**　目录区的两个组件实例分别维护游标，却复用 `DirectorySection.query`。该状态键在 URL 中全局持有。岗位态待实测。 [筛选读写](../../../frontend/src/iam/CentralDirectory.tsx#L181)、[状态存储](../../../frontend/src/shared/listFilters.tsx#L230)。
- **改造及验收（P1/T1）**　按对象隔离查询键，商家到门店携带精确归属，保留只写岗位入口。商家搜索和门店搜索各自刷新恢复，编号相同内容不同仍可独立定位。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c04)。
- **本页动作或表单证据**　[重新核验权限与目录](../../../frontend/src/iam/CentralDirectory.tsx#L279)、[回到首页](../../../frontend/src/iam/CentralDirectory.tsx#L320)、[下一页](../../../frontend/src/iam/CentralDirectory.tsx#L326)。

<a id="c05"></a>
### C05 库存作业 · `/operations/inventory`

- **角色与目标**　中央仓配，查看真实库存并按权限增量入库。
- **当前**　按已授权店选择，SKU 关键词、库存列、受控收货。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Inventory](../../../frontend/src/iam/CentralInventory.tsx#L201)、[Order](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/order/OrderController.java#L13)、[InventoryActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/order/InventoryActionsController.java#L13)。
- **问题及影响**　行列和收货表单分离，SKU 需要人工回填，核对数量依赖记忆。
- **改造及验收（P1/T2）**　行内打开收货带入范围，显示真实 SKU 名与预计变化，保留独立收货岗位路径。跨店重填并重新核权，同时改库存时可看到真实回执。零和缺数区分。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c05)。
- **本页动作或表单证据**　[重新核验权限与库存](../../../frontend/src/iam/CentralInventory.tsx#L293)、[回到首页](../../../frontend/src/iam/CentralInventory.tsx#L330)、[下一页](../../../frontend/src/iam/CentralInventory.tsx#L336)。

<a id="c06"></a>
### C06 会员档案 · `/operations/members`

- **角色与目标**　中央会员运营，查会员与档案审核历史。
- **当前**　会员号、姓名、客户账号、等级、状态、版本，独立创建编辑与状态变更权限。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [MemberList](../../../frontend/src/iam/CentralMembers.tsx#L207)、[Commerce](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java#L17)、[MemberActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/MemberActionsController.java#L14)。
- **问题及影响**　用户身份与显示名都暴露在同一密度列表，与成长、标签仍依赖手工复制号。
- **改造及验收（P1/T2）**　姓名+会员号首列，账号辅助且按授权遮罩，详情加入同会员跨页入口。查档、编辑、冻结能力分离，未获列表权仍可用已获准的精确动作。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c06)。
- **本页动作或表单证据**　[刷新会员](../../../frontend/src/iam/CentralMembers.tsx#L222)、[查看变更记录](../../../frontend/src/iam/CentralMembers.tsx#L247)、[回到首页](../../../frontend/src/iam/CentralMembers.tsx#L263)、[下一页](../../../frontend/src/iam/CentralMembers.tsx#L269)。

<a id="c07"></a>
### C07 成长与等级 · `/operations/member-growth`

- **角色与目标**　中央会员运营，按会员核对成长，管理生效政策。
- **当前**　会员号读取、余额和流水，独立政策、调整或初始化。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [WalletDetails](../../../frontend/src/iam/CentralGrowth.tsx#L227)、[MemberGrowth](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/growth/MemberGrowthController.java#L13)、[GrowthActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/GrowthActionsController.java#L14)。
- **问题及影响**　主路径手填会员号，政策版和本次账本来源不便对照。
- **改造及验收（P1/T2）**　从会员深链定位，政策详情按版本跳转，调整确认旧值和变动并给真实收据。切人清账本游标，输入和结果绑定原会员，政策不能覆盖历史成长率。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c07)。
- **本页动作或表单证据**　[账本首页](../../../frontend/src/iam/CentralGrowth.tsx#L276)、[下一批流水](../../../frontend/src/iam/CentralGrowth.tsx#L284)。

<a id="c08"></a>
### C08 会员标签 · `/operations/member-tags`

- **角色与目标**　中央会员运营，维护定义及会员关联。
- **当前**　关键字字典、按会员号查关联，按现有版本绑定或停用。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [TagDictionary](../../../frontend/src/iam/CentralTags.tsx#L197)、[MemberOperations](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/MemberOperationsController.java#L11)、[TagActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/TagActionsController.java#L14)。
- **问题及影响**　字典编号、会员编号和关联版本手工串接，字典中没有关联现状。
- **改造及验收（P2/T1）**　字典名称+编号，会员上下文内显示已绑定版本和复核入口，保留独立写岗。待绑定、已绑定、已停用分明，版本冲突提示重读。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c08)。
- **本页动作或表单证据**　[刷新字典](../../../frontend/src/iam/CentralTags.tsx#L213)、[字典首页](../../../frontend/src/iam/CentralTags.tsx#L240)、[下一批标签](../../../frontend/src/iam/CentralTags.tsx#L246)。

<a id="c09"></a>
### C09 会员行为 · `/operations/member-behavior`

- **角色与目标**　中央人群运营，核对画像、构建偏好及投影。
- **当前**　精确会员号、近30天信号、事件流水、偏好修订与重建。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [BehaviorDetail](../../../frontend/src/iam/CentralBehavior.tsx#L272)、[MemberBehavior](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/behavior/MemberBehaviorController.java#L17)、[BehaviorActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/BehaviorActionsController.java#L14)。
- **问题及影响**　画像时间窗口语义复杂，行为事件表 SKU/门店编号无业务名称。
- **改造及验收（P2/T2）**　摘要标 UTC 与来源水位，行为流水按已授权对象可下钻，敏感字段只在需要处显示。历史覆盖不足可识别，重建展示受理与实际完成差异。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c09)。
- **本页动作或表单证据**　[刷新行为资料](../../../frontend/src/iam/CentralBehavior.tsx#L283)、[最早交互](../../../frontend/src/iam/CentralBehavior.tsx#L409)、[下一批交互](../../../frontend/src/iam/CentralBehavior.tsx#L415)。

<a id="c10"></a>
### C10 会员周期 · `/operations/member-cycles`

- **角色与目标**　中央会员运营，维护周期政策、读取会员考核。
- **当前**　按区查政策与单会员周期，显示独立新政策、考核动作。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Policies](../../../frontend/src/iam/CentralCycles.tsx#L285)、[MemberCycle](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/cycle/MemberCycleController.java#L11)、[CycleActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/CycleActionsController.java#L14)。
- **问题及影响**　锚点、窗口和考核快照版本难同时辨别。
- **改造及验收（P2/T1）**　用时间轴显示当前区间、下一次评估和固定规则来源。切换会员不会沿用历史动作，计算规则标出旧新版本。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c10)。
- **本页动作或表单证据**　[刷新政策](../../../frontend/src/iam/CentralCycles.tsx#L318)、[最早政策](../../../frontend/src/iam/CentralCycles.tsx#L328)、[下一批政策](../../../frontend/src/iam/CentralCycles.tsx#L334)。

<a id="c11"></a>
### C11 周期权益 · `/operations/member-cycle-benefits`

- **角色与目标**　中央权益运营，按政策版查礼包并发放。
- **当前**　礼包编号、政策版本、等级、门店、截止和权益引用，独立处置回执。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Bundles](../../../frontend/src/iam/CentralCycles.tsx#L452)、[MemberBenefit](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/benefit/MemberBenefitController.java#L11)、[CycleActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/CycleActionsController.java#L14)。
- **问题及影响**　礼包版本、权益版本和截止时间难在一屏核对。
- **改造及验收（P1/T1）**　按政策版显示合资格礼包、门店和固定权益引用，分开发放与结果回执。旧政策的礼包仍保持原绑定，发放回执列明确真实权益状态。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c11)。
- **本页动作或表单证据**　[查询周期礼包](../../../frontend/src/iam/CentralCycles.tsx#L486)。

<a id="c12"></a>
### C12 积分账户 · `/operations/member-points`

- **角色与目标**　中央积分运营，核对积分、冻结和待偿，变更政策。
- **当前**　精确会员号、积分账户流水、政策独立区和有资格调整。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [WalletDetails](../../../frontend/src/iam/CentralPoints.tsx#L261)、[MemberPoints](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/points/MemberPointsController.java#L11)、[PointsActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/member/operations/PointsActionsController.java#L14)。
- **问题及影响**　流水列很多，增量、冻结、待偿和来源对象需要优先呈现。
- **改造及验收（P1/T2）**　分余额与流水，关键动作标正负、账户作用和原因，支持授权来源下钻。无权不可见敏感流水，负向调整显示冻结保留与待偿逻辑，冲突重读。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c12)。
- **本页动作或表单证据**　[账本首页](../../../frontend/src/iam/CentralPoints.tsx#L317)、[下一批流水](../../../frontend/src/iam/CentralPoints.tsx#L325)。

<a id="c13"></a>
### C13 积分兑换 · `/operations/point-offers`

- **角色与目标**　中央积分运营，维护可兑换权益和券。
- **当前**　按店搜名称/状态，独立创建和停用显示额度与版本。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [OfferList](../../../frontend/src/iam/CentralPointOffers.tsx#L153)、[PointOffer](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/pointoffer/PointOfferController.java#L12)、[PointOfferActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/pointoffer/PointOfferActionsController.java#L14)。
- **问题及影响**　资产只有编号版本，已发和剩余用量需要快速核对，需分清积分价与现金。
- **改造及验收（P1/T2）**　名称配资产类型和固定版本，展示已兑/总量、窗口与实际状态，停用显示影响。额度后来缩紧或资产失效时按当前策略显示，原兑换仍有受理事实。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c13)。
- **本页动作或表单证据**　[目录首页](../../../frontend/src/iam/CentralPointOffers.tsx#L226)、[下一批商品](../../../frontend/src/iam/CentralPointOffers.tsx#L232)。

<a id="c14"></a>
### C14 活动管理 · `/operations/campaigns`

- **角色与目标**　中央营销、审批，草拟、模拟并治理版本。
- **当前**　内容/控制版本、独立预览和命令，模拟给选中活动、人群和订单分项。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Directory](../../../frontend/src/iam/CentralCampaigns.tsx#L145)、[Commerce](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/commerce/CommerceController.java#L17)、[CampaignActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/CampaignActionsController.java#L14)。
- **问题及影响**　命令需要填原标识和版本，目录日期与活动资格预览脱节。
- **改造及验收（P1/T1）**　对兼具读写权限的人员从原版本预填，写岗精确入口仍独立，强化发布影响摘要。过期资格、控制版本冲突、角色撤销均准确回显，模拟结果不写预算。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c14)。
- **本页动作或表单证据**　[刷新活动目录](../../../frontend/src/iam/CentralCampaigns.tsx#L169)、[选择此版本](../../../frontend/src/iam/CentralCampaigns.tsx#L223)、[回到首页](../../../frontend/src/iam/CentralCampaigns.tsx#L241)、[下一页](../../../frontend/src/iam/CentralCampaigns.tsx#L244)。

<a id="c15"></a>
### C15 活动预算 · `/operations/campaign-budgets`

- **角色与目标**　中央营销财务，查独立授权下真实版本额度。
- **当前**　按预算或活动号查，上限、预占和支出，只读。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Budgets](../../../frontend/src/iam/CentralCampaigns.tsx#L1032)、[MarketingAsset](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java#L15)。
- **问题及影响**　门店/活动名缺失，不能从活动带版本进来快速对账，缺剩余定义。
- **改造及验收（P1/T2）**　输出服务端可用额度，预算详情显示关联版和状态，拒绝跨权限链接。同活动多版预算保持可区分，退款不回补，余额不得客户端浮算。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c15)。
- **本页动作或表单证据**　[刷新预算余额](../../../frontend/src/iam/CentralCampaigns.tsx#L1047)、[回到首页](../../../frontend/src/iam/CentralCampaigns.tsx#L1082)、[下一页](../../../frontend/src/iam/CentralCampaigns.tsx#L1085)。

<a id="c16"></a>
### C16 人群快照 · `/operations/audiences`

- **角色与目标**　中央营销运营，读取固定成员和独立导入。
- **当前**　目录含版本、人数、来源、水位、截止，有界会员粘贴及实际回执。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Directory](../../../frontend/src/iam/CentralAudiences.tsx#L47)、[MarketingAsset](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java#L15)、[AudienceActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/AudienceActionsController.java#L14)。
- **问题及影响**　旧/空/可用人群大量共用，缺“可用于当前活动”快捷定位。
- **改造及验收（P1/T2）**　及时显示过期与资格说明，预先核对成员条数，保留过期导入审计规则。允许历史有效期查询，过期/未知不误呈现可投放，独立写岗不自动读目录。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c16)。
- **本页动作或表单证据**　[刷新人群目录](../../../frontend/src/iam/CentralAudiences.tsx#L66)、[回到首页](../../../frontend/src/iam/CentralAudiences.tsx#L134)、[下一页](../../../frontend/src/iam/CentralAudiences.tsx#L137)。

<a id="c17"></a>
### C17 动态人群 · `/operations/segments`

- **角色与目标**　中央人群运营，配置规则、调度、刷新和控制固定运行。
- **当前**　定义/调度版本、输出、运行列表、独立推进资格、公告与未知结果。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Directory](../../../frontend/src/iam/CentralSegments.tsx#L359)、[Segment](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/segment/SegmentController.java#L12)、[SegmentActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/segment/SegmentActionsController.java#L14)。
- **问题及影响**　停止周期和停止实际运行分散，当前输出快照难从目录确认。
- **改造及验收（P1/T2）**　目录附最后可用输出，操作标题明确未来排程和本次运行，回执按双作用域。停周期保留原运行规则，重试明确原运行编号和原授权，公告失败独立可见。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c17)。
- **本页动作或表单证据**　[刷新定义目录](../../../frontend/src/iam/CentralSegments.tsx#L388)、[详情与记录](../../../frontend/src/iam/CentralSegments.tsx#L432)、[调整调度](../../../frontend/src/iam/CentralSegments.tsx#L435)、[回到首页](../../../frontend/src/iam/CentralSegments.tsx#L455)、[下一页](../../../frontend/src/iam/CentralSegments.tsx#L458)。

<a id="c18"></a>
### C18 动态规则 · `/operations/rules`

- **角色与目标**　中央规则运营，维护及发布动态规则。
- **当前**　字段字典、结构化条件、固定版和独立发布。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Directory](../../../frontend/src/iam/CentralRules.tsx#L123)、[MarketingAsset](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/MarketingAssetController.java#L15)、[RuleActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/asset/RuleActionsController.java#L13)。
- **问题及影响**　复杂条件在表格难阅读，发布表单重输规则号和内容版本。
- **改造及验收（P2/T1）**　列表给语言摘要，复杂条件展开，授权时从目录按实际版本带入发布。未知字段或无权限有明确信息，发布后原定义可查。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c18)。
- **本页动作或表单证据**　[刷新规则目录](../../../frontend/src/iam/CentralRules.tsx#L141)、[目录首页](../../../frontend/src/iam/CentralRules.tsx#L186)、[下一批规则](../../../frontend/src/iam/CentralRules.tsx#L192)。

<a id="c19"></a>
### C19 优惠券定义 · `/operations/coupon-definitions`

- **角色与目标**　中央券运营，建立、校验和搜索券定义。
- **当前**　按店和关键词查，表包含门槛、优惠、额度、叠加、承担比例、发行方式及有效窗口。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [DefinitionList](../../../frontend/src/iam/CentralCouponDefinitions.tsx#L145)、[Coupon](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/coupon/CouponController.java#L12)、[CouponDefinitionActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/coupon/CouponDefinitionActionsController.java#L14)。
- **问题及影响**　14列压缩操作判断，能否定向发券由读者自己推断。
- **改造及验收（P1/T1）**　名称+编号/版本、方式/有效期、额度/已发优先，成本说明进入详情及创建确认。任一主要视口可判断领取或定向，超额或非法比例必须由服务端拒绝。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c19)。
- **本页动作或表单证据**　[目录首页](../../../frontend/src/iam/CentralCouponDefinitions.tsx#L226)、[下一批定义](../../../frontend/src/iam/CentralCouponDefinitions.tsx#L232)。

<a id="c20"></a>
### C20 定向发券 · `/operations/coupon-deliveries`

- **角色与目标**　中央券运营，定向发放、停止、撤回和逐会员追踪。
- **当前**　批次、方向、实际状态、CAS、处理结果、推进资格、受理和逐人结果。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Directory](../../../frontend/src/iam/CentralCouponDeliveries.tsx#L397)、[CouponDelivery](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/delivery/CouponDeliveryController.java#L12)、[CouponDeliveryActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/delivery/CouponDeliveryActionsController.java#L14)。
- **问题及影响**　当前控制版醒目但截止、可撤回基数、失败归属滞后，独立操作栏需人工填号。
- **改造及验收（P1/T2）**　首屏突出人数和截止，行内可选的独立控制预填，受理与实际结果并列。撤回时已用、占用和到期分别保留，不显示补偿成功，未知结果复用原意图。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c20)。
- **本页动作或表单证据**　[查询批次](../../../frontend/src/iam/CentralCouponDeliveries.tsx#L446)、[刷新批次](../../../frontend/src/iam/CentralCouponDeliveries.tsx#L449)、[详情与收件人](../../../frontend/src/iam/CentralCouponDeliveries.tsx#L511)、[查询收件人](../../../frontend/src/iam/CentralCouponDeliveries.tsx#L547)、[关闭](../../../frontend/src/iam/CentralCouponDeliveries.tsx#L562)。

<a id="c21"></a>
### C21 权益定义 · `/operations/entitlement-definitions`

- **角色与目标**　中央权益运营，创建内部权益和看受控容量。
- **当前**　门店、定义版本、单位、上限、预留、已发、有效期，独立新增授权。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [DefinitionList](../../../frontend/src/iam/CentralEntitlementDefinitions.tsx#L141)、[Entitlement](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java#L12)、[EntitlementActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementActionsController.java#L13)。
- **问题及影响**　相邻容量列缺关系说明，外部渠道含义容易误解。
- **改造及验收（P2/T1）**　定义、单位、剩余额度和窗口主视图，其他展开，提交前对照窗口及每份单位。数量不带币种，预留与已发不合并，已授权门店切换后重置旧选择。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c21)。
- **本页动作或表单证据**　[目录首页](../../../frontend/src/iam/CentralEntitlementDefinitions.tsx#L195)、[下一批定义](../../../frontend/src/iam/CentralEntitlementDefinitions.tsx#L201)。

<a id="c22"></a>
### C22 权益台账 · `/operations/entitlements`

- **角色与目标**　中央权益支持，追实例并处理补偿欠项。
- **当前**　会员、定义、版本、剩余、欠额、时间、来源对象、独立处置。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Directory](../../../frontend/src/iam/CentralEntitlements.tsx#L121)、[Entitlement](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementController.java#L12)、[EntitlementActions](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/benefit/entitlement/EntitlementActionsController.java#L13)。
- **问题及影响**　列表大量 ID，欠项缺负责处置人和最近原因。
- **改造及验收（P1/T2）**　将名称/实例、状态、剩余与待处理优先，详情联到来源和受控处理回执。处置确认使用实际单位与版本，未知状态保持原意图，来源读取独立授权。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c22)。
- **本页动作或表单证据**　[目录首页](../../../frontend/src/iam/CentralEntitlements.tsx#L183)、[下一批实例](../../../frontend/src/iam/CentralEntitlements.tsx#L189)、[刷新实例](../../../frontend/src/iam/CentralEntitlements.tsx#L197)。

<a id="c23"></a>
### C23 旅程定义 · `/operations/journeys`

- **角色与目标**　中央旅程运营，建立、预览、审批和发布旅程。
- **当前**　固定内容版、状态锁及触发，独立动作和流程详情。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Workspace](../../../frontend/src/iam/CentralJourneys.tsx#L154)、[Journey](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java#L12)、[JourneyAccess](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyAccessController.java#L13)。
- **问题及影响**　实际动作表单和定义分离，创建与验证仍用节点、频控和生命周期文本配置，运营需理解字段及触发规则。
- **改造及验收（P1/T2）**　采用类型化节点编辑分组与分步校验，保留高级文本输入的合法互转。从目录预填所选固定版本的授权动作，提供实例与扫描深链。审批与执行资格仍独立，暂停、在途和过去结果分开。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c23)。
- **本页动作或表单证据**　[取消 / 重试](../../../frontend/src/iam/CentralJourneys.tsx#L511)、[恢复扫描](../../../frontend/src/iam/CentralJourneys.tsx#L548)、[重新核验并刷新](../../../frontend/src/iam/CentralJourneys.tsx#L801)、[推进一次](../../../frontend/src/iam/CentralJourneys.tsx#L834)、[手工入组](../../../frontend/src/iam/CentralJourneys.tsx#L846)。

<a id="c24"></a>
### C24 旅程实例 · `/operations/journey-instances`

- **角色与目标**　中央营销支持，按固定内容版追踪实例与步骤。
- **当前**　列表含固定版、节点、已提交步数、执行期限，实例级历史和控制。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Workspace](../../../frontend/src/iam/CentralJourneys.tsx#L154)、[Journey](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java#L12)、[JourneyAccess](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyAccessController.java#L13)。
- **问题及影响**　详情、历史读取和独立操作需要重新输入实际实例，形成重复定位。
- **改造及验收（P1/T1）**　从行详情按原实例打开历史，预填授权动作，标历史覆盖与真实副作用。同一实例历史从行可达，点击动作自动带准确版本，旧日志缺失明确覆盖。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c24)。
- **本页动作或表单证据**　[取消 / 重试](../../../frontend/src/iam/CentralJourneys.tsx#L511)、[恢复扫描](../../../frontend/src/iam/CentralJourneys.tsx#L548)、[重新核验并刷新](../../../frontend/src/iam/CentralJourneys.tsx#L801)、[推进一次](../../../frontend/src/iam/CentralJourneys.tsx#L834)、[手工入组](../../../frontend/src/iam/CentralJourneys.tsx#L846)。

<a id="c25"></a>
### C25 生命周期扫描 · `/operations/journey-scans`

- **角色与目标**　中央营销值班，核对扫查进度并恢复固定版本。
- **当前**　显示旅程、检查点、扫描入组、下次、失败码和动作。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Workspace](../../../frontend/src/iam/CentralJourneys.tsx#L154)、[Journey](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java#L12)、[JourneyAccess](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyAccessController.java#L13)。
- **问题及影响**　检查点作为内部进度一眼难判读，停机/隔离、计划尚未到期需分辨。
- **改造及验收（P1/T2）**　把状态、最后成功和下次时间置前，保留技术诊断与原版本恢复入口。同旅程不同内容版可区分，隔离失败有原原因，恢复不跨版本。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c25)。
- **本页动作或表单证据**　[取消 / 重试](../../../frontend/src/iam/CentralJourneys.tsx#L511)、[恢复扫描](../../../frontend/src/iam/CentralJourneys.tsx#L548)、[重新核验并刷新](../../../frontend/src/iam/CentralJourneys.tsx#L801)、[推进一次](../../../frontend/src/iam/CentralJourneys.tsx#L834)、[手工入组](../../../frontend/src/iam/CentralJourneys.tsx#L846)。

<a id="c26"></a>
### C26 营销效果 · `/operations/marketing-effects`

- **角色与目标**　中央营销分析，按订单、旅程和批次展现投影效果。
- **当前**　门店与窗口、活动成交/生命周期/券批次/执行计数，显示覆盖和成本解释。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Workspace](../../../frontend/src/iam/CentralJourneys.tsx#L154)、[MarketingEffects](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/insight/MarketingEffectsController.java#L13)、[Journey](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java#L12)。
- **问题及影响**　报表切换时页游标须跟系列绑定，各系列行标识、窗口语义和数据完整性不同。
- **证据细节**　效果表以响应首行键生成列，只有少量字段有中文映射。指标小数精度、更新时点、未覆盖字段与各系列的呈现要明确。 [列生成](../../../frontend/src/iam/CentralJourneys.tsx#L599)、[效果 DTO](../../../marketing-automation/src/main/java/com/lrj/commerce/insight/api/MarketingEffectsApi.java#L18)。
- **改造及验收（P1/T2）**　把系列、门店、区间绑成同一查询，每个系列使用 Owner 专属游标和列，显示覆盖。切系列与窗口清旧 cursor，页末按相应真实主键推进，过期数据与零区分。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c26)。
- **本页动作或表单证据**　[取消 / 重试](../../../frontend/src/iam/CentralJourneys.tsx#L511)、[恢复扫描](../../../frontend/src/iam/CentralJourneys.tsx#L548)、[重新核验并刷新](../../../frontend/src/iam/CentralJourneys.tsx#L801)、[推进一次](../../../frontend/src/iam/CentralJourneys.tsx#L834)、[手工入组](../../../frontend/src/iam/CentralJourneys.tsx#L846)。

<a id="c27"></a>
### C27 营销执行 · `/operations/marketing-executions`

- **角色与目标**　中央营销技术支持，查单笔活动执行和权益真实状态。
- **当前**　目录列订单、活动和版本、失败，另有订单+活动精确查询。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Workspace](../../../frontend/src/iam/CentralJourneys.tsx#L154)、[CampaignExecution](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/execution/CampaignExecutionController.java#L15)。
- **问题及影响**　列表缺按订单或异常的筛选，按订单信息需手填。现有下单路径只写一条选中的活动执行。
- **证据细节**　SQL 在按订单号和活动号排序时只按订单号寻找下一批。复合主键允许同单多条数据。已核对原生下单只登记一个中选活动，兼容风险见 R09。 [查询](../../../marketing-runtime/src/main/resources/mappers/campaign/CampaignExecutionMapper.xml#L8)、[主键](../../../commerce-app/src/main/resources/db/migration/V41__marketing_execution.sql#L23)。
- **改造及验收（P1/T2）**　目录加精确单号和失败类查询，行导航携带复合业务键，原状态与权益结果区分。重复查询和现有单活动路径不遗漏，未来一单多活动再验证复合游标，失败按责任提示。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c27)。
- **本页动作或表单证据**　[取消 / 重试](../../../frontend/src/iam/CentralJourneys.tsx#L511)、[恢复扫描](../../../frontend/src/iam/CentralJourneys.tsx#L548)、[重新核验并刷新](../../../frontend/src/iam/CentralJourneys.tsx#L801)、[推进一次](../../../frontend/src/iam/CentralJourneys.tsx#L834)、[手工入组](../../../frontend/src/iam/CentralJourneys.tsx#L846)。

<a id="c28"></a>
### C28 订单 · `/operations/orders`

- **角色与目标**　中央交易运营，查看授权订单与到期处置。
- **当前**　订单、店、应付、会员、商家、付款方式、时间，明细单独请求，20笔到期及单笔恢复。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [CentralOrders](../../../frontend/src/iam/CentralOrders.tsx#L20)、[Console](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java#L16)、[OperationsAccess](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OperationsAccessController.java#L12)。
- **问题及影响**　“独立订单操作”到期批次在目录前，清单不反映实际处理资格，店名仍需另查。
- **改造及验收（P1/T2）**　到期命令前标明实际批范围和原停止责任，目录加授权门店名与支付、履约入口。支付中的订单必须先核对真实渠道，命令处理与实际状态区分，跨组织拒绝。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c28)。

<a id="c29"></a>
### C29 支付 · `/operations/payments`

- **角色与目标**　中央财务，按原订单核支付。
- **当前**　订单号精确读取渠道、币种、金额、状态、版本，核对权限独立。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [PaymentRead](../../../frontend/src/iam/CentralPayments.tsx#L13)、[Console](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java#L16)、[Payment](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/payment/PaymentController.java#L14)、[OperationsAccess](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OperationsAccessController.java#L12)。
- **问题及影响**　查询与核对两个输入位置，交易排查需要手工复制订单号，无支付目录。
- **改造及验收（P1/T1）**　从订单带入同一 ID、把资金结果和订单显示分开，保持独立写岗和读取权限。打开支持的订单深链后只 GET，支付无记录、未知、成功分开，当前未授权则只按资格展示。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c29)。
- **本页动作或表单证据**　[读取支付](../../../frontend/src/iam/CentralPayments.tsx#L34)、[刷新](../../../frontend/src/iam/CentralPayments.tsx#L37)。

<a id="c30"></a>
### C30 履约 · `/operations/fulfillments`

- **角色与目标**　中央仓配，跟踪仓配与允许的发货、签收。
- **当前**　队列有订单、进度、运单、阻拦与渠道，独立发货签收资格。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [CentralFulfillments](../../../frontend/src/iam/CentralFulfillments.tsx#L8)、[Aftersale](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java#L14)。
- **问题及影响**　操作表单和被阻拦记录分散，值班需核对单号和阻拦原因。
- **改造及验收（P1/T2）**　阻拦状态置前，行内预填需双权限，展示阻拦原售后和有效运单。被拦订单没有可误导的可发按钮，遵循可信适配器回执。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c30)。

<a id="c31"></a>
### C31 售后 · `/operations/aftersales`

- **角色与目标**　中央售后，审核、收件并跟踪退款。
- **当前**　列表快照和独立审批、驳回、收件，复用通用详情。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [CentralAftersales](../../../frontend/src/iam/CentralAftersales.tsx#L8)、[Aftersale](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java#L14)。
- **问题及影响**　仅列表快照，详情未传 detailPath，独立动作缺申请商品行及条件核对。
- **改造及验收（P1/T2）**　加受中央权限保护的原申请实时详情，审批前预览商品、店、金额和影响。不同状态只允许合法处置，跨对象授权失败，原未知资金单不产生二次退款。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c31)。

<a id="c32"></a>
### C32 退款 · `/operations/refunds`

- **角色与目标**　中央财务售后，查退款及进行原渠道核对。
- **当前**　退款/订单/售后、金额、渠道、状态，独立手填核对。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [CentralRefunds](../../../frontend/src/iam/CentralRefunds.tsx#L8)、[Aftersale](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/aftersales/AftersaleController.java#L14)。
- **问题及影响**　同订单多退款需要可识别的售后归属，查看与核对角色虽独立但入口重复填。
- **改造及验收（P1/T2）**　以待核对为快捷卡，显示原申请、记录更新时间和可信证据范围。同单多退可以原退款号区分，无新退款命令，渠道不可用保持未知。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c32)。

<a id="c33"></a>
### C33 运营页面 · `/operations/ops-pages`

- **角色与目标**　中央内容运营，查、预览、治理低代码版本。
- **当前**　目录、发布内容、版本表、直接读取、结构化命令与独立授权。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [PageRead](../../../frontend/src/iam/CentralOpsPages.tsx#L572)、[OpsPage](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OpsPageController.java#L12)。
- **问题及影响**　[确认] 筛选工具栏提交 q/status/limit，目录请求只用 after 和固定50，可令翻页与每页选项不符。
- **证据细节**　在此页现有请求没有拼接 `filters.query`，分页又以 `filters.limit` 判下一批。过滤和换页会出现两套口径。 [读取与翻页](../../../frontend/src/iam/CentralOpsPages.tsx#L572)。
- **改造及验收（P1/T1）**　接实 filters.query 并规范编码，保持 owner 判权和游标，确认控件所需范围。选择10条实际请求 limit=10，精确关键词、状态和重置返回正确结果，副作用为零。
- **实施范围**　前端交互、标签或已有载荷。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c33)。
- **本页动作或表单证据**　[刷新列表](../../../frontend/src/iam/CentralOpsPages.tsx#L593)、[读取版本与发布页](../../../frontend/src/iam/CentralOpsPages.tsx#L616)、[首批](../../../frontend/src/iam/CentralOpsPages.tsx#L633)、[下一批](../../../frontend/src/iam/CentralOpsPages.tsx#L636)、[读取发布内容](../../../frontend/src/iam/CentralOpsPages.tsx#L649)。

<a id="c34"></a>
### C34 事件处理 · `/operations/events`

- **角色与目标**　中央运行运营，监测事件、查积压与人工推进。
- **当前**　健康、列表、独立推进和重试，业务与技术信息并存。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [CentralEvents](../../../frontend/src/iam/CentralEvents.tsx#L145)、[Console](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java#L16)、[OperationsAccess](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/OperationsAccessController.java#L12)。
- **问题及影响**　缺从业务对象到事件的可用链路，推进接受与实际消费分散。
- **改造及验收（P1/T2）**　健康摘要给明确范围，事件行关联原对象及失败分类，原重试只在允许时显示。推进遇未知时先读实际事件结果，不显示其它租户失败详情。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c34)。

<a id="c35"></a>
### C35 运行恢复 · `/operations/runtime`

- **角色与目标**　中央平台值班，治理停工、恢复和历史重放。
- **当前**　停工、恢复审计、消费者分类、重放目录和审批式预检。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [RuntimeRead](../../../frontend/src/iam/CentralRuntime.tsx#L63)、[RuntimeRecovery](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/recovery/RuntimeRecoveryController.java#L15)。
- **问题及影响**　处置语义复杂，职权分离保持，但目标核对和历史追踪需要手填。
- **改造及验收（P1/T2）**　停工、审计、重放按任务分区，预填在同授权组合下生效，试运行影响和版本明确。不合资格拒绝逐条可解释。变更追溯原工作和试运行条件，平台健康权仍独立。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c35)。
- **本页动作或表单证据**　[刷新](../../../frontend/src/iam/CentralRuntime.tsx#L92)、[返回停止项首页](../../../frontend/src/iam/CentralRuntime.tsx#L147)、[下一批停止项](../../../frontend/src/iam/CentralRuntime.tsx#L153)、[返回审计首页](../../../frontend/src/iam/CentralRuntime.tsx#L194)、[下一批恢复审计](../../../frontend/src/iam/CentralRuntime.tsx#L200)。

<a id="c36"></a>
### C36 商品协作 · `/collaboration/products`

- **角色与目标**　外部门店协作者，在同行授权时间内读、改、导出商品。
- **当前**　同授权目录组件，有限时 OA 支持、任务状态和真实下载资格。壳层 HTTP 200 · IAM 未启用，岗位交互待实测。 [Products](../../../frontend/src/iam/CentralProducts.tsx#L435)、[CentralScope](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java#L12)。
- **问题及影响**　跨店同名易混，审批请求、有效权限及可下载三种状态之间要跑多个界面。
- **改造及验收（P1/T2）**　顶部显示授权门店及有效时点，保持原审计与取消、失效语义，下载失败给真实原因。限时授权回收后行命令和下载均拒绝，仅查/改/导出任一可独立。
- **实施范围**　前端与 Owner 的受限读取/命令契约，索引与数据模型按实际查询计划决定。 [当前列、当前筛选和目标布局](03-table-and-filter-optimization.md#c36)。
- **本页动作或表单证据**　[退出本应用](../../../frontend/src/iam/CentralProducts.tsx#L507)、[刷新商品](../../../frontend/src/iam/CentralProducts.tsx#L524)、[查看资料](../../../frontend/src/iam/CentralProducts.tsx#L557)、[回到首页](../../../frontend/src/iam/CentralProducts.tsx#L577)、[下一页](../../../frontend/src/iam/CentralProducts.tsx#L583)。

## 配置和处置表单逐类复核

| 实际表单和容器 | 当前保护及缺口 | 改造和验收 |
| --- | --- | --- |
| 商品资料、SKU、条码、渠道价、计划 | 支持单 SKU 修订原因、版本历史、已选规格和计划窗口，SPU 与 SKU 属性差异显式。多处使用原编号，计划前后的真实变化难一屏核对。 [商品](../../../frontend/src/features/ProductOperations.tsx#L193)、[计划](../../../frontend/src/features/CatalogScheduling.tsx#L71)、[类目与模板](../../../frontend/src/features/CatalogMerchandising.tsx#L91)。 | 目录/结构化信息分组，选择或校验实际父类、模板版与联合规格。调价、批量和渠道价采用现价→目标价对照。图片沿用真实 URL 与文本替代，不扩大上传能力。 |
| 会员档案、成长、积分、周期、标签、行为 | 已有原因、版本、精确会员号等，同会员跨子区再录号时有对象确认负担，多个时期和负数规则需在控件附近说明。 [档案](../../../frontend/src/features/MemberActions.tsx)、[积分](../../../frontend/src/features/MemberPoints.tsx)、[周期](../../../frontend/src/features/MemberCycles.tsx)、[中央行为](../../../frontend/src/iam/CentralBehavior.tsx#L444)。 | 变更前后并列，单独展示政策内容版、生效窗和本次变更原因，切换范围使旧草稿失效。支持者用稳定错误码定位等级、重复标识、版本冲突。 |
| 活动、规则、快照、人群、旅程 | 已支持固定版本、结构化条件/审批或专用预览，门槛、成员来源时点、频控、同店限制出现在不同表单。 [中央活动表单](../../../frontend/src/iam/CampaignDraftForm.tsx#L165)、[中央人群](../../../frontend/src/iam/CentralSegments.tsx#L510)、[中央快照](../../../frontend/src/iam/CentralAudiences.tsx#L150)、[旅程节点](../../../frontend/src/features/Journeys.tsx#L49)。 | 依次核配置、资产与范围、预检与版本发布。复制产生新草稿及新键，敏感或过期资产必须重新核权。跨步骤校验显示缺项，服务端仍锁定固定版本。 |
| 券定义、发券、权益定义和积分兑换 | 定义、受理、真实执行分开服务，尤其发券停发与撤回不等价，旧页配券和额度的读写密度高。 [发券](../../../frontend/src/iam/CentralCouponDeliveries.tsx#L602)、[权益定义](../../../frontend/src/iam/CentralEntitlementDefinitions.tsx#L215)、[兑换](../../../frontend/src/iam/CentralPointOffers.tsx#L268)。 | 依发放类型动态显示字段，份/单位/积分/元分别标出，影响估算与实际完成分开，出问题仍按原批次恢复。 |
| 库存收货、发出/签收、售后审批、核对 | Owner 有合法状态及作用空间校验。中央不少操作表单可由只写岗位直接输入实际号，兼具读写者仍需手填。 [库存](../../../frontend/src/iam/CentralInventory.tsx#L201)、[履约](../../../frontend/src/iam/CentralFulfillments.tsx)、[售后](../../../frontend/src/iam/CentralAftersales.tsx)、[支付](../../../frontend/src/iam/CentralPayments.tsx)。 | 明确原对象、金额、数量和效果，预填仅在真实授权交集。审批、退货收回和款项到账分别验收。 |
| 低代码、恢复、重放 | 操作按类型判权，保留试运行、原因、版本和未知回执，技术字段易让操作者误判计划或真实结果。 [中央低代码](../../../frontend/src/iam/CentralOpsPages.tsx#L44)、[中央运行恢复](../../../frontend/src/iam/CentralRuntime.tsx#L278)。 | 向导优先选择真实对象/窗口与副作用性质，再确认与查看结果，预览不执行，业务动作仍单独鉴权，跨窗口必须重新试运行。 |
