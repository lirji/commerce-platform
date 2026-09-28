# 能力缺口复核证据索引

- generated_at：2026-09-27；workspace：`/Users/liruijun/personal/LLM/commerce-platform`
- baseline：`7dda31ed017617f3701480f8a2d1620656c843cc`；开始时main与origin/main一致、无已有脏改动。
- permission_followup：`afc5e940c1dd988759880ad9f03106e68170ad0e`；该报告提交已发布main，补充复核开始时工作树干净，产品代码仍同原基线。
- protocol：`engineering-baseline/v1`、`skill-contract/v1`、`capability-exploration-report/v1`
- artifact：CAPABILITY_EXPLORATION_REPORT；task：CE-20260927。
- scope：源码/配置/SQL与测试源码交叉检查；历史验收只归入历史证据。本轮未启动应用、写业务库、重跑产品测试/压测或联调渠道。

## Exploration Summary

现有本地会员/商品/营销与交易能力已明显完善；主要剩余是中台复用、持续购物、运营及长期运行治理。先补这些目标，当前不建议通用BPM、全量拆服务或无依据新中间件。五份报告为本次唯一业务分析产物，正式设计/契约/旧证据不变。

## Skill Sources

| 实际读取入口 | 本轮用途 |
|---|---|
| `~/.claude/skills/project-capability-exploration/SKILL.md`、`references.md`、`output-schema.md` | 主流程Phase 0–17、9维能力、四分类候选、触发条件、五份产物 |
| `~/.claude/skills/project-capability-discovery/SKILL.md`、`references.md` | 业务/平台/技术真实性、模块与链路扫描方法 |
| `~/.claude/skills/project-deep-analysis/SKILL.md`、`scan-checklist.md` | 事务、幂等、状态、失败恢复与数据模型的证据扫描 |
| `~/.claude/skills/architecture-reviewer/SKILL.md`、`checklist.md` | 条件风险/故障维度；只复用方法，不重新写其历史风险报告 |

这些Claude技能入口是指向`~/.cursor/skills`的链接；已经从Claude入口实际读取内容，不是复制名称，也不声称调用了Claude模型。目录中没有`~/.claude/dev-standards.md`；本轮按用户提供的AGENTS规范执行，未修改全局规范或技能。

报告同步进度及Git交付分别由`~/.claude/skills/update-progress-docs`与用户指定的`~/.codex/skills/task-git-delivery`负责，授权来源为本会话AGENTS Git规则8。分析技能本身没有扩大为产品实施或生产部署。

## Evidence Index

表中Finding是由该证据支持的结论；Class/Method、Configuration、SQL·Table按实际类型填写，`—`表示不适用。链接相对当前报告目录。

| ID | Finding / Capability | Evidence Type / File | Class / Method | Configuration / SQL·Table | Confidence |
|---|---|---|---|---|---|
| E01 | 模块化单体基线/精确金额 | build/domain：[pom](../../pom.xml)、[Money](../../shared-kernel/src/main/java/com/lrj/commerce/kernel/Money.java) | Money | 19 Maven modules；单Boot启动 | FACT |
| E02 | 三值规则、确定性竞争、合法订单迁移已有 | domain：[RuleEvaluator](../../marketing/src/main/java/com/lrj/commerce/marketing/domain/RuleEvaluator.java)、[CampaignConflictResolver](../../marketing/src/main/java/com/lrj/commerce/marketing/domain/CampaignConflictResolver.java)、[OrderLifecycle](../../order/src/main/java/com/lrj/commerce/order/domain/OrderLifecycle.java) | evaluate / bestOf / apply | — | FACT |
| E03 | 命令、防重回执/审计与业务共事务 | Java/SQL：[Commands](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/command/Commands.java)、[CommandMapper](../../platform-runtime/src/main/resources/mappers/runtime/CommandMapper.xml) | Commands.run | platform_command/request_hash/response_json；platform_audit | FACT |
| E04 | 内部Outbox/Inbox、消费者事务隔离与失败分类已有 | Java/SQL/test：[EventDispatcher](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/event/EventDispatcher.java)、[EventMapper](../../platform-runtime/src/main/resources/mappers/runtime/EventMapper.xml)、[隔离测试源码](../../commerce-app/src/test/java/com/lrj/commerce/app/EventConsumerIsolationTest.java) | tick/pump与消费者逐项事务（实现） | platform_event/platform_inbox；非独立Broker | FACT（源码），本轮执行UNVERIFIED |
| E05 | 会员绑定与状态历史已有；关闭不删除资料 | API/Java/SQL：[MemberApi](../../member/src/main/java/com/lrj/commerce/member/profile/api/MemberApi.java)、[MemberService](../../member/src/main/java/com/lrj/commerce/member/profile/application/MemberService.java)、[MemberMapper](../../member/src/main/resources/mappers/member/MemberMapper.xml)、[V16](../../commerce-app/src/main/resources/db/migration/V16__member_lifecycle.sql) | create/current/change/history | member_record、member_change；ACTIVE/FROZEN/CLOSED | FACT |
| E06 | 本地成长、周期、积分有实现，不是缺失 | Java/API：[MemberGrowthService](../../member/src/main/java/com/lrj/commerce/member/growth/application/MemberGrowthService.java)、[MemberCycleApi](../../member/src/main/java/com/lrj/commerce/member/cycle/api/MemberCycleApi.java)、[MemberPointsApi](../../member/src/main/java/com/lrj/commerce/member/points/api/MemberPointsApi.java)、[PointsSpendService](../../member/src/main/java/com/lrj/commerce/member/points/application/PointsSpendService.java) | observe/contribute/assess/reserve/refund | member_growth_*、member_cycle_*、member_point_*；本地订单事实 | FACT |
| E07 | 标签/行为和会员偏好已有；非完整身份平台 | API/Java：[MemberBehaviorApi](../../member/src/main/java/com/lrj/commerce/member/behavior/api/MemberBehaviorApi.java)、[MemberBehaviorService](../../member/src/main/java/com/lrj/commerce/member/behavior/application/MemberBehaviorService.java)、[MemberTagService](../../member/src/main/java/com/lrj/commerce/member/tag/application/MemberTagService.java) | record/projectOrder/facts/profile/assign | BROWSE/ADD_TO_CART；birthday/journeyEnabled | FACT |
| E08 | 商品门店/商家资源授权已有，商家只有基础资料 | API/Java：[StoreAccessService](../../store/src/main/java/com/lrj/commerce/store/access/application/StoreAccessService.java)、[MerchantApi](../../merchant/src/main/java/com/lrj/commerce/merchant/api/MerchantApi.java) | requireCatalog/create/change | store_operator_grant；permission=CATALOG | FACT |
| E09 | 商品主档/SKU与店铺绑定，已支持规格版本 | API/SQL：[ProductOperationsApi](../../catalog/src/main/java/com/lrj/commerce/catalog/product/api/ProductOperationsApi.java)、[ProductMapper](../../catalog/src/main/resources/mappers/catalog/ProductMapper.xml)、[V18](../../commerce-app/src/main/resources/db/migration/V18__product_specifications_and_revisions.sql) | create/variant/change/history | catalog_product/store_id、catalog_sku、catalog_revision | FACT；共享主档缺口INFERRED |
| E10 | 定时上下架/调价可恢复，单job≤100目标 | API/Java：[CatalogJobApi](../../catalog/src/main/java/com/lrj/commerce/catalog/job/api/CatalogJobApi.java)、[CatalogJobService](../../catalog/src/main/java/com/lrj/commerce/catalog/job/application/CatalogJobService.java) | create/control/tick | PRICE/PUBLISH/UNPUBLISH；catalog_operation_job/item | FACT |
| E11 | 类目模板、图片URL、条码和渠道检索已有 | API/Java/SQL：[CatalogMerchandisingApi](../../catalog/src/main/java/com/lrj/commerce/catalog/merchandising/api/CatalogMerchandisingApi.java)、[CatalogMerchandisingService](../../catalog/src/main/java/com/lrj/commerce/catalog/merchandising/application/CatalogMerchandisingService.java)、[MerchandisingMapper](../../catalog/src/main/resources/mappers/catalog/MerchandisingMapper.xml)、[ChannelPriceApi](../../catalog/src/main/java/com/lrj/commerce/catalog/pricing/api/ChannelPriceApi.java) | changeProfile/picture/search/change | picture HTTPS或/media；LOCATE检索；channel认证决定 | FACT；文件中心缺口INFERRED |
| E12 | 活动固定引用、预览、审批/发布及单权益门禁 | Java/API：[CampaignService](../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/management/application/CampaignService.java)、[MarketingAssetService](../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/asset/application/MarketingAssetService.java) | create/review/publish/preview/candidates | CREDIT/COUPON互斥；同时有效候选≤100；审批能力 | FACT |
| E13 | 动态人群持久刷新已有；固定名单单批≤500 | API/Java：[SegmentApi](../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/segment/api/SegmentApi.java)、[SegmentService](../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/segment/application/SegmentService.java)、[MarketingAssetService](../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/asset/application/MarketingAssetService.java) | create/batch/checkpoint/refresh/control/createAudience | 每批100；maxMembers预算≤100000；TTL；未完成不发布 | FACT；真实大租户持续容量NEEDS_VERIFICATION |
| E14 | 券/权益钱包、预留和退款补偿已有 | Java：[CouponService](../../benefit/src/main/java/com/lrj/commerce/benefit/coupon/application/CouponService.java)、[EntitlementService](../../benefit/src/main/java/com/lrj/commerce/benefit/entitlement/application/EntitlementService.java) | reserve/confirm/refund/confirmCampaign/reverseOrder | benefit_coupon/hold、benefit_campaign_coupon_hold、benefit_grant/ledger | FACT |
| E15 | 等级礼包受理与兑换已有，到账按原权益事件逐项推进 | Java/API：[MemberBenefitService](../../benefit/src/main/java/com/lrj/commerce/benefit/memberbenefit/application/MemberBenefitService.java)、[PointOfferService](../../benefit/src/main/java/com/lrj/commerce/benefit/pointoffer/application/PointOfferService.java)、[MemberPointsApi](../../member/src/main/java/com/lrj/commerce/member/points/api/MemberPointsApi.java) | award/grant/handle/redeem/exchange | 礼包1–8项；来源唯一；受理事务与异步可用分开 | FACT；客户全礼包承诺UNKNOWN |
| E16 | 保留机制完成、默认关闭，仅指定数据类 | Java/SQL/config：[RetentionLane](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/retention/RetentionLane.java)、[RetentionMapper](../../platform-runtime/src/main/resources/mappers/runtime/RetentionMapper.xml)、[application](../../commerce-app/src/main/resources/application.yml) | tick/events/lag | DELIVERED_EVENTS/SKIPPED_EVENTS/COMMANDS；保护active replay；enabled=false | FACT；具体保留政策UNKNOWN |
| E17 | 独立业务车道共享有界池，非全部串行 | Java/config：[EventWorker](../../commerce-app/src/main/java/com/lrj/commerce/app/runtime/scheduling/EventWorker.java)、[application](../../commerce-app/src/main/resources/application.yml) | configureTasks/taskScheduler | 10业务车道＋replay/retention；默认3后台线程/8连接；workers默认false、compose明确true | FACT |
| E18 | 逐项恢复、审计、受控历史重放与公平轮转已有 | Java：[RuntimeRecovery](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/recovery/RuntimeRecovery.java)、[ReplayGate](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/replay/ReplayGate.java)、[WorkLanes](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/work/WorkLanes.java)、[TenantRotation](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/work/TenantRotation.java) | recover/recoverOne及安全门 | platform_recovery/platform_replay；领域原项恢复，不全程副作用replay | FACT |
| E19 | 报价/成交快照与事务预占已有；没有运费模型 | API/Java：[QuoteApi](../../trade/src/main/java/com/lrj/commerce/trade/api/QuoteApi.java)、[QuoteService](../../trade/src/main/java/com/lrj/commerce/trade/application/QuoteService.java)、[OrderService](../../order-runtime/src/main/java/com/lrj/commerce/ordering/order/application/OrderService.java) | create/consume/settle | 商品、优惠、积分分摊；单店CNY；事务库存/预算/券/积分预留 | FACT；运费完整能力缺失INFERRED |
| E20 | 库存为可售额度，非多仓实物权威 | API/SQL：[InventoryApi](../../inventory/src/main/java/com/lrj/commerce/inventory/api/InventoryApi.java)、[InventoryMapper](../../inventory/src/main/resources/mappers/inventory/InventoryMapper.xml) | receive/reserve/confirm/release/returnItems | store/sku；available/held/sold，条件更新 | FACT |
| E21 | 退款保留UNKNOWN与额度；缺真拒绝生命周期 | API/Java/SQL：[RefundApi](../../payment/src/main/java/com/lrj/commerce/payment/refund/api/RefundApi.java)、[RefundService](../../payment/src/main/java/com/lrj/commerce/payment/refund/application/RefundService.java)、[RefundChannel](../../payment/src/main/java/com/lrj/commerce/payment/refund/application/port/RefundChannel.java)、[V5](../../commerce-app/src/main/resources/db/migration/V5__fulfillment_returns_refunds.sql) | request/reconcileInternal/tick | UNKNOWN/SUCCEEDED；非成功proof保持UNKNOWN；payment退款预留 | FACT；真拒绝业务处理INFERRED/政策UNKNOWN |
| E22 | 单履约单号、部分退货退款已有，真实仓储后置 | API/Java：[FulfillmentApi](../../fulfillment/src/main/java/com/lrj/commerce/fulfillment/api/FulfillmentApi.java)、[SandboxWms](../../fulfillment/src/main/java/com/lrj/commerce/fulfillment/infrastructure/adapter/SandboxWms.java)、[AftersaleApi](../../aftersales/src/main/java/com/lrj/commerce/aftersales/api/AftersaleApi.java) | ship/deliver/request/receiveReturn/fullyReturned | SANDBOX_WMS；case/SKU数量、金额和积分；一order履约 | FACT |
| E23 | Journey固定版本、当前事实、历史Truth/actionRef及站内通知 | API/domain/Java/SQL：[JourneyApi](../../marketing-automation/src/main/java/com/lrj/commerce/journey/api/JourneyApi.java)、[JourneyService](../../marketing-automation/src/main/java/com/lrj/commerce/journey/application/JourneyService.java)、[JourneyGraph](../../marketing-automation/src/main/java/com/lrj/commerce/journey/domain/JourneyGraph.java)、[JourneyActions](../../marketing-automation/src/main/java/com/lrj/commerce/journey/application/JourneyActions.java)、[V44](../../commerce-app/src/main/resources/db/migration/V44__journey_step_execution_history.sql) | validate/preview/execute/history | 32节点/单路径；WAIT/DECIDE/GRANT/COUPON/NOTIFY/END；不复制完整Facts | FACT |
| E24 | 阶段8真实kill/多JVM/旧新兼容及到期量测 | historical evidence：[报告](../../docs/evidence/phase8-marketing-journey/PHASE8_REPORT.md)、[scale.csv](../../docs/evidence/phase8-marketing-journey/results/scale.csv)、[规模结果](../../docs/evidence/phase8-marketing-journey/results/scale-results.md)、[进程恢复结果](../../docs/evidence/phase8-marketing-journey/results/process-recovery-results.md) | 真实scheduler_due_drain、hot/normal公平性、process recovery | 100=6.127s/1000=48.759s/10000=469.990s；P95(10000)=446891ms；共享dev_infra | FACT（归档结果）；生产SLO NEEDS_VERIFICATION |
| E25 | 定向发券批次/逐项结果/撤销控制已有 | API/Java：[CouponDeliveryApi](../../marketing-automation/src/main/java/com/lrj/commerce/journey/delivery/api/CouponDeliveryApi.java)、[CouponDeliveryService](../../marketing-automation/src/main/java/com/lrj/commerce/journey/delivery/application/CouponDeliveryService.java) | create/control/recipients/tick | automation_coupon_batch/recipient/frequency | FACT |
| E26 | 经营效果为描述性读模型，明确非因果ROI/利润 | API/Java/SQL：[MarketingEffectsApi](../../marketing-automation/src/main/java/com/lrj/commerce/insight/api/MarketingEffectsApi.java)、[MarketingEffectsService](../../marketing-automation/src/main/java/com/lrj/commerce/insight/application/MarketingEffectsService.java)、[EffectsMapper](../../marketing-automation/src/main/resources/mappers/insight/EffectsMapper.xml) | report/journeys/deliveries/rebuild/project | 本地OrderApi/QuoteApi/RefundApi；≤93天；退款/覆盖/成本口径 | FACT |
| E27 | 低代码白名单和审批已有，角色仍ADMIN | Java：[OpsPageService](../../marketing-automation/src/main/java/com/lrj/commerce/ops/application/OpsPageService.java) | change/preview/render/execute | requireAdmin；APPROVED/PUBLISHED，固定组件 | FACT |
| E28 | 低基数指标已有，非只有health | Java：[BackgroundLaneMetrics](../../commerce-app/src/main/java/com/lrj/commerce/app/observability/lane/BackgroundLaneMetrics.java)、[EventRuntimeMetrics](../../commerce-app/src/main/java/com/lrj/commerce/app/observability/event/EventRuntimeMetrics.java) | bindTo | commerce.lanes.* / commerce.events.*；固定lane标签，含积压/最老/隔离 | FACT |
| E29 | 平台聚合与告警代码已有；默认仅日志送出 | Java/config：[BackgroundRuntime](../../commerce-app/src/main/java/com/lrj/commerce/app/runtime/monitoring/BackgroundRuntime.java)、[AlertConfiguration](../../commerce-app/src/main/java/com/lrj/commerce/app/configuration/alert/AlertConfiguration.java)、[OperationalAlertPublisher](../../commerce-app/src/main/java/com/lrj/commerce/app/runtime/monitoring/OperationalAlertPublisher.java)、[PlatformRuntimeController](../../commerce-app/src/main/java/com/lrj/commerce/app/http/runtime/health/PlatformRuntimeController.java) | logHealth/loggingAlertPublisher/publish | EVENT_RUNTIME_METRICS_READ；WARN codes；management仅health | FACT；外部送达NEEDS_VERIFICATION |
| E30 | 身份/租户/本人授权已有，无应用主体 | API/Java/SQL：[Actor](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/api/identity/Actor.java)、[SecurityConfiguration](../../commerce-app/src/main/java/com/lrj/commerce/app/configuration/security/SecurityConfiguration.java)、[CredentialMapper](../../platform-runtime/src/main/resources/mappers/runtime/CredentialMapper.xml) | capabilities/security/TokenFilter | ADMIN/MEMBER/OPERATOR/PLATFORM_OPERATOR；WEB/MINI_APP；token_hash/active/expires | FACT；应用授权缺口INFERRED |
| E31 | 商城购物袋为组件状态，已路由懒加载及部分分页 | UI：[Shop](../../frontend/src/features/Shop.tsx)、[App](../../frontend/src/app/App.tsx)、[API](../../frontend/src/shared/api.ts) | basket useState/createQuote/onFinish；lazy/Suspense/useCommand | 无购物车HTTP读写；地址随order.create；token不写localStorage | FACT；持久购物闭环缺口INFERRED |
| E32 | 编译依赖/核心边界测试已有，不能证明SQL所有权 | test：[ModuleBoundaryTest](../../architecture-tests/src/test/java/com/lrj/commerce/architecture/ModuleBoundaryTest.java) | persistedModulesOnlyReachOtherDomainsThroughApi/appShellReachesDomainsOnlyThroughApi | jdeps对实际编译类；.api跨域依赖约束 | FACT（源码）；本轮执行UNVERIFIED |
| E33 | CI真实DB/E2E/npm审计已有，基线main已绿 | CI/historical API：[workflow](../../.github/workflows/verify.yml)、[Phase8回归](../../docs/evidence/phase8-marketing-journey/16-regression.md)、[基线main运行](https://github.com/lirji/commerce-platform/actions/runs/36363560873) | build/verify/Chromium/npm audit；gh run list核实baseline head | head=7dda31e；completed/success；后端SBOM/依赖风险步骤未发现 | FACT（配置与已观察基线结果）；本轮新文档ref CI单独观察 |
| E34 | 单应用/单数据源，已有本地营销热点与滚动证据 | config/historical evidence：[application](../../commerce-app/src/main/resources/application.yml)、[compose](../../compose.yaml)、[运行手册](../../deploy/README.md)、[Phase7报告](../../docs/evidence/phase7-marketing-production/PHASE7_REPORT.md) | datasource/scheduler及OLD/NEW验收 | Hikari8、Tomcat64、app单服务、预算/券额度行争用；无生产HA认证 | FACT；目标环境持续容量/灾备NEEDS_VERIFICATION |
| E35 | 地址AES-GCM/AAD已有，单密钥/固定写入版本 | Java/SQL：[AddressCipher](../../order-runtime/src/main/java/com/lrj/commerce/ordering/address/infrastructure/security/AddressCipher.java)、[OrderMapper](../../order-runtime/src/main/resources/mappers/ordering/OrderMapper.xml) | encrypt | commerce.address-key；address_key_version写1；只有encrypt，无受控历史解密/轮换 | FACT；真实WMS披露政策UNKNOWN |
| E36 | 外部适配端口存在；缺合作方完整接入链 | API/Java/docs：[PaymentChannel](../../payment/src/main/java/com/lrj/commerce/payment/charge/application/port/PaymentChannel.java)、[ExternalEntitlementPort](../../benefit/src/main/java/com/lrj/commerce/benefit/entitlement/application/port/ExternalEntitlementPort.java)、[WmsPort](../../fulfillment/src/main/java/com/lrj/commerce/fulfillment/application/port/WmsPort.java)、[运行手册后置清单](../../deploy/README.md) | observe/ensure及权益/WMS端口 | 本地沙箱默认关闭；真实IdP/资金/权益/WMS后置 | 端口FACT；完整对外接入缺口INFERRED |
| E37 | 全额累计退货返原用券/CREDIT冲正；非自动撤已赠券 | Java/historical evidence：[BenefitCompensationHandler](../../commerce-app/src/main/java/com/lrj/commerce/app/runtime/compensation/BenefitCompensationHandler.java)、[CouponService](../../benefit/src/main/java/com/lrj/commerce/benefit/coupon/application/CouponService.java)、[Phase7报告](../../docs/evidence/phase7-marketing-production/PHASE7_REPORT.md) | handle/refund/confirmCampaign | fullReturn触发coupon hold USED→AVAILABLE；gifted coupon退款政策待独立决定 | 当前行为FACT；撤奖励目标UNKNOWN |
| E38 | 页面/按钮使用固定角色分支；运行开关不是权限集合 | UI/HTTP：[App](../../frontend/src/app/App.tsx)、[contracts](../../frontend/src/shared/contracts.ts)、[AdminData](../../frontend/src/features/AdminData.tsx)、[Orders](../../frontend/src/features/Orders.tsx)、[ConsoleController](../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/ConsoleController.java) | groups/menu/content；admin/sandboxEnabled；capabilities | Actor前端单role；静态菜单；runtime-capabilities={sandboxEnabled,workersEnabled} | 当前分支FACT；可配置页面/按钮RBAC闭环缺失INFERRED |
| E39 | 服务端是固定角色/能力，未见账号角色权限管理闭环 | API/SQL：[Actor](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/api/identity/Actor.java)、[SecurityConfiguration](../../commerce-app/src/main/java/com/lrj/commerce/app/configuration/security/SecurityConfiguration.java)、[CredentialMapper](../../platform-runtime/src/main/resources/mappers/runtime/CredentialMapper.xml)、[V1](../../commerce-app/src/main/resources/db/migration/V1__commerce_foundation.sql)、[V36](../../commerce-app/src/main/resources/db/migration/V36__background_runtime_failure_semantics.sql) | role/capabilities/requireAdmin/require/security | platform_credential单role；四固定角色；ADMIN全部营销能力；45迁移未见通用user-role/role-permission/menu模型 | 固定鉴权FACT；可配置RBAC完整闭环缺失INFERRED |
| E40 | 会员/订单有tenant/本人隔离，管理员没有岗位数据范围 | Service/SQL：[MemberService](../../member/src/main/java/com/lrj/commerce/member/profile/application/MemberService.java)、[MemberMapper](../../member/src/main/resources/mappers/member/MemberMapper.xml)、[OrderService](../../order-runtime/src/main/java/com/lrj/commerce/ordering/order/application/OrderService.java)、[OrderMapper](../../order-runtime/src/main/resources/mappers/ordering/OrderMapper.xml) | member.list/current；order.list/read/adminList/adminRead | member.list按tenant；order会员tenant＋member；adminList member=null，adminRead按tenant；请求store不是上述授权条件 | 已有过滤FACT；跨业务组织/门店/负责人通用范围缺失INFERRED |
| E41 | 商品经营已有数据库资源范围与撤权负向测试 | SQL/test：[StoreAccessMapper](../../store/src/main/resources/mappers/store/StoreAccessMapper.xml)、[V17](../../commerce-app/src/main/resources/db/migration/V17__store_operator_grants.sql)、[StoreAccessTest](../../commerce-app/src/test/java/com/lrj/commerce/app/StoreAccessTest.java)、[AuthorizationCoverageTest](../../commerce-app/src/test/java/com/lrj/commerce/app/configuration/security/AuthorizationCoverageTest.java) | allowed/stores；grantsAreScopedRevocableAndNeverGrantPlatformRights；控制器路径清单检查 | store_operator_grant只允许CATALOG＋STORE/MERCHANT；测试源码覆盖撤销、跨tenant、OPERATOR不能读管理会员/转授 | 实现与测试存在FACT；本轮未执行测试，不能扩展成所有业务范围验证PASS |

权限复核时另核实报告提交afc5e94的CI：[main运行36373088584](https://github.com/lirji/commerce-platform/actions/runs/36373088584)为completed/success；[分支运行36373067309](https://github.com/lirji/commerce-platform/actions/runs/36373067309)为completed/failure，浏览器24项中1项在等待“会员营销运营台”标题时失败，23项通过。失败原因未确认，不能静默忽略或直接断言为偶发，也不能把同提交main成功继承为权限补充新ref的CI结果。本轮未修改产品或测试来处理这个独立结果。

## 核心链路交叉核对

| 链路 | 入口→领域→权威持久化/失败恢复 | 证据 |
|---|---|---|
| 会员状态与成长 | 管理/注册→MemberService/成长事件→member_record/change/ledger→版本/命令约束 | E03/E05/E06 |
| 周期与积分 | 订单事实→成长/周期/积分→来源/批次/账本→到期逐项恢复 | E06/E15/E18 |
| 商品与经营job | 经营HTTP→授权/规格/资料/渠道价/job→catalog表→逐项版本冲突/取消 | E08–E11 |
| 报价与下单 | 商品可信价/人群/规则/券/积分→quote→order与库存/预算/权益预留→同事务回滚 | E12/E13/E14/E19/E20 |
| 支付/退款未知 | 请求意图→事务外渠道查证→锁内事实推进→Outbox/Inbox，UNKNOWN保留 | E04/E21/E36 |
| 履约/售后/补偿 | 已付/可履约事件→履约/售后单→部分金额/积分、累计退货→原券/CREDIT补偿 | E22/E37 |
| 动态人群/定向券 | 定义/刷新/批次→扫描检查点/固定快照→完整发布/逐项结果 | E13/E25 |
| Journey | 可信事件/扫描→固定版本→WAIT/DECIDE/既有动作→步骤/检查点原子，原节点恢复 | E23/E24 |
| 经营效果 | 订单/退款事件→重新读取权威事实→按订单唯一投影→UTC队列/净收款 | E26 |
| 公共运行 | EventWorker→独立有界车道→WorkLanes/Recovery→低基数观测/告警 | E17/E18/E28/E29 |
| 页面与接口权限 | App固定菜单/角色→SecurityConfiguration固定路径→Actor固定动作能力；无可配置角色/权限管理闭环 | E38/E39 |
| 业务数据范围 | 凭据tenant/actor→会员本人/订单过滤；商品StoreAccess→权威grant与目录SQL；管理会员/订单仍按tenant | E08/E40/E41 |

## 缺失判断的检索边界

静态清单涵盖19模块、全部41 Mapper XML的表引用和45迁移表定义，以及Controller/API/Service、前端业务页、CI/脚本/测试目录。重要现有链路采用实际方法段复核，不用文件数冒充全行审计。

跨域缺失检索包括：账号/角色/权限/菜单管理、permission/capability/role分支、角色关联与组织范围迁移、接口角色/能力、列表/详情/SQL范围；接入应用/外部主体与订单映射、Webhook/OpenAPI、购物车表/地址簿/配送费用、文件上传、仓库/结算、后端SBOM/依赖检查、API限流、密钥版本。权限补充采用入口→服务→Mapper交叉检查，未审计所有接口的每条路径。未发现完整闭环不等于绝对不存在于外部系统。只有明确的源码/配置事实使用FACT，缺失结论统一INFERRED。

额度、积分/券/权益防重、交易未知结果与状态约束没有因缺少外部渠道而降级为“未实现”。默认关闭的retention/sandbox/workers标为开关事实，不推测机器当前开启值。

## Validation / Handoff

CE-20260927必需检查：五份产物存在非空、必需章节和9维成熟度、四分类/触发条件、Not Recommended Now、依赖图、证据ID与本地链接有效、未把未执行测试标PASS、无产品/测试/配置/依赖变更。

本轮产品测试/压测/实时DB/渠道联调：UNVERIFIED（未执行），无需用它们证明本次文档改动。分析产物检查PASS：五文件非空、必需章节、四分类/16个候选模板、16项缺口、41个证据ID、本地链接、Mermaid围栏与git diff --check；权限补充只改五份分析文档及owner负责的CODEX_PROGRESS摘要。本次Git/CI实际观察在交付时记录，不能以历史CI的PASS继承新文档ref。

```yaml
SKILL_HANDOFF:
  protocol: skill-contract/v1
  skill: project-capability-exploration
  status: COMPLETED
  gate: PASS
  produced:
    - type: CAPABILITY_EXPLORATION_REPORT
      ref: .engineering/exploration/
  updated: []
  unresolved:
    - second-application-and-sharing-policy
    - delivery-fee-and-refund-policy
    - operating-roles-and-reward-promises
    - role-action-matrix-and-business-data-scope
    - revocation-for-accepted-background-work
    - retention-SLO-RTO-RPO-and-alert-owner
  recommended_next: [stop]
```

PASS表示分析产物可交付；未知政策保留且没有依赖它们实施产品。本次Git交付按独立delivery owner处理，实际提交/远程/CI事实以Git和最终交付观察为准。
