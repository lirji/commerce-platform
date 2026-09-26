# 能力探索证据索引

- 日期：2026-09-23
- 项目：`/Users/liruijun/personal/LLM/commerce-platform`
- 协议：`engineering-baseline/v1`、`skill-contract/v1`、`capability-exploration-report/v1`
- 分析基线：HEAD `fb7adf6278ed98c49d8851102031b061bcbb68bf` 加当前工作树。开始时已有 `CommerceController.java`、`CampaignService.java` 两处未提交修改，属于既有内容。
- 范围：源码、接口、Mapper、V1–V15迁移、前端、测试源码、CI配置与既有验收证据；只生成本目录分析产物。不执行产品修改、数据库写入、真实外部联调或生产操作。
- 结论边界：源码已有能力标 `FACT`；“未发现完整能力”及建设建议标 `INFERRED`；运行可用性、生产规模和本次测试结果标 `NEEDS_VERIFICATION`。未查询实时数据库、未重跑测试或远程CI。
- 已确认产品方向（用户，2026-09-23）：长期建设可供多个项目复用的会员、商品、营销能力中台，同时包含品牌自营商城＋会员运营。自营商城是实际业务应用，不仅是演示验证壳；第二接入项目及具体业务政策仍待设计。

## Exploration Summary

当前是**具备本地交易闭环、部分营销治理和一致性机制的业务基础版本**，尚不能按“完整企业级经营平台”评价。此前S0–S10完成表示批准切片已交付，不表示所有企业业务能力都已覆盖。会员和商品主要处在BASIC，营销处在PARTIAL；工程机制的完整程度高于业务功能广度。

产品定位是“共享能力中台＋品牌自营商城应用”。会员、商品、营销负责可复用业务能力；自营商城承载消费者购物、订单、支付、配送、售后及品牌运营。建设同时补齐共享主数据与实际购物闭环，再深化会员营销，最终由第二独立应用验证复用。独立商家入驻、佣金结算和完整仓内作业仍按需建设。

## Finding / Capability / Evidence Index

表内行号对应本次读取的工作树；文件后续变化时按类/方法重新定位。SQL字段保存状态不等于状态迁移功能已实现。以下FACT仅说明代码/文档内容，不能推导生产已启用。

| ID | Finding / Capability | Evidence Type | File | Class | Method | Configuration / SQL·Table | Confidence |
|---|---|---|---|---|---|---|---|
| E01 | 会员仅创建、查询、主体绑定，等级为输入字段 | Source | [member/src/main/java/com/lrj/commerce/member/api/MemberApi.java:5](../../member/src/main/java/com/lrj/commerce/member/api/MemberApi.java#L5) | MemberApi | create/current/list | member_record | FACT |
| E02 | 会员创建ACTIVE；无修改、冻结、注销公开用例 | Source | [member/src/main/java/com/lrj/commerce/member/application/MemberService.java:16](../../member/src/main/java/com/lrj/commerce/member/application/MemberService.java#L16) | MemberService | create/requireActive | member_record | FACT |
| E03 | 商品创建只有SKU、店铺、标题、单价 | Source | [catalog/src/main/java/com/lrj/commerce/catalog/api/CatalogApi.java:6](../../catalog/src/main/java/com/lrj/commerce/catalog/api/CatalogApi.java#L6) | CatalogApi | create/list/published | catalog_sku | FACT |
| E04 | 商品插入ACTIVE revision=1，Mapper仅插入和读取 | Mapper | [catalog/src/main/resources/mappers/catalog/CatalogMapper.xml:5](../../catalog/src/main/resources/mappers/catalog/CatalogMapper.xml#L5) | CatalogMapper | insert/list/batch | catalog_sku | FACT |
| E05 | 主数据、凭据、审计表边界 | Migration | [commerce-app/src/main/resources/db/migration/V1__commerce_foundation.sql:12](../../commerce-app/src/main/resources/db/migration/V1__commerce_foundation.sql#L12) | — | — | platform_audit/platform_credential/member_record/catalog_sku | FACT |
| E06 | 可信规则资产字段仅memberLevel和orderAmount | Source | [marketing-runtime/src/main/java/com/lrj/commerce/campaign/api/MarketingAssets.java:7](../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/api/MarketingAssets.java#L7) | MarketingAssets | TRUSTED_FIELDS | marketing_rule_asset | FACT |
| E07 | 人群为固定成员快照；最多500人；新鲜窗口最长24小时 | Source | [marketing-runtime/src/main/java/com/lrj/commerce/campaign/application/MarketingAssetService.java:15](../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/application/MarketingAssetService.java#L15) | MarketingAssetService | createAudience/sources/requireFresh | marketing_audience_snapshot/marketing_audience_member | FACT |
| E08 | 活动支持固定减免、百分比、预算和权益绑定及审批 | Source | [marketing-runtime/src/main/java/com/lrj/commerce/campaign/api/CampaignApi.java:9](../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/api/CampaignApi.java#L9) | CampaignApi | Draft/Terms/Policy/review | marketing_campaign/marketing_budget | FACT |
| E09 | 决策在活动间单一择优，稳定平局和金额分摊 | Source | [marketing/src/main/java/com/lrj/commerce/marketing/application/MarketingDecisionService.java:38](../../marketing/src/main/java/com/lrj/commerce/marketing/application/MarketingDecisionService.java#L38) | MarketingDecisionService | decide/allocate | — | FACT |
| E10 | 同店报价、两种可信事实、一张券与活动叠加/择优、资方分摊 | Source | [trade/src/main/java/com/lrj/commerce/trade/application/QuoteService.java:27](../../trade/src/main/java/com/lrj/commerce/trade/application/QuoteService.java#L27) | QuoteService | create | trade_quote | FACT |
| E11 | 优惠券限定店铺、固定优惠/有效期；每会员每版本至多一张 | Source | [benefit/src/main/java/com/lrj/commerce/benefit/application/CouponService.java:20](../../benefit/src/main/java/com/lrj/commerce/benefit/application/CouponService.java#L20) | CouponService | create/claim/reserve/refund | benefit_coupon_definition/benefit_coupon/benefit_coupon_hold | FACT |
| E12 | 内部整数权益有账本、核销、冲正及补偿 | Source | [benefit/src/main/java/com/lrj/commerce/benefit/api/EntitlementApi.java:5](../../benefit/src/main/java/com/lrj/commerce/benefit/api/EntitlementApi.java#L5) | EntitlementApi | reserveOrder/consume/resolve | benefit_definition/benefit_grant/benefit_ledger | FACT |
| E13 | 旅程仅MANUAL/ORDER_PAID触发和五种节点 | Source | [marketing-automation/src/main/java/com/lrj/commerce/journey/api/JourneyApi.java:9](../../marketing-automation/src/main/java/com/lrj/commerce/journey/api/JourneyApi.java#L9) | JourneyApi | Kind/Trigger/State | journey_definition/journey_instance | FACT |
| E14 | 旅程条件取等级/订单额，NOTIFY仅写站内消息 | Source | [marketing-automation/src/main/java/com/lrj/commerce/journey/application/JourneyService.java:148](../../marketing-automation/src/main/java/com/lrj/commerce/journey/application/JourneyService.java#L148) | JourneyService | execute | journey_notification | FACT |
| E15 | 低代码为五类数据源和三种业务动作 | Source | [marketing-automation/src/main/java/com/lrj/commerce/ops/api/OpsPageApi.java:9](../../marketing-automation/src/main/java/com/lrj/commerce/ops/api/OpsPageApi.java#L9) | OpsPageApi | Source/ActionKind | ops_page | FACT |
| E16 | 仅ADMIN/MEMBER角色，无商家/门店数据范围字段 | Source | [platform-runtime/src/main/java/com/lrj/commerce/runtime/api/Actor.java:7](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/api/Actor.java#L7) | Actor | Role/requireAdmin | — | FACT |
| E17 | 本地摘要Bearer认证与租户身份 | Source | [commerce-app/src/main/java/com/lrj/commerce/app/SecurityConfiguration.java:18](../../commerce-app/src/main/java/com/lrj/commerce/app/SecurityConfiguration.java#L18) | SecurityConfiguration | security/TokenFilter | platform_credential | FACT |
| E18 | 商家主数据仅标识名称状态 | Source | [merchant/src/main/java/com/lrj/commerce/merchant/api/MerchantApi.java:5](../../merchant/src/main/java/com/lrj/commerce/merchant/api/MerchantApi.java#L5) | MerchantApi | Create/View | merchant_record | FACT |
| E19 | 店铺与商家绑定及查询 | Source | [store/src/main/java/com/lrj/commerce/store/api/StoreApi.java:5](../../store/src/main/java/com/lrj/commerce/store/api/StoreApi.java#L5) | StoreApi | Create/View/browse | store_record | FACT |
| E20 | 库存是店铺SKU可售额度，支持预占确认释放退回 | Source | [inventory/src/main/java/com/lrj/commerce/inventory/api/InventoryApi.java:5](../../inventory/src/main/java/com/lrj/commerce/inventory/api/InventoryApi.java#L5) | InventoryApi | reserve/confirm/release/returnItems | inventory_stock/inventory_hold/inventory_return | FACT |
| E21 | 订单创建串起会员报价库存券预算权益与事件 | Source | [order-runtime/src/main/java/com/lrj/commerce/ordering/application/OrderService.java:28](../../order-runtime/src/main/java/com/lrj/commerce/ordering/application/OrderService.java#L28) | OrderService | create | order_record/platform_event | FACT |
| E22 | 履约单一订单物流号；沙箱WMS | Source | [fulfillment/src/main/java/com/lrj/commerce/fulfillment/api/FulfillmentApi.java:5](../../fulfillment/src/main/java/com/lrj/commerce/fulfillment/api/FulfillmentApi.java#L5) | FulfillmentApi | ship/deliver | fulfillment_record | FACT |
| E23 | 已有按行部分退货、审批、收货和退款 | Source | [aftersales/src/main/java/com/lrj/commerce/aftersales/api/AftersaleApi.java:5](../../aftersales/src/main/java/com/lrj/commerce/aftersales/api/AftersaleApi.java#L5) | AftersaleApi | request/approve/receiveReturn | aftersales_case/aftersales_line | FACT |
| E24 | 真实身份支付权益WMS接入后置 | Historical document | [deploy/README.md:40](../../deploy/README.md#L40) | — | — | 外部适配后置清单 | FACT |
| E25 | 购物袋只在组件useState中；前端真实读API | Source | [frontend/src/features/Shop.tsx:27](../../frontend/src/features/Shop.tsx#L27) | Shop | useResource/useState | — | FACT |
| E26 | 会员/商品/人群管理表单与后端最小模型一致 | Source | [frontend/src/features/AdminData.tsx:40](../../frontend/src/features/AdminData.tsx#L40) | specs | members/skus/audiences | — | FACT |
| E27 | 事务命令幂等与基本审计 | Source | [platform-runtime/src/main/java/com/lrj/commerce/runtime/Commands.java:23](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/Commands.java#L23) | Commands | run | platform_command/platform_audit | FACT |
| E28 | 数据库Outbox/Inbox，有界轮转、失败隔离 | Source | [platform-runtime/src/main/java/com/lrj/commerce/runtime/EventDispatcher.java:19](../../platform-runtime/src/main/java/com/lrj/commerce/runtime/EventDispatcher.java#L19) | EventDispatcher | tick/pumpTenant | platform_event/platform_inbox | FACT |
| E29 | 健康暴露、DB池8、沙箱和worker默认关闭 | Configuration | [commerce-app/src/main/resources/application.yml:11](../../commerce-app/src/main/resources/application.yml#L11) | — | — | commerce.* / spring.datasource.hikari / management.* | FACT |
| E30 | 四类任务由单Scheduled方法顺序调用 | Source | [commerce-app/src/main/java/com/lrj/commerce/app/EventWorker.java:13](../../commerce-app/src/main/java/com/lrj/commerce/app/EventWorker.java#L13) | EventWorker | deliver | — | FACT |
| E31 | CI声明真实DB构建、前端审计和浏览器验收 | Configuration | [.github/workflows/verify.yml:36](../../.github/workflows/verify.yml#L36) | — | verify | — | FACT |
| E32 | 历史验收145后端测试和4浏览器场景，非本次重跑 | Historical document | [docs/evidence/s10b/TEST_RESULT.md:1](../../docs/evidence/s10b/TEST_RESULT.md#L1) | — | — | — | FACT |
| E33 | 集成测试包含库存并发、退款、券预算权益、旅程恢复 | Test source | [commerce-app/src/test/java/com/lrj/commerce/app/PersistedCommerceTest.java:163](../../commerce-app/src/test/java/com/lrj/commerce/app/PersistedCommerceTest.java#L163) | PersistedCommerceTest | simultaneousOrdersCannotOversellOrConsumeOneQuoteTwice等 | 真实MySQL测试代码 | FACT |
| E34 | 历史架构风险：恢复、容量、保留、密钥、监测缺口 | Historical document | [.cursor/project-analysis/architecture-risks.md:1](../../.cursor/project-analysis/architecture-risks.md#L1) | — | — | 既有审查文档，本次非重新审查 | FACT |
| E35 | 明确受限AST选型，不迁移旧Drools | Historical document | [docs/design/unified-commerce/TECH_SELECTION.md:5](../../docs/design/unified-commerce/TECH_SELECTION.md#L5) | — | — | — | FACT |
| E36 | 模块边界测试源文件 | Test source | [architecture-tests/src/test/java/com/lrj/commerce/architecture/ModuleBoundaryTest.java:1](../../architecture-tests/src/test/java/com/lrj/commerce/architecture/ModuleBoundaryTest.java#L1) | ModuleBoundaryTest | — | — | FACT |
| E37 | 报价/售后浏览器验收及规则低代码旅程用例 | Test source | [frontend/tests/commerce.spec.ts:34](../../frontend/tests/commerce.spec.ts#L34) | — | test | — | FACT |
| E38 | 外部权益仅预留接口，无实现声明 | Source | [benefit/src/main/java/com/lrj/commerce/benefit/api/ExternalEntitlementPort.java:2](../../benefit/src/main/java/com/lrj/commerce/benefit/api/ExternalEntitlementPort.java#L2) | ExternalEntitlementPort | grant/query/revoke | — | FACT |

## 用户确认与建议的边界

| 编号 | 类型 | 内容 | Confidence |
|---|---|---|---|
| U01 | USER_REQUEST，2026-09-23 | 用户倾向会员、商品、营销较完整、可被多个项目复用的能力中台长期方向 | FACT |
| U02 | USER_REQUEST，2026-09-23 | 用户补充同时包含品牌自营商城＋会员运营 | FACT |
| U03 | 分析建议 | 共享能力中台与自营业务应用分清职责；第二独立应用检验复用；开放契约和数据所有权前移 | INFERRED，尚非已批准架构/实施切片 |

本轮据U01与U02修订五份探索文档的目标、候选优先级和路线。源码能力证据仍沿用本次已采集基线，没有把新方向写成已实现能力。

## 结论到证据映射

| 候选 | 能力 | 证据 | 缺失/建议可信度 |
|---|---|---|---|
| M01 | 会员档案与生命周期 | E01 E02 E05 E26 | INFERRED；已有实现见FACT证据 |
| M02 | 等级、成长值与等级权益 | E01 E02 E12 | INFERRED；已有实现见FACT证据 |
| M03 | 积分账户与积分生命周期 | E05 E12 E23 | INFERRED；已有实现见FACT证据 |
| M04 | 会员标签、行为摘要与360视图 | E01 E06 E10 E13 | INFERRED；已有实现见FACT证据 |
| C01 | 商品主数据与SPU/SKU规格体系 | E03 E04 E05 E26 | INFERRED；已有实现见FACT证据 |
| C02 | 商品编辑、上下架和价格版本治理 | E03 E04 E10 | INFERRED；已有实现见FACT证据 |
| C03 | 商品查找、筛选与有界批量维护 | E03 E04 E25 E26 | INFERRED；已有实现见FACT证据 |
| K01 | 动态分群与人群刷新 | E06 E07 E26 | INFERRED；已有实现见FACT证据 |
| K02 | 促销范围与组合策略 | E08 E09 E10 E11 | INFERRED；已有实现见FACT证据 |
| K03 | 优惠券运营生命周期 | E11 E07 E23 | INFERRED；已有实现见FACT证据 |
| K04 | 事件触发与可恢复旅程扩展 | E13 E14 E28 | INFERRED；已有实现见FACT证据 |
| K05 | 多渠道触达与偏好治理 | E13 E14 E17 | INFERRED；已有实现见FACT证据 |
| K06 | 营销经营指标与效果归因 | E09 E10 E11 E13 E15 | INFERRED；已有实现见FACT证据 |
| K07 | 领券/发奖滥用防护 | E11 E12 E16 E27 | INFERRED；已有实现见FACT证据 |
| S01 | 真实身份与商家/门店数据权限 | E16 E17 E18 E19 | INFERRED；已有实现见FACT证据 |
| S02 | 审计查询与审批职责分离 | E05 E08 E13 E15 E16 E27 | INFERRED；已有实现见FACT证据 |
| S03 | 数据保留、敏感信息访问与密钥恢复 | E05 E21 E24 E34 | INFERRED；已有实现见FACT证据 |
| T01 | 购物车、地址簿与配送计价 | E10 E21 E25 | INFERRED；已有实现见FACT证据 |
| T02 | 包裹、逆向物流和售后运营 | E22 E23 E24 | INFERRED；已有实现见FACT证据 |
| T03 | 多仓库存与作业协同 | E20 E22 E24 | INFERRED；已有实现见FACT证据 |
| T04 | 商家入驻、合同与结算 | E18 E19 E10 E23 | INFERRED；已有实现见FACT证据 |
| T05 | 真实渠道接入与差异对账 | E24 E28 E32 | INFERRED；已有实现见FACT证据 |
| O01 | 业务指标、告警与故障恢复验收 | E28 E29 E30 E31 E34 | INFERRED；已有实现见FACT证据 |
| P01 | 可恢复批量运营任务 | E07 E13 E28 | INFERRED；已有实现见FACT证据 |
| P02 | 规则模拟、解释与版本治理 | E06 E09 E35 E37 | INFERRED；已有实现见FACT证据 |
| P03 | 外部业务接入契约与交付事件 | E16 E17 E24 E28 | INFERRED；已有实现见FACT证据 |
| X01 | 拼团、秒杀、邀请裂变、订阅/付费会员 | E01 E08 E09 E21 E23 | INFERRED；已有实现见FACT证据 |
| X02 | Drools接入或旧规则迁移 | E06 E09 E35 | INFERRED；已有实现见FACT证据 |

## 搜索与反证范围

- 先读取根及项目CODEX_PROGRESS.md、设计BRIEF与既有架构风险，再按主数据→营销→交易履约→公共运行→前端→迁移→测试/CI顺序交叉确认。
- 已扫描所有19个模块结构、101个主Java源码文件清单和15个迁移的建表范围；重点读取各域API、核心服务和Mapper，按调用链核对。并非宣称对每行源码做了完整安全审查。
- 关键词复核范围：member/catalog/merchant/store/marketing/marketing-runtime/marketing-automation的生产代码与迁移、前端；检索loyalty/points/consent/segment/category/spu/variant/settlement/commission/experiment/attribution/webhook等和中文积分、成长值、画像、运费、分账、频控、归因、分群。语义无关的UI `variant` 不作为能力命中。
- “缺失”的判断同时依赖公开API、领域数据和UI边界，不以一次字符串无命中作为唯一依据。当前仓库之外的同名平台能力和部署状态均UNKNOWN。
- 反证保留：已支持券、百分比折扣封顶、单券叠加、预算/资方分摊、部分退款、权益冲正、快照人群、持久旅程与测试；没有将其误记为完全未实现。

## 外部参照（2026-09-23检索）

| 编号 | 来源 | 支持的参照范围 | 使用边界 |
|---|---|---|---|
| R01 | [Salesforce loyalty program](https://trailhead.salesforce.com/content/learn/modules/loyalty-management-basics/set-up-loyalty-program) | 等级、积分类型、权益、到期与来源追踪 | 不将所有功能列成当前项目必需 |
| R02 | [Shopify products](https://help.shopify.com/en/manual/products) | 规格、媒体、分类集合、批量修改/导入导出 | 不据此要求采购或模仿全部功能 |
| R03 | [Adobe capping rules](https://experienceleague.adobe.com/en/docs/journey-optimizer/using/conflict-prioritization/capping-rules/capping-rules-landing-page) | 消息频次与旅程进入治理 | 不把国外产品策略当作本项目合规要求 |

## 验证记录

- 五份产物存在与非空：PASS。
- 证据路径及行号：PASS，248处本地链接目标已验证；38项源码/文档证据。
- 候选ID及统一字段：PASS，28项唯一候选均含当前状态、目标、风险、依赖、触发条件和可观察验收；四分类齐全。
- 九维能力地图、Not Recommended Now、阶段路线与依赖图：PASS；Mermaid已检查结构与围栏，未运行图形渲染器。
- 当前生产功能运行、数据库内容、负载容量、远程CI状态：UNVERIFIED，本次不重跑。
- 历史测试数据：仅引用E32，未将历史145/4声明为本次通过。
- 产品/测试/配置未修改：PASS，写入报告前后已跟踪文件聚合SHA256一致：`8ab9ae346094d06e9e5b07af3cb5f1f6f7c7a1f67dd0a4844db76db2e063be10`。原两处未提交修改保留。
- Git：分析产物保持本地；本次为探索而非开发交付，未提交/推送，未处理既有两处产品修改。

## SKILL_HANDOFF

```yaml
protocol: skill-contract/v1
status: COMPLETED
gate: PASS
summary: 已完成只读能力探索；PASS仅表示分析产物校验，不代表生产验收或实现授权。
produced:
  - type: CAPABILITY_EXPLORATION_REPORT
    ref: .engineering/exploration/
updated: []
verification:
  - check: five-artifacts-present-nonempty
    result: PASS
  - check: evidence-links-and-candidate-fields
    result: PASS
  - check: no-product-mutation
    result: PASS
  - check: current-runtime-tests
    result: UNVERIFIED
unresolved:
  - 中台＋品牌自营方向已确认；品牌店铺范围、第二接入项目、共享授权、主数据归属及运营政策待设计
  - 真实外部联调沿用后置安排，生产能力未验收
  - 当前工作树既有两处修改未由本次测试验证
downstream_requirements:
  - 围绕中台＋品牌自营方向形成业务蓝图、数据所有权、接入契约与实施切片
recommended_next:
  - stop
```
