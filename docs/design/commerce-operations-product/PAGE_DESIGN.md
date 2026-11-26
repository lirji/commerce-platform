# 按模块与页面的优化设计

[总体方案](FRONTEND_ARCHITECTURE.md) · [重点页面草图](WIREFRAMES.md)

以下按现有业务入口编排。L 为旧版运营，C 为中央员工或协作，M 为会员，P 为平台运维。同一业务的两套入口保留身份、查询与动作范围的差异。请求路径省略共同的 `/v1` 前缀。表中 P1 先交付高频决策与异常处置，P2 在核心路径稳定后推进。涉及新增读接口、字段、阈值或审查的设计，依对应 Owner 定义契约后进入实施。

旧门店运营复用 `/#skus`，仅有授权商品经营入口。沿 L07 的主列与双查询设计，只显示自身授权门店、SKU 与动作范围。组织入口 `/operations`、OIDC 回调 `/iam/callback`、登录与未知路由是导航边界，验收本人落地与错误恢复。历史 `/products`、`/stores` 属于兼容声明，当前工作区的业务入口以发布菜单和编译路由的交集为准。

## 经营总览

| 页面与主要数据入口 | 定位与运营判断 | 当前不足 | 调整后的首屏与详情 | 默认字段与筛选 | 优先级与范围 |
| --- | --- | --- | --- | --- | --- |
| **L01 经营总览**<br>`/#dashboard`<br>`/admin/dashboard` · [Dashboard](../../../frontend/src/features/Dashboard.tsx) | 店长判断本店今天先处理什么、经营结果是否异常。 | 已有四类待办和经营趋势；门店成交、门店在售和租户会员混在同一阅读序列。 | 顶部门店和 UTC 窗口；首屏先待办，再门店实收、退款、净收和成交趋势；会员规模移到独立的租户摘要。点击待办带原门店与状态。 | 待办按严重性与职责固定排列；趋势保留指标切换、日期和明细，暂用原 30 日窗口。 | P1 前端；全量待办数和跨期对比需读契约 |
| **C01 经营总览**<br>`/operations/dashboard`<br>`/admin/dashboard` · [CentralDashboard](../../../frontend/src/iam/CentralDashboard.tsx) | 中央店长从授权队列做决策，核对收款与退款走势。 | 已有待办和独立读门禁，手填门店与指标来源风险仍突出。 | 顶部组织、环境、实际门店和时间；待办支持逐域重读；经营指标标店铺/租户及覆盖，手动输入有权威可选店时复用目录。 | 同门店/状态下钻；总览独立于各队列。近期对比等待明确窗口。 | P1 前端；门店选择补读可选 |

## 商品、库存、主体及授权

| 页面与主要数据入口 | 定位与运营判断 | 当前不足 | 调整后的首屏与详情 | 默认字段与筛选 | 优先级与范围 |
| --- | --- | --- | --- | --- | --- |
| **L07 商品管理**<br>`/#skus`<br>`/operations/catalog-search` · [ProductOperations](../../../frontend/src/features/ProductOperations.tsx) | 商品运营判定哪些规格可卖及在哪里维护价格。 | 双搜索已有作用域；规格、售价、销售状态和计划仍要横跨子区核对。 | SKU 首列商品名/规格/条码，后续当前价/状态/门店，把计划下一动作放在详情；SPU 维持独立查询；真实授权图只作辅助。 | 按各页签已有关键词、渠道上下文；状态仅按接口；渠道现价批量展开需 Owner 数据。 | P1 前端＋渠道摘要读契约 |
| **L08 库存额度**<br>`/#inventory`<br>`/admin/inventory` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 收货岗核对货量、预占和补货对象。 | 只见规格号时易混同 SKU；可用、预占、已售对决策作用不同。 | 门店常驻，首列规格名称与编号，三种库存事实并列；收货弹窗显示当前、增量和预检，回执重读实际变化。 | 门店必选、规格关键词；低库存仅在确定阈值与真实筛选后开放。 | P1 前端；商品名批量补读与治理 |
| **L09 商家管理**<br>`/#merchants`<br>`/admin/merchants` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 商家管理岗查经营主体并进入所属门店。 | 有目录但主体到门店通常还需重复检索。 | 商家名及编号、状态、版本列放前；行入口带商家号跳门店，再返回原商家筛选；创建和历史分开。 | 名称/编号、状态；门店数量不上主表，待 Owner 提供完整统计。 | P2 前端 |
| **L10 店铺管理**<br>`/#stores`<br>`/admin/stores` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 门店运营按归属找到同名门店并检查营业状态。 | 归属经常只展示编号；店铺用途与商品/库存断开。 | 门店名称和门店号、商家、状态为常驻；可见商家名称配原编号；开商品或库存时带精确店铺；建店沿已有合法归属入口。 | 门店名称/编号、精确商家、状态；页大小和上下页复用。 | P2 前端；商家名缺失时补授权读 |
| **L11 经营授权**<br>`/#store-grants`<br>`/admin/store-grants` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 授权管理员确认受授人、覆盖范围和撤销后果。 | 同屏单店与商家授权易混；商家未来门店有授权扩张语义。 | 行展示受授人、授权类型、实际资源及启停；确认明确未来新店规则；撤销后展示原授予与实际回执。 | 受授人/资源编号、启停；受限门店明细按现有授权列表。 | P1 前端；当前覆盖数需 Owner 规则 |
| **C02 商品经营**<br>`/operations/catalog`<br>`/operations/catalog-search` · [CentralCatalog](../../../frontend/src/iam/CentralCatalog.tsx) | 中央商品运营对照规格、渠道售价及计划。 | 现有双搜索和计划，行阅读仍未覆盖商品可售判断的最短路径。 | 商品/规格/条码主列，版本、门店、状态和已返回价格分列；商品、类目模板、计划使用独立任务页签与编辑分组。 | 规格/商品分别搜索；规格状态按真实支持显示；权限差异保留。 | P1 前端＋渠道摘要读契约 |
| **C03 授权商品**<br>`/operations/products`<br>`/operations/scoped/product` · [Products](../../../frontend/src/iam/CentralProducts.tsx) | 中央运营在本人范围里查看、处理或导出商品。 | 有可信总数和原查询导出，结果回执与当前授权范围需更靠前。 | 展示实际可见统计、已应用条件；首列产品、门店、品类和品牌，任务区显示原条件、实际行数、有效期与下载复核。 | 当前 scoped search/游标；权限和资源版本变化重生成；字段审查和快照清理独立。 | P1 前端＋导出治理 |
| **C04 商家与门店**<br>`/operations/directory`<br>`/admin/merchants` · [DirectorySection](../../../frontend/src/iam/CentralDirectory.tsx) | 企业运营在相同组织内找商家、切换下属门店。 | 已分别记忆筛选，归属、后续商品和库存动作仍可一眼看清。 | 保留商家/门店两页签，首列名称+编号、归属/状态；从商家跳门店的精确范围在条带中显示，返回恢复原页签。 | 各自关键词、状态、商家精确编号和本页游标。 | P2 前端 |
| **C05 库存作业**<br>`/operations/inventory`<br>`/admin/inventory` · [Inventory](../../../frontend/src/iam/CentralInventory.tsx) | 门店库存岗位核对收货对象、数量和重复提交。 | 规格信息与库存事实需更近，只写岗位还须手工精确定位。 | 门店和 SKU 主列，数量拆可用/预占/已售；短收货确认对照原数量、增量与回执；读权不足沿用独立写岗入口。 | 精确店铺、规格关键词；大数量告警按治理门槛，保留合法输入。 | P1 前端；规格名批量补读与治理 |
| **C36 商品协作**<br>`/collaboration/products`<br>`/operations/scoped/product` · [Products](../../../frontend/src/iam/CentralProducts.tsx) | 合作方按现有授权读商品和发起合法协作。 | 同组件的外部身份入口需突出仅可见的作用域和导出处理。 | 明确授予门店/商品范围与读取时点；商品首列名称/编号、门店、分类；操作按独立授权，撤权清旧范围与导出下载。 | 沿用 scoped search 和真实总量，导出条件与范围分别回显。 | P1 前端＋导出治理 |

## 会员经营及资产

| 页面与主要数据入口 | 定位与运营判断 | 当前不足 | 调整后的首屏与详情 | 默认字段与筛选 | 优先级与范围 |
| --- | --- | --- | --- | --- | --- |
| **L02 会员档案**<br>`/#members`<br>`/admin/members` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 客服按姓名或编号定位会员，判断当前服务资格。 | 档案、状态变更和行为分散，认证标识对多数岗位帮助有限。 | 首列姓名与会员号，紧随有效状态和等级；详情先档案和状态历史，再带同一会员进入成长/行为；认证标识移到获授权的排查信息。 | 姓名/编号、状态；等级筛选只有约定权威来源与作用域后再启用。 | P1 前端；等级筛选需读契约 |
| **L03 会员成长**<br>`/#growth`<br>`/admin/member-growth/{member}/ledger` · [MemberGrowth](../../../frontend/src/features/MemberGrowth.tsx) | 会员运营核对等级变动、成长余额和周期权益。 | 会员作业与等级政策共页，换会员容易把旧流水当新上下文。 | 顶部固定当前会员、当前等级和成长值；按账本、策略分组，调整弹窗显示原值、增量、理由，成功后重读实际余额。 | 会员号精确定位，账本余额/来源/发生时间；策略按实际版本顺序。 | P1 前端 |
| **L04 会员标签字典**<br>`/#member-tags`<br>`/admin/member-tags` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 标签维护者判断有没有可复用的同名标签。 | 名称、定义和关联操作缺少清晰边界。 | 目录首列名称和标签号，备注后移；详情说明可在哪些场景使用，关联入口带已选会员；定义新增用短表单。 | 名称/编号字面搜索；使用量有可信聚合后再展示。 | P2 前端 |
| **C06 会员档案**<br>`/operations/members`<br>`/admin/members` · [MemberList](../../../frontend/src/iam/CentralMembers.tsx) | 企业客服识别同名会员并判断状态与实际经营情况。 | 现有行为下钻和变更历史，决策事实可由读取权限逐步披露。 | 姓名/会员号、状态、等级、允许时的行为摘要；详情或历史切换带同一真实会员。原认证标识按需展示。 | 会员号/姓名/状态；不把仅能查询部分字段的人群误认为全租户。 | P1 前端；摘要批量读契约 |
| **C07 成长与等级**<br>`/operations/member-growth`<br>`/admin/member-growth/{member}/ledger` · [WalletDetails](../../../frontend/src/iam/CentralGrowth.tsx) | 会员运营核对成长事实并解释等级变化。 | 账户/策略和调整的语义不同，位置容易给读者错觉。 | 按会员账本和等级策略分区；账户头展示等级、成长、净消费口径；调整前显示差异，实际关联来源单列。 | 精确会员号，账本游标和当前策略版本；调整与读取分别鉴权。 | P2 前端 |
| **C08 会员标签**<br>`/operations/member-tags`<br>`/admin/member-tags` · [TagDictionary](../../../frontend/src/iam/CentralTags.tsx) | 会员运营先查标签定义，再查选定会员的关联。 | 结构可看，标签用量和标签定义容易被当成一回事。 | 页签明确字典和会员关联。字典用名称、编号、描述；关联先显示原会员和实际生效标签，增加/移除分别反馈。 | 字典按关键词，关联按原会员；标签覆盖人数由独立聚合再安排。 | P2 前端 |
| **C09 会员行为**<br>`/operations/member-behavior`<br>`/admin/member-behavior/{member}` · [BehaviorDetail](../../../frontend/src/iam/CentralBehavior.tsx) | 会员运营判断单个会员近期活跃及数据是否新鲜。 | 投影值、事件和缺证信息相邻，容易将窗口当成全量行为。 | 账户头标识会员与投影时点，突出 30 日浏览/加购/完成及净消费；事件按现有来源、类型、时间排布，展示覆盖限制。 | 仅精确会员号；行为类型/时间仅用现有列表参数，禁止虚构转化漏斗。 | P1 前端 |
| **C10 会员周期**<br>`/operations/member-cycles`<br>`/admin/member-cycles/policies` · [Policies](../../../frontend/src/iam/CentralCycles.tsx) | 周期策略负责人核对启用、评估与下一次有效窗口。 | 单会员周期与全局策略、后台失去上下文时易混读。 | 策略列表固定版本、启用态、时间端点；会员分区用当前/上期、评估状态和原版；编辑只承载策略版本。 | 先区分策略与精确会员，按原批次和游标观察。 | P2 前端 |
| **C11 周期权益**<br>`/operations/member-cycle-benefits`<br>`/admin/member-cycle-benefits` · [Bundles](../../../frontend/src/iam/CentralCycles.tsx) | 权益运营按周期等级配置实际发出的礼包。 | 绑定政策版、级别、门店及资产条目多，新增时易选错固定版本。 | 主列资格版本与级别、门店、有效至、资产项；明细展示原权益引用及适用范围，补发单独展示原会员真实授予回执。 | 政策版与级别依当前接口，缺服务端筛选时允许逐个版本查看。 | P2 前端；范围筛选需读契约 |
| **C12 积分账户**<br>`/operations/member-points`<br>`/admin/member-points/{member}/ledger` · [WalletDetails](../../../frontend/src/iam/CentralPoints.tsx) | 积分岗核对可用、冻结、待到期或账本来源。 | 现有余额/流水存在，余额和业务负债口径容易混用。 | 账户头呈现实际可用/预占和可读的到期事实；流水按增减、来源/原号、时间。短调整核前后值与原因并重载。 | 精确会员号、原游标；到期批次只有可读时显示；财务总负债不由页面计算。 | P1 前端 |
| **C13 积分兑换**<br>`/operations/point-offers`<br>`/admin/point-offers` · [OfferList](../../../frontend/src/iam/CentralPointOffers.tsx) | 商城运营找对兑换档和当前发放额度。 | 面价、积分价、固定资产版和份数上限需要同屏比较。 | 首列名称/兑换号，积分价、资产类型/版本、已发/上限、状态和窗口。详情解释每次兑换消耗，发布前校验原版本。 | 门店、编号/名称、状态；可兑资格以本人真实接口为准。 | P2 前端 |

## 人群、投放及营销效果

| 页面与主要数据入口 | 定位与运营判断 | 当前不足 | 调整后的首屏与详情 | 默认字段与筛选 | 优先级与范围 |
| --- | --- | --- | --- | --- | --- |
| **L05 动态人群**<br>`/#segments`<br>`/admin/segments` · [Segments](../../../frontend/src/features/Segments.tsx) | 人群运营确认最新可用结果能否被活动引用。 | 定义、周期和最近一次运行彼此分离；失败后难确认上一可用输出。 | 定义列表放启停、当前定义版和最近可用快照；详情用运行记录保留原输出与失败原因，按原运行恢复。 | 名称/编号、启停；批次详情按实际运行游标。最近输出摘要批量提供，避免逐行查。 | P1 前端＋最近可用输出读契约 |
| **L06 人群快照**<br>`/#audiences`<br>`/admin/audiences` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 选一份时效内的人群资产以支撑活动资格。 | 源数据时点和到期在选取前难一扫；导入后需要核对实际人数。 | 名称和固定版本在首列，人数、源时点、有效至放前；导入的短表单与历史详情分开，回执展示落库汇总。 | 名称/编号/来源；过期保留历史，窗口筛选待 Owner 契约。 | P1 前端；列表缺字段时补读 |
| **L12 活动管理**<br>`/#campaigns`<br>`/admin/campaigns` · [Marketing](../../../frontend/src/features/Marketing.tsx) | 营销运营判断草稿能否提交、审批或按窗口发布。 | 活动版与预算来源能下钻，主体、发布窗口、优惠及资格仍散在多个区。 | 列表前置活动/门店/状态/窗口及内容版；详情依次读版本、优惠、资格、成本、审批变更记录；预览弹窗明确输入和模拟结果。 | 活动名称/编号、状态，资方和预算用同版只读详情；窗口筛选需当前支持才启用。 | P1 前端＋同版聚合可选 |
| **L13 营销效果**<br>`/#effects`<br>`/admin/marketing-effects` · [MarketingEffects](../../../frontend/src/features/MarketingEffects.tsx) | 经营负责人复核活动、旅程和发券带来的已观察成交。 | 活动、会员营销比较、旅程执行和补齐分散；共用时窗标签含多个业务起点。 | 首行门店、时间、口径、覆盖；活动、旅程版本和券批次使用各自的业务列和起点，旅程执行及历史补齐在独立区域继续提供。 | 店铺/原时间窗、系列游标；对照仅同口径同分母，无权时展示该系列不可读取。 | P1 前端；跨期指标需读契约 |
| **L14 动态规则**<br>`/#rules`<br>`/admin/rules` · [Marketing](../../../frontend/src/features/Marketing.tsx) | 规则配置者判断当前内容能否正确匹配资格。 | 现有表达式术语较技术化，抽象条件难在审核前复述。 | 定义名、版本、发布状态置前；详情按条件组生成语义一致的业务句，说明空值/缺失事实，保留结构视图供排查。 | 名称/编号、发布状态；配置校验对定位字段，模拟只使用已提供场景。 | P2 前端 |
| **L15 营销预算**<br>`/#budgets`<br>`/admin/campaign-budgets` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 财务看活动版本的预算压力和可用余额。 | 预算列表已有金额事实，持有、已耗、剩余额度易混。 | 固定活动/版本、上限、预占、已耗；字段校验后展示余额，说明退款的原处理规则；点击进原版活动。 | 活动号精确、预算关键词；告警筛选与全局总额等业务规则待决。 | P1 前端；阈值由治理确认 |
| **L16 优惠券**<br>`/#coupons`<br>`/admin/coupon-definitions` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 券运营核对发行方式、可领取时效及剩余发行量。 | 旧列表的定义和政策信息分散，难区分公开券与受控券。 | 首列名称、定义号和版本，发行方式、有效期、已发/上限放前；详情展开门槛、出资比例、叠加和相对有效期。 | 门店精确、定义名称/编号；过期和可领取只按服务端资格判断。 | P1 前端；复用固定版本契约 |
| **L17 定向发券**<br>`/#coupon-deliveries`<br>`/admin/coupon-deliveries` · [CouponDeliveries](../../../frontend/src/features/CouponDeliveries.tsx) | 发券运营确认本批到底处理多少、为何跳过以及可处置项。 | 已有停止/撤回，进度与逐人结果需要按动作理解。 | 批次、门店、固定券/快照、批次状态放前；发放和撤回各自分区，逐人结果按原因展示。短确认说明保留已用/占用的后果。 | 门店、批次关键词、状态以现有支持为准；领取游标与受理时间独立。 | P1 前端 |
| **L18 权益定义**<br>`/#definitions`<br>`/admin/entitlement-definitions` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 权益定义维护者核对面向用户的单位和生效窗口。 | 有效期和固定定义版后置，单位及金额易混。 | 定义名/编号、固定版本、单位数量、窗口和到期方式；表单分别填写窗口与发放后天数；详情保持源版本。 | 定义名/编号、门店；按接口显示空值/未来有效。 | P2 前端 |
| **L19 权益台账**<br>`/#entitlements`<br>`/admin/entitlements` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 履约支持按原业务号判断权益仍在发放、已用或需补偿。 | 交易、兑换及周期来源易被读成同一类型。 | 权益名称/实例号、来源类型/编号、状态、剩余/债务、到期；详情显示原订单或其他来源和实际处置回执。 | 实例或原业务关键词、状态；只有来源菜单与读取权均有时下钻。 | P1 前端；独立读取权限 |
| **L20 营销旅程**<br>`/#journeys`<br>`/admin/journeys` · [Journeys](../../../frontend/src/features/Journeys.tsx) | 营销负责人从触发、资格与节点判断可否发布旅程。 | 定义、预览、扫描及实例属于不同任务，入口间关联偏弱。 | 首屏固定旅程名、触发、状态、已生效版；复杂编辑按触发→资格→节点→频控分段，预览给受理证据；实例与扫描由原版跳转。 | 旅程名称/编号与真实状态；版本差异服务端无数据时按支持版本显示。 | P2 前端；版本跳转读契约核对 |
| **L21 旅程实例**<br>`/#instances`<br>`/admin/journey-instances` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 运营从实例辨认正在等待、已落地动作或实际失败阶段。 | 旧入口仍以实例列表为主，原步骤证据容易留在当前页之外。 | 实例号、旅程固定版、会员、阶段、下一尝试放前；短详情展示原历史覆盖，详细过程按真实步骤游标。 | 实例号/状态和实际时段筛选；实例历史复用现有契约，控制独立。 | P1 前端复用中央固定历史 |
| **C14 活动管理**<br>`/operations/campaigns`<br>`/admin/campaigns` · [Directory](../../../frontend/src/iam/CentralCampaigns.tsx) | 营销负责人选定活动版，判断时效、资格、可用钱和可发资产。 | 已有审批、预算下钻和模拟，仍需要把决定所需事实放同一屏。 | 活动/门店、状态、投放窗口、内容版；详情由运营摘要进入版本、资格、优惠、预算与审批。发布前展示同版检查，预览注明模拟。 | 名称/ID、状态；预算、名单和资产分别核权，查看原版。 | P1 前端＋批量预算摘要读契约 |
| **C15 活动预算**<br>`/operations/campaign-budgets`<br>`/admin/campaign-budgets` · [Budgets](../../../frontend/src/iam/CentralCampaigns.tsx) | 财务按活动版看预算上限、实际占用和已消耗。 | 按活动精准定位已有，缺同版风险提示和资金口径的优先顺序。 | 上限、预占、已耗与可用同屏，标币种及读取时点；列表按当前授权，调整预算用版本及差异确认。 | 活动号、预算关键词；低余额阈值需业务确认后提供真实筛选。 | P1 前端；阈值及完整统计另立契约 |
| **C16 人群快照**<br>`/operations/audiences`<br>`/admin/audiences` · [Directory](../../../frontend/src/iam/CentralAudiences.tsx) | 人群运营按真正可用的固定快照筛选资产。 | 现有时点/范围/版本详情，上层目录缺突出可用性。 | 目录优先名称、版本、人数、数据时点和有效至；历史详情说明范围及过期事实，导入回执只返回真实汇总。 | 编号/名称/来源；有效窗口优先用于判读，查询过滤沿 Owner。 | P1 前端 |
| **C17 动态人群**<br>`/operations/segments`<br>`/admin/segments` · [Directory](../../../frontend/src/iam/CentralSegments.tsx) | 人群运营核对运行是否产出可用快照，追踪原失败批次。 | 已可定位输出，定义、近期健康和实际产出仍分散。 | 目录突出最近可用版和刷新时间；详细页按周期配置/运行历史/输出版本；失败行直接回原批次重试范围。 | 名称/定义号、启停；运行按实际状态/游标，摘要由有界读取。 | P1 前端＋最近结果读契约 |
| **C18 动态规则**<br>`/operations/rules`<br>`/admin/rules` · [Directory](../../../frontend/src/iam/CentralRules.tsx) | 营销运营确认表达式按预期筛选和绑定合法版本。 | 审批需要能够阅读层级条件，技术字段易误判。 | 列表展示规则名、内容版本、状态；详情业务句与结构树并存，校验逐字段定位，发布确认说明实际版本和范围。 | 规则名称/编号、发布状态；需要模拟时沿已有活动报价预览能力。 | P2 前端 |
| **C19 优惠券定义**<br>`/operations/coupon-definitions`<br>`/admin/coupon-definitions` · [DefinitionList](../../../frontend/src/iam/CentralCouponDefinitions.tsx) | 券运营查可发行的门店券与实际额度。 | 固定版本读已交付，范围/门槛/期限的层级可继续优化。 | 发行方式、门店、有效期、已发额度优先；同名券用门店与版本辨认；固定版本弹窗分购买门槛、分摊和叠加。 | 门店与名称/编号；发行方式若无服务器条件保留为展示字段。 | P1 前端 |
| **C20 定向发券**<br>`/operations/coupon-deliveries`<br>`/admin/coupon-deliveries` · [Directory](../../../frontend/src/iam/CentralCouponDeliveries.tsx) | 发券负责人判断进度、失败影响、停止或撤回的实际后果。 | 多种持久结果同表，按方向读更高效。 | 首屏批次与固定定义/人群摘要，预览目标人数与当前限制；状态区区分实际发放、跳过、撤回、保留。逐人结果使用现有权限。 | 门店、批次号/状态；保留同任务方向、游标及原批次幂等重试。 | P1 前端；名单脱敏及预检读契约 |
| **C21 权益定义**<br>`/operations/entitlement-definitions`<br>`/admin/entitlement-definitions` · [DefinitionList](../../../frontend/src/iam/CentralEntitlementDefinitions.tsx) | 权益配置岗查定义版本、生效时点和实际单位。 | 首屏能浏览定义，顾名辨识相同名称与跨版需要辅助。 | 第一列名称/编号/版本，单位、发放后有效天数与时窗；新增页用分组必填并显示类型真实边界。 | 门店、名称/编号；展示缺失字段为明确未知。 | P2 前端 |
| **C22 权益台账**<br>`/operations/entitlements`<br>`/admin/entitlements` · [Directory](../../../frontend/src/iam/CentralEntitlements.tsx) | 权益处置岗结合来源、权益债务和真实回执裁定补偿。 | 台账有来源和状态，原因与下一动作可放到原对象旁。 | 来源类型及原号、状态、剩余和债务、回执时间与版本；补偿确认把原实例和原因相邻，保留 独立权限。 | 实例/原订单/会员关键词、状态；受权原对象跳转。 | P1 前端 |
| **C23 旅程定义**<br>`/operations/journeys`<br>`/admin/journeys` · [Workspace](../../../frontend/src/iam/CentralJourneys.tsx) | 旅程编排岗确认固定版的触发、资格、节点与发布窗。 | 目录、校验、预览和发布都有入口，但版本语义需要置顶。 | 目录列版本、触发、状态、窗口；多步骤配置走完整路由，画布预览和节点错误相互定位；测试输出写明输入和作用域。 | 旅程名称/编号、状态；原实例和扫描各自使用固定定义版。 | P2 前端；版本定位读契约 |
| **C24 旅程实例**<br>`/operations/journey-instances`<br>`/admin/journey-instances` · [Workspace](../../../frontend/src/iam/CentralJourneys.tsx) | 值班运营按原实例判断已执行的不可逆步骤和后续等待。 | 固定历史可读，摘要行仍可更聚焦判断。 | 实例/版本/状态/下一处理时间放前；详情上层展示覆盖范围和原步骤，按最后实际版本翻页；取消结果注释实际影响。 | 精确实例号与原状态；历史游标、范围封闭和撤权复用。 | P1 前端 |
| **C25 生命周期扫描**<br>`/operations/journey-scans`<br>`/admin/journey-scans` · [Workspace](../../../frontend/src/iam/CentralJourneys.tsx) | 生命周期值班员从扫描结果定位滞后或停止。 | 单次运行、调度与业务触发容易混为同一种作业。 | 显示定义/版本、窗口和状态、已扫描/实际处理/下一步；只有可恢复时给原重试，详情展示检查点与回执。 | 原定义ID/版本、状态和已有游标；时间列写清源与含义。 | P2 前端 |
| **C26 营销效果**<br>`/operations/marketing-effects`<br>`/admin/marketing-effects` · [Workspace](../../../frontend/src/iam/CentralJourneys.tsx) | 商业化负责人比较活动资金与实际成交、旅程观察及券贡献。 | 三个系列的时间起点、可比较范围和优惠成本分散。 | 顶部同屏展示门店、范围、数据更新时间及覆盖；活动以净收/优惠/资方为主，旅程以成熟与触达为先，券以实际使用订单为先。 | 各系列原窗口、游标。下钻沿原活动版、旅程版或批次。 | P1 前端；同口径趋势与分母需读契约 |
| **C27 营销执行**<br>`/operations/marketing-executions`<br>`/admin/marketing-executions` · [Workspace](../../../frontend/src/iam/CentralJourneys.tsx) | 营销支持按原单查活动版与当前权益履约异常。 | 已有准确编号和失败分类，列表中仍需区别执行和权益事实。 | 先订单与活动/版本、门店、优惠、参与状态；详情独立展示权益落地、最新事件和事实时点，按原键关联。 | 精确订单、活动关键词、事件失败分类；事实读取失败单独反馈。 | P1 前端 |

## 交易、支付及交付

| 页面与主要数据入口 | 定位与运营判断 | 当前不足 | 调整后的首屏与详情 | 默认字段与筛选 | 优先级与范围 |
| --- | --- | --- | --- | --- | --- |
| **L22 订单工作台**<br>`/#orders`<br>`/admin/orders` · [Orders](../../../frontend/src/features/Orders.tsx) | 订单岗核对具体门店、应付、付款及后续协作。 | 门店精确过滤已交付，首屏可进一步突出下一步和相邻事实。 | 首列订单号与店名，状态、金额、创建时间；详情按原订单、支付、履约、售后关系展示有权限的入口，优先处理中状态。 | 精确门店/订单、状态、日期折叠；金额与支付信息按独立权限。 | P1 前端；批量状态摘要另立读契约 |
| **L23 履约队列**<br>`/#fulfillments`<br>`/admin/fulfillments` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 履约岗找可发、被阻拦和已送达的原订单。 | 阻拦过滤已具备，阻拦原因、后续动作还可更近。 | 订单号/店铺、履约状态、阻拦、运单、仓配渠道；默认工作视图与历史视图区分；发货短表单紧邻实际当前详情。 | 门店/订单、阻拦与状态；按原单进入售后或订单。 | P1 前端；预计送达需真实仓配 |
| **L24 售后审批**<br>`/#aftersales`<br>`/admin/aftersales` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 售后审核岗判断申请如何处理、是否需要收件。 | 当前详情可读取商品，首次判断需在状态和后续动作间切换。 | 先待审、待收件、退款核对快捷视图；列表前置原单/门店、申请状态、金额；详情分申请、商品和退款，决策确认讲明下一状态。 | 门店/订单、申请状态、既有关键词；申请时间列表缺字段时补只读投影。 | P1 前端＋申请龄读契约 |
| **L25 退款核对**<br>`/#refunds`<br>`/admin/refunds` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 财务找到原退款，判定仍未知还是已成功。 | 退款详情可看原申请时间；列表需区分渠道未知与正常等待。 | 退款号/原订单、店铺、金额、渠道、状态放前；把核对放在同一原退款详情，显示最近可信结果和已保存的时间。 | 门店/订单、未知/成功、原业务关键词；真实核对时点字段核实后可筛。 | P1 前端；最后核对时点需读契约 |
| **C28 订单**<br>`/operations/orders`<br>`/admin/orders` · [CentralOrders](../../../frontend/src/iam/CentralOrders.tsx) | 订单运营从已授权交易事实判断下一工作项。 | 交易列偏好和门店过滤已有，支付、售后、履约入口可更好编排。 | 常驻订单/实际门店/状态/应付/下单时间，其余按偏好；原订单详情保留完整商品与受权链接。 | 精确门店/订单、状态、日期、既有关键词；保存视图仅保存原支持字段。 | P1 前端；列表跨域摘要需 Owner 契约 |
| **C29 支付**<br>`/operations/payments`<br>`/admin/orders/{order}/payment` · [PaymentRead](../../../frontend/src/iam/CentralPayments.tsx) | 支付财务凭原订单核对单笔渠道事实。 | 当前按编号读取，期望很容易把状态查询当重新付款。 | 精确单号输入，顶部对照原订单、支付编号、金额、币种与可信状态。把核对放短确认，原结果和当前渠道分别展示。 | 只支持原订单号；新增支付总表/渠道筛选要独立受权读契约。 | P1 前端；支付队列属后续读契约 |
| **C30 履约**<br>`/operations/fulfillments`<br>`/admin/fulfillments` · [CentralFulfillments](../../../frontend/src/iam/CentralFulfillments.tsx) | 履约岗按门店、原单和阻拦判断可否登记发货。 | 当前详情和阻拦条件已有，判断所需的留置原因需优先。 | 行首实际门店、订单、履约状态和阻拦；运单与渠道后列；短发货确认就地展示同一订单及核权结果。 | 精确店铺、原订单、状态、阻拦；对只写岗给精确入口。 | P1 前端；承诺送达需仓配数据 |
| **C31 售后**<br>`/operations/aftersales`<br>`/admin/aftersales` · [CentralAftersales](../../../frontend/src/iam/CentralAftersales.tsx) | 售后审核按申请证据决定批准、驳回或收件。 | 实时详情已有，默认列和决策动作之间仍有阅读距离。 | 列表按待审核、待收件、退款核对给快捷视图；详情先申请事实和商品行，再决策；带入原退款/订单按独立读取。 | 精确店/订单、申请/退款编号与状态，不能以采集缺时点判断超期。 | P1 前端；申请龄投影需读契约 |
| **C32 退款**<br>`/operations/refunds`<br>`/admin/refunds` · [CentralRefunds](../../../frontend/src/iam/CentralRefunds.tsx) | 财务在结算/退款异常时核对原退款证据和金额。 | 现有精确读取及当前详情，最近可信回执和未知的区别可强化。 | 退款/原单/店铺、渠道、金额和状态为主列；详情先待核对、提交时间与原对象。保留同一退款命令意图。 | 精确门店/订单与退款关键词、真实状态；依现有接口返回游标。 | P1 前端；最近渠道核对时点需读契约 |

## 运营配置与平台作业

| 页面与主要数据入口 | 定位与运营判断 | 当前不足 | 调整后的首屏与详情 | 默认字段与筛选 | 优先级与范围 |
| --- | --- | --- | --- | --- | --- |
| **L26 低代码页面**<br>`/#pages`<br>`/admin/ops-pages` · [OpsPages](../../../frontend/src/features/OpsPages.tsx) | 配置人员验证页面组件与动作，发布正确的版本。 | 草稿、结构编辑、预览和发布动作在相邻区域，缺目标对照。 | 列表先页面/门店、当前内容版和发布态；复杂结构用全宽工作区，预览面板显示实际只读数据及已允许动作，发布短确认核对原版与后果。 | 页面号/名称、门店关键词、状态；历史版本只读。 | P2 前端；版本对比先验契约 |
| **L27 异步事件**<br>`/#events`<br>`/admin/events` · [AdminData](../../../frontend/src/features/AdminData.tsx) | 值班员按原业务号找到异步异常证据。 | 精确业务号已实现，事件要素、失败类别与回收路径需要更近。 | 事件类型/业务号、状态、失败类别、下次尝试；详情先因果对象和最新可核对事实，载荷仅限授权排查。 | 来源精确号、状态、关键词；失败分类仅在当前接口支持时启用。 | P1 前端 |
| **L28 任务恢复**<br>`/#recovery`<br>`/admin/runtime/stopped` · [Recovery](../../../frontend/src/features/RuntimeOperations.tsx) | 故障处置人员恢复原停止任务并查同一笔审计。 | 原任务恢复审计已交付；操作原因、预检和事后回执可集中。 | 按工作类型先选停止列表；原工作/停止原因/重试条件放前，恢复短确认给确切目标，操作后回原审计条件。 | 实际工作类型、失败分类与原工作号；按审计游标续查。 | P1 前端 |
| **L29 历史重放**<br>`/#replay`<br>`/admin/runtime/replays` · [Replay](../../../frontend/src/features/RuntimeOperations.tsx) | 平台值班验证历史处理影响后发起限定重放。 | 有试运行与状态，但输入、审批动作和持久产物需要更清楚地对照。 | 先原消费者、模式、范围和安全分类，再试运行、发起和任务列表；详情显示扫描、实际执行、跳过及失败和下一动作。 | 消费者/任务编号/模式，按原时窗和事件范围；仅显示实际支持的暂停等动作。 | P2 前端 |
| **C33 运营页面**<br>`/operations/ops-pages`<br>`/admin/ops-pages` · [PageRead](../../../frontend/src/iam/CentralOpsPages.tsx) | 低代码运营从当前版本校验真实组件数据和页动作。 | 筛选已绑定 HTTP，独立动作及多个版本的结果不够同屏。 | 状态、门店、当前版与锁版本作为定位；目录 → 配置 → 只读预览 → 审批/发布，复杂操作保持原版和范围。 | 真实 q/status/limit/after，版本历史单独读；变更后回原条件重读。 | P2 前端 |
| **C34 事件处理**<br>`/operations/events`<br>`/admin/events` · [CentralEvents](../../../frontend/src/iam/CentralEvents.tsx) | 中央平台值班按原业务定位异步事件与失败原因。 | 聚合号及原订单可达，列表错误状态仍需更具体。 | 列当前状态、业务键、类型、失败类、计划重试时点；详情只显最小安全摘要，原 Owner 跳转依独立菜单。 | 精确聚合号、业务关键词、状态；失败筛选需接口真实支持。 | P1 前端 |
| **C35 运行恢复**<br>`/operations/runtime`<br>`/admin/runtime/stopped` · [RuntimeRead](../../../frontend/src/iam/CentralRuntime.tsx) | 运维依原工作类型、状态和权限恢复后台任务。 | 停止作业、原审计、重放试运行各有协议，混看易误重试。 | 三个任务分区；停止项有原号及最近错误，审计列原作业和结果，重放显示实际试运行范围与有界副作用。 | 工作类型/失败分类、精确工作号、对应游标；权限与未知状态按原契约。 | P1 前端 |
| **P01 运行健康**<br>`/#platform-runtime`<br>`/platform/runtime` · [PlatformRuntime](../../../frontend/src/features/PlatformRuntime.tsx) | 平台值班员判断哪条运行车道需要排查。 | 技术指标多，队列积压与可处理业务的入口需要显式区分。 | 先采样时间、队列积压和最老任务，再租户公平性、失败与恢复；受权原工作链接单独显示。 | 按现有车道/来源筛，限定聚合范围；可配置 SLO 作为后续。 | P2 前端；敏感聚合与 SLO 治理 |

## 会员自助

| 页面与主要数据入口 | 定位与运营判断 | 当前不足 | 调整后的首屏与详情 | 默认字段与筛选 | 优先级与范围 |
| --- | --- | --- | --- | --- | --- |
| **M01 逛店铺**<br>`/#shop`<br>`/catalog/search` · [Shop](../../../frontend/src/features/Shop.tsx) | 买家比较可下单的规格，确认每笔实付。 | 购买页面已有商品与报价，资格、门槛、有效期和付款之间仍需读懂。 | 店铺和搜索在前，商品卡展示真实图片/价格/可购规格。结算从商品、优惠到应付逐行列示，清晰展示报价过期后的重新报价。 | 店铺、商品/规格关键词；下单后进入原订单，沿真实服务端价格。 | P1 前端 |
| **M02 我的订单**<br>`/#orders`<br>`/orders` · [Orders](../../../frontend/src/features/Orders.tsx) | 买家查发货、付款或售后进度。 | 列表显示基础状态，资金未知、实际履约和售后待办容易混读。 | 原单号、店名、状态和应付在主单元，后面是下单时间；按条件显示付款或原单售后，仅当前身份查看。 | 门店、状态、订单号和日期；详情返回原筛选、页与焦点。 | P1 前端 |
| **M03 优惠券中心**<br>`/#coupons`<br>`/coupons` · [MemberWallet](../../../frontend/src/features/MemberWallet.tsx) | 买家判断手中优惠券何时可用。 | 券的领取和已拥有信息可能混在同屏，门槛需要操作前理解。 | 区分可领取和我的券；卡片突出面额、使用门槛、有效期及范围，吊销/占用单列词义；领取后重读钱包。 | 实际会员券状态、门店定义查询；受控券资格依服务端。 | P2 前端 |
| **M04 我的权益**<br>`/#benefits`<br>`/entitlements` · [MemberWallet](../../../frontend/src/features/MemberWallet.tsx) | 买家核对本人权益余额、有效期及可用动作。 | 权益名称与余额需优先于原业务技术编号。 | 卡片展示可用单位、有效期和使用状态；详情放来源提示与本人可见消费记录，扣减命令反馈实际余额。 | 本人资产状态和已有关键词；过期详情保持可查。 | P2 前端 |
| **M05 我的成长**<br>`/#growth`<br>`/members/me/growth/ledger` · [MemberGrowth](../../../frontend/src/features/MemberGrowth.tsx) | 买家理解当前等级、成长来源与周期状态。 | 成长、积分与周期权益易当成同一种余额。 | 等级、成长、周期及积分分组，当前数值和到期信息置前；流水标明增减、原因、时点，升级/保级说明沿当前策略。 | 按实际分页和时间入口，切页保留当前本人上下文。 | P2 前端 |
| **M06 售后记录**<br>`/#aftersales`<br>`/aftersales` · [MemberWallet](../../../frontend/src/features/MemberWallet.tsx) | 买家判断售后申请是否需要补交退货或等待退款。 | 申请状态、原订单和渠道进度有必要更明确地关联。 | 主列售后号、原订单、状态、金额、提交时间（有则）。详情用已完成的实际阶段串联退货与退款，可回原订单。 | 订单号精确、售后状态；无渠道结果显示待核对。 | P1 前端；缺时间须读契约 |
| **M07 站内消息**<br>`/#notifications`<br>`/notifications` · [MemberWallet](../../../frontend/src/features/MemberWallet.tsx) | 买家从通知回到合法的原业务。 | 纯通知列表缺少清晰对象关系和动作指引。 | 标题、实际发生时间、正文摘要，只有事件类型与目标确证才给原对象链接；普通通知保留可读内容。 | 沿用消息游标；未读/批量动作只有存在对应个人状态契约才加入。 | P2 前端；读标记需契约 |
