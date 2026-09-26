# Commerce Platform — 架构、业务能力缺口与演进报告

> 本文为英文版 `project-architecture-business-gap-evolution-report.md` 的中文译本，内容与结论保持一致。

- 日期：2026-09-25
- 基线：`main` @ `86d7e0a` 加工作区。未提交的前端排版改动（8 个文件）已阅读，但不纳入评判。
- 模式：`READ_ONLY_ANALYSIS`（经由 `project-capability-discovery` 路由）。未修改任何产品代码、测试、配置或 Schema；未执行构建、测试或数据库查询。测试行为引自源码，而非实际运行结果。
- 证据标签：**FACT**（在代码/配置/Schema 中可见）、**INFERENCE**（由事实推理得出）、**UNKNOWN**（无法从仓库证明）。路径均相对于仓库根目录。Java 代码为每个方法一行的高密度写法，因此行号指向的是整个方法。
- 对比的既有产物：`.engineering/exploration/*`（2026-09-23，基线 V1–V15）、`.cursor/project-analysis/architecture-risks.md`、`docs/design/unified-commerce/BACKEND_ARCHITECTURE.md`。其中列出的会员、商品、营销方面的大部分缺口已在 V16–V34 中补齐。本报告基于当前代码重新核验。

---

## 1. 执行摘要

**这是什么。** 这是一个 DDD **模块化单体**：Java 21、Spring Boot 4.1、MyBatis、单一 MySQL Schema，由一个进程同时承载 REST API、后台 Worker 以及同源的 React 管理端/会员端 SPA。它覆盖品牌自营电商以及会员与营销运营：
- 会员生命周期、等级、积分
- 商品、门店、库存
- 活动、规则、人群、优惠券、权益
- 报价 → 下单 → 支付 → 履约 → 退货/退款
- 持久化营销旅程，以及受治理的低代码运营页面搭建

代码库体量小但密度极高：167 个主 Java 文件（约 540 KB）、34 个 Flyway 迁移、87 张表、约 182 个 HTTP 端点。由单一作者在 2026-09-23/24 两天内通过 43 次提交完成。

**结构如何。** 共 19 个 Maven 模块：每个限界上下文一个模块，外加两个纯内核（`marketing` 规则、`order` 状态机）、`platform-runtime`（命令/Outbox/Inbox/凭证）、`shared-kernel`（Money）以及 `commerce-app` 外壳。一项 jdeps 测试强制跨模块 Java 调用只能经过 `.api` 包。每张表只有一个归属模块。共享的 MySQL 事务是主要的集成机制。

**做得好的地方（FACT）。**
- 就其体量而言，一致性工程做得异常扎实：
  - 带请求哈希重放的幂等命令
  - 事务型 Outbox 与 Inbox
  - 库存、预算、优惠券、积分、权益均采用条件更新式预留
  - 显式的 `CLOSING` 状态，确保支付结果未知时绝不释放资源
  - 精确到分的最大余数分摊，以及守恒的部分退款
  - 旅程每个节点使用 CAS 检查点
  - 基于批次（lot）的积分，支持欠款与过期豁免
- 以上几乎全部在真实 MySQL 上测试过，包括并发竞争场景。

**缺什么。** 生产级的外围能力：
- 真正的身份体系（无登录、无 IdP、无 Token 生命周期）。
- 真正的支付、退款、WMS 适配器（仅有沙箱，无 Webhook，无对账单对账）。
- 未支付订单的自动过期。
- 退款失败处理。
- 细粒度 RBAC 与双人复核（maker-checker）。
- 可观测性：指标、告警、链路关联。
- 电商基础能力：购物车、地址簿、运费。
- 结算。
- 站外通知渠道。

**风险在哪。** 异步主干的故障隔离很弱：
- 同一事件的所有消费者在**同一个事务**中运行，因此营销侧的失败会阻塞已支付订单的履约。
- 九个 Worker 共享**一个调度线程**，且部分 tick 会让异常逃逸。
- 失败约 30 秒后的隔离是静默的，因为没有任何告警。

存在若干硬编码的规模上限：每门店 100 个活动、每个人群 10 万会员、生命周期扫描每个事务仅处理一个会员。

**下一步应做什么。**
1. **稳定异步主干与订单生命周期边缘。** 包括按消费者拆分事务、Worker 隔离、定时过期、退款失败状态以及告警。
2. **加固身份与授权。**
3. **建设缺失的电商与结算能力。**

保持单体。仓库中没有任何证据支持现在拆分服务。

---

## 2. 当前架构

### 2.1 技术清单

| 领域 | 当前技术 | 证据 | 角色 |
|---|---|---|---|
| 语言/构建 | Java 21，Maven 多模块（19 个模块），surefire `failIfNoTests` | `pom.xml:10,14-34` | FACT |
| 框架 | Spring Boot 4.1.1（webmvc/Tomcat），优雅停机 | `pom.xml:36`、`application.yml` | FACT |
| 持久化 | MyBatis 4.0.1 XML Mapper，语句超时 10 s | `pom.xml:37`、`application.yml:35-40` | FACT |
| 数据库 | MySQL 8.4，Hikari 连接池 8，`innodb_lock_wait_timeout=5` | `application.yml:22-26`、`scripts/ci-configure.py:15` | FACT |
| 迁移 | Flyway V1–V34，全部位于 `commerce-app` | `commerce-app/src/main/resources/db/migration` | FACT |
| 消息 | **仅 MySQL Outbox/Inbox**，无消息中间件 | `platform-runtime/.../Outbox.java`、`EventDispatcher.java` | FACT |
| 调度 | 单个 `@Scheduled(fixedDelay=1000)` 方法，由 `commerce.workers-enabled` 控制（默认 false，compose 中为 true） | `commerce-app/.../EventWorker.java:7-13`、`compose.yaml:18` | FACT |
| 安全 | Spring Security，自定义 Bearer 过滤器，在 `platform_credential` 中按 SHA-256 查找 Token | `commerce-app/.../SecurityConfiguration.java:21-50` | FACT |
| 加密 | AES-256-GCM 地址加密，AAD=租户/订单，单密钥，无密钥 ID | `order-runtime/.../AddressCipher.java:17-28` | FACT |
| 可观测性 | 仅 Actuator `health`；主代码中共 7 处日志调用；traceId 仅出现在错误响应体中（无 MDC） | `application.yml:41-48` | FACT |
| 前端 | React 19、antd 6、Vite 8、TS 7，hash 路由，无状态/查询库；Playwright e2e | `frontend/package.json`、`frontend/src/app/App.tsx:44-50` | FACT |
| 容器/CI | Distroless 风格 Temurin JRE（按 digest 固定，uid 10001，只读文件系统）；GitHub Actions：构建 + 真实 MySQL 测试 + npm audit + 冒烟 + Playwright | `Dockerfile`、`.github/workflows/verify.yml` | FACT |
| **缺失** | Redis/缓存、Kafka/Rabbit、Micrometer/OTel、ShedLock/Quartz、resilience4j、OAuth2/JWT、方法级安全、限流、OpenAPI、搜索引擎、对象存储、Testcontainers | 对 java/xml/yml 的 grep | FACT |

### 2.2 运行时拓扑

```text
浏览器（React SPA：ADMIN 控制台 / OPERATOR 商品页 / MEMBER 商城）
   │  同源，Bearer Token（手动粘贴，仅存于内存）
   ▼
commerce-app  （单个 Spring Boot 进程，127.0.0.1:8602，768 MiB）
   ├─ TokenFilter → Actor(tenant, actor, role, channel)   [platform_credential]
   ├─ 25 个薄 @RestController（全部位于 commerce-app）
   │     └─ Commands.run（幂等 + 审计 + 业务事务）
   ├─ 各领域模块通过 .api 接口调用（进程内调用）
   │     member · merchant · store · catalog · inventory · trade · order-runtime
   │     payment · fulfillment · aftersales · marketing-runtime(campaign)
   │     benefit · marketing-automation(journey/ops/insight)
   │     纯内核：marketing（规则 AST/决策）· order（状态机）
   ├─ Outbox（platform_event，与业务变更同事务）
   └─ EventWorker 线程（1 s）：payments → refunds → events → segments →
        journeys → cycles → points → coupon deliveries → catalog jobs
   ▼
MySQL（外部 dev-infra 实例，单 Schema，87 张表，每张表均带 tenant_id）
   沙箱（基于数据库）替代支付 / 退款 / WMS 渠道
```

### 2.3 模块依赖图（Maven，FACT）

```text
shared-kernel ← marketing (rules), order (state machine)
shared-kernel ← platform-runtime ← member, merchant
merchant ← store ← catalog ← inventory
member, store ← benefit
catalog, member, marketing, store, benefit ← marketing-runtime (campaign)
member, store, catalog, marketing-runtime, benefit ← trade
trade, inventory, member, store, benefit, order ← order-runtime
order-runtime ← payment, fulfillment
order-runtime, fulfillment, payment, inventory, member ← aftersales
marketing-runtime, benefit, member, store, aftersales ← marketing-automation
everything ← commerce-app
```

- 最长依赖链深度为 8：automation → aftersales → payment → order-runtime → trade → campaign → benefit → member。
- 若干模块引用了传递依赖模块却未显式声明（INFERENCE，来自 import 扫描）：marketing-automation 引用了 order-runtime、payment 和 trade；commerce-app 引用了 aftersales、payment、fulfillment、inventory 和 order-runtime。

### 2.4 数据流与集成方式

- **同步（进程内，单个本地事务）：** 报价；创建订单（6 类资源预留）；发起支付；售后审核通过（回补库存 + 预留退款）；发货/签收；积分兑换。
- **异步（数据库 Outbox，至少一次投递，Inbox 去重）：** 支付事实 → 订单；订单已支付/待履约 → 履约、效果统计、旅程；退款成功 → 售后 → 优惠券/权益/旅程补偿；订单完成/退款 → 成长值、积分、行为；周期评定 → 等级权益；进入人群 → 旅程。
- **轮询 Worker：** 支付/退款重查、人群计算、旅程节点、周期评定、积分过期、优惠券批次、商品批量任务。

---

## 3. 业务能力地图

成熟度沿用提示中的分级。"MATURE"表示领域逻辑在本地范围内完整且经过测试，不代表已在生产中验证。

| 领域 | 能力 | 归属模块 | 成熟度 | 证据 | 缺口 |
|---|---|---|---|---|---|
| **会员** | 会员档案、状态 ACTIVE/FROZEN/CLOSED 及变更历史 | member | PARTIAL | `MemberService.java:18-52`、V16 | 仅支持管理员创建；无自助注册或资料维护 |
| | 成长值与等级（版本化策略，按订单净贡献） | member | MATURE | `MemberGrowthService.java:56-93`、`MemberGrowthTest` | `member_level` 有 3 个写入方（见 §8） |
| | 周期评定（保级/降级） | member | MATURE（逻辑） | `MemberCycleService.java:84-134`、`MemberCycleTest` | 每个周期边界时所有会员同时到期；20 会员/秒（§11） |
| | 积分：批次、过期、结算时冻结、退回原批次、欠款 | member | MATURE | `PointsSpendService.java`、`MemberPointsService.java`、V25–V28 | — |
| | 标签、行为事件、30 天画像 | member | PARTIAL | `MemberBehaviorService.java:28-41`、V30 | 仅 BROWSE/ADD_TO_CART；无外部行为数据接入 |
| | 身份 / 登录 / 账号 | platform-runtime | **MISSING** | `SecurityConfiguration.java:18`（"外部IdP适配尚未启用"）；Token 由 `scripts/seed-*.py` 插入 | 无注册、登录、IdP，也无 Token 签发/吊销 API |
| **商户/门店** | 商户与门店主数据 | merchant, store | PARTIAL | `MerchantMapper.xml`、`StoreMapper.xml`（仅 insert/find/list） | 无冻结/关闭/更新，无入驻流程 |
| | 运营人员授权（STORE/MERCHANT 范围，CATALOG 权限） | store | PARTIAL | `StoreAccessService.java:21-44`、V17 CHECK | CATALOG 是唯一权限；ADMIN 为租户级超级用户 |
| **商品** | SPU/SKU、规格模板、修订版本、类目（≤3 级）、条码、图片 | catalog | MATURE | `ProductOperationsService.java`、`CatalogMerchandisingService.java`、V18/V33 | 旧的 `POST /admin/skus` 绕过了 SPU/模板 |
| | 搜索 | catalog | PARTIAL | `MerchandisingMapper.xml:24-26`（`LOCATE` + keyset） | 无相关性排序或索引 |
| | 定时批量上架/下架/改价 | catalog | MATURE | `CatalogJobService.java:34-40`、`CatalogSchedulingTest` | — |
| | 可信渠道价（WEB/MINI_APP 取自凭证） | catalog | MATURE | V34、`CatalogMapper.xml:9`、`QuoteService.java:48-49` | — |
| | 媒体上传 | — | MISSING | `CatalogMerchandisingService.java:66`（仅 URL） | 无文件服务 |
| **库存** | 可售数量、按订单冻结/确认/释放、退货回补 | inventory | PARTIAL | `InventoryMapper.xml:7,16`、`InventoryService.java:52-58` | 无仓库维度、无调整/盘点、无库存流水 |
| **营销** | 规则 DSL（有界 AST、三值逻辑、可信字段） | marketing | MATURE | `RuleEvaluator.java:29-56`、`Condition.java`、`RuleNode.java:14-40` | — |
| | 活动：版本、审核、发布/暂停、阶梯/范围优惠 | marketing-runtime | MATURE | `CampaignService.java:24-88` | 无优先级字段；只取单个最优活动；候选 >100 时报价失败 |
| | 预算与出资分摊 | marketing-runtime | PARTIAL | `BudgetMapper.xml:5-8`、V10 | 退款时预算不回退；按版本封顶 |
| | 静态人群 + 动态人群 | marketing-runtime | PARTIAL | `SegmentService.java:24-89`、V20 | 已处理会员数上限 10 万；每租户约 100 会员/秒 |
| | 效果分析（活动/旅程/优惠券批次） | marketing-automation (insight) | PARTIAL | `EffectsMapper.xml`、`MarketingEffectsService.java:29` | 仅相关性；无对照组/A-B |
| **权益** | 优惠券：定义、券包、冻结/核销/退回、定向批次、相对有效期 | benefit, automation | MATURE | `CouponService.java`、`CouponDeliveryService.java`、V9/V31 | 旅程发放的券在退款时不会被收回 |
| | 内部权益，带流水与补偿欠款 | benefit | MATURE | `EntitlementService.java:25-46`、V11–V13 | 无外部供应方（`ExternalEntitlementPort` 未实现） |
| | 等级权益包、积分兑换 | benefit | MATURE | `MemberBenefitService.java`、`PointOfferService.java` | — |
| **自动化** | 持久化旅程（DAG、等待、判断、发放、通知、频控、生命周期触发） | marketing-automation | MATURE（引擎）/ PARTIAL（触达） | `JourneyService.java:56-265`、V14/V21/V32 | 通知仅站内；触发器为硬编码 `switch` |
| | 低代码运营页（白名单、预览、审批、回滚） | marketing-automation (ops) | MATURE（限定范围） | `OpsPageService.java:27-76`、V15 | 列表未按门店过滤 |
| **交易** | 服务端报价（活动 vs 优惠券 vs 积分，TTL，快照） | trade | MATURE | `QuoteService.java:30-102` | 叠加策略硬编码在 trade 中 |
| | 购物车、地址簿、运费、税费 | — | **MISSING** | `Shop.tsx:52`（React state），无对应表 | — |
| **订单** | 订单状态机（含 CLOSING）、多资源原子创建 | order, order-runtime | MATURE | `OrderLifecycle.java:21-42`、`OrderService.java:28-47` | 无反映退款的状态 |
| | 未支付订单超时 | order-runtime | **FRAGMENTED** | `OrderService.java:96-107` 已实现，但**仅** `POST /admin/orders/expire` 调用它（`PaymentController.java:22`）；不在 `EventWorker.java:13` 中 | 从不自动执行 |
| **支付** | 支付尝试、未知结果处理、单笔对账 | payment | PARTIAL | `PaymentService.java:19-76` | 仅沙箱；无 Webhook；自动检查 5 次后停止（约 2 分钟） |
| | 退款、额度保护 | payment | PARTIAL | `RefundService.java`、V5/V6 | 无 FAILED 状态（`ck_refund_status` V5:68） |
| | 对账单对账、结算、发票、拆分支付 | — | MISSING | grep：无 | — |
| **履约** | 每单一个包裹、发货/签收、售后阻断 | fulfillment | PARTIAL | `FulfillmentService.java:19-45` | 无拆包或物流跟踪；地址从未为 WMS 解密 |
| **售后** | 退货/退款单、按行精确分摊、回补库存、补偿 | aftersales | MATURE（仅退款） | `AftersaleService.java:28-64` | 无换货、退货时效、质检或凭证附件 |
| **治理** | 幂等命令 + 审计记录 | platform-runtime | PARTIAL | `Commands.java:23-38`、V1:12-21 | 审计缺少目标对象/结果，且无查询 API |
| | 双人复核 / 职责分离 | — | MISSING | 同一管理员可提交并审批（`CampaignService.java:69`、`JourneyService.java:92`、`OpsPageService.java:42`） | — |
| **运维** | 隔离任务可见性与手动重试（事件、旅程、人群、任务、投放） | 多个模块 | PARTIAL | `EventDispatcher.java:45-47`、各模块重试端点 | 仅租户范围；无全局积压视图，无告警 |
| | 指标 / 告警 / 链路 / 数据保留 | — | MISSING | `application.yml:41-48`；无删除任务 | — |

**平台化候选**（提示 §17）：可恢复批处理任务、异步 Worker 运行时、审计、身份/授权、通知。

---

## 4. 关键业务流程

| 流程 | 起点 | 关键状态 | 依赖 | 失败处理 | 薄弱点 |
|---|---|---|---|---|---|
| **报价 → 下单** | `POST /v1/quotes`、`POST /v1/orders` | 报价只能消费一次；订单 `PENDING_PAYMENT` | 会员积分、优惠券、活动预算、权益额度、库存（全部同步、同一事务） | 任一失败即整体回滚，包括报价消费；幂等重试（`PersistedCommerceTest:183,525`） | 热点行（预算、SKU）上的大事务；创建与释放之间锁顺序相反（INFERENCE，§11） |
| **支付** | `POST /orders/{id}/payments` | `PAYMENT_IN_PROGRESS`；支付尝试 `UNKNOWN→OPEN→PAID/CLOSED` | 沙箱渠道（事务外）→ Outbox `payment.paid` → 订单消费者 | 未知结果从不释放；在锁内校验金额/币种/租户；自动检查 5 次后转人工对账 | 无 Webhook；约 2 分钟后停止检查；被隔离的 `payment.paid` 事件会让订单静默卡住 |
| **取消 / 超时** | 取消 API / 管理员过期 | `CANCELLED` 或 `CLOSING`（支付进行中时） | 同样 5 类资源 | CLOSING 保持冻结；已收款优先于取消（`OrderLifecycle.java:33-37`） | **过期从未被调度** → 被遗弃的订单无限期占用库存/优惠券/预算/积分 |
| **履约** | Outbox `order.paid`/`order.ready` → 管理员发货/签收 | `READY→SHIPPED→DELIVERED`；订单 `FULFILLING→COMPLETED` | 沙箱 WMS 凭证（事务外） | 条件 CAS，售后期间设置阻断标记 | 单包裹；无自动确认收货；履约创建与旅程/效果统计共享事务 |
| **退货 / 退款** | `POST /aftersales` → 审核 → 收货 → 退款 | 售后单 `REQUESTED→WAIT_RETURN→REFUNDING→COMPLETED`；退款 `UNKNOWN→SUCCEEDED` | 库存回补、支付退款额度、积分、优惠券、权益、旅程 | 超额退款保护（V6）；累计精确分摊；已消耗权益的补偿欠款状态 | **无退款 FAILED 路径**；订单保持 `PAID`；预算不回退；旅程券不收回 |
| **旅程** | 事件触发 / 生命周期扫描 / 手动加入 | 实例 `RUNNING/WAITING/ISOLATED/COMPLETED/CANCELLED/TIMED_OUT` | 在节点事务中发放权益/优惠券；站内通知 | 每节点 CAS 检查点、SKIP LOCKED、退避、隔离、截止时间 | 生命周期扫描每事务一个会员（约 4 会员/秒/旅程）；匹配旅程 >10 时在共享事件事务内抛异常 |
| **人群刷新** | 定时 / 手动 | 运行 `RUNNING→COMPLETED/FAILED/ISOLATED` | 会员事实 | 每批 100 的 keyset 分批、检查点、原子快照切换 | 会员 >10 万的租户会失败；tick 循环未捕获异常 |
| **会员生命周期** | `order.completed` / `refund.succeeded` | 成长值流水、周期贡献、积分批次 | 位于应用外壳中的 handler | 按来源净贡献 → 可安全重放 | 周期 `due` 扫描每秒执行且无状态索引 |
| **商品批量任务** | 运营人员任务 | `SCHEDULED→RUNNING→COMPLETED/ISOLATED/CANCELLED`，条目 CONFLICT | 商品运营 API | 逐条修订版本 CAS，每步重新校验权限 | —（参考实现） |

---

## 5. 架构痛点

### P0 — 阻碍安全演进

1. **事件消费者没有故障隔离。** `EventDispatcher.pumpTenant` 在**同一个**事务中执行某事件的全部 handler（`EventDispatcher.java:31-35`，已核实）。`order.paid.v1` 同时被履约、营销效果统计和旅程消费。因此：
   - 旅程异常（例如匹配旅程 >10，`JourneyService.java:161`）会回滚履约单的创建。
   - 失败 5 次（约 30 秒）后，该事件对**所有**消费者都变为 `ISOLATED`。
   - 不会触发任何告警。

   路线图中的每一项都会给同一批事件增加消费者（通知、结算、更多旅程），因此每加一个功能，影响半径就扩大一分。按消费者划分的 `platform_inbox` 表已经存在，所以修复成本很低。
2. **后台 Worker 没有故障隔离。** 九个 tick 在一个 `@Scheduled` 方法中、在一个线程上顺序执行（`EventWorker.java:13`，已核实）。`MemberCycleService.tick` 和 `MemberPointsService.tick`（已核实）以及人群 `due` 循环（`SegmentService.java:62`）都不捕获异常。它们的到期行没有尝试次数或退避，因此单个毒行就会让循环每秒中断一次（INFERENCE）。这会饿死排在其后的所有 Worker：优惠券投放和商品任务；人群失败时还包括旅程。`payments.tick` 中渠道重查缓慢也会拖延事件分发。

### P1 — 显著增加工程或运营成本

3. **未支付订单过期从未自动化。** `OrderService.expire` 存在，但只能经由管理员 `POST /admin/orders/expire` 调用。没有任何调度器、UI 或脚本调用它（已 grep 核实）。被遗弃的订单持续占用库存、优惠券、预算、积分和权益。
4. **支付/退款长尾。**
   - 自动重查在 5 次尝试（4–64 s 退避）后放弃（`PaymentMapper.xml:14-16`），且没有 Webhook。
   - 退款只有 `UNKNOWN|SUCCEEDED`（`V5:68`，已核实）。被拒绝的退款会让售后单停留在 `REFUNDING`、退款额度被占用，而 `uk_refund_case` 又阻止重新发起。
5. **没有可观测性。** 应用只暴露 health，无指标、无告警，traceId 只放在错误响应体中而不在日志中。事件列表只有元数据且仅限租户范围。没有任何手段能回答"什么失败了、从何时开始、有多少"。
6. **身份与授权仅为开发级。**
   - Token 由 SQL 种子脚本插入（部分在 `2030-01-01` 才过期，`seed-local.py:20`）。
   - 没有签发或吊销 API，也没有限流。
   - 角色只有 ADMIN/MEMBER/OPERATOR，ADMIN 为租户级。
   - 任何新端点，除非放在 `/v1/admin` 下，否则默认 MEMBER 可访问（`SecurityConfiguration.java:27`，已核实）。
   - 同一管理员可以提交并审批活动、旅程和页面。
   - 优惠券定义、规则资产、人群、积分兑换以及最多 10 万接收人的优惠券批次，发布时均无需任何审批。
7. **业务路径中的硬编码规模上限。**
   - 门店已发布活动超过 100 个时报价失败（`CampaignService.java:82`，已核实）。
   - 会员超过 10 万的租户人群计算失败，因为上限统计的是*已处理*会员而非匹配会员（`SegmentService.java:85`、V20 CHECK）。
   - 生命周期扫描每个事务只处理一个会员。
   - 周期 `due` 扫描在每个周期边界让所有会员同时到期。
8. **共享事务耦合掩盖了一个 Saga。** 创建订单、支付事实、售后审核通过、退款成功，各自在一个 MySQL 事务中横跨 4–7 个模块的表（`OrderService.java:28-47,83-90`）。这在当下是正确且有意为之的（`BACKEND_ARCHITECTURE.md` §一致性），但也是未来任何拆分的最大障碍。

### P2 — 可控，但应改进

9. **锁顺序相反（INFERENCE）。** 创建时加锁顺序为 预算 → 权益 → 库存；确认与释放时为 库存 → 优惠券 → 预算 → 权益（`OrderService.java:35-37` 对比 `:62,88`）。在热门活动上存在死锁风险，MySQL 会中止其中一方。没有测试覆盖。
10. **跨领域流程管理器位于应用外壳中。** `MemberGrowthHandler`、`BenefitCompensationHandler`、`MemberBehaviorController`（业务逻辑）、`DashboardController`（聚合）。`ModuleBoundaryTest` 不扫描 `commerce-app`。
11. **业务规则以硬编码值散落各处。**
    - 报价 TTL 300 s、支付截止 900 s、5 次尝试、批大小 4/5/20 以及 CNY 均为硬编码。
    - 优惠券/活动叠加逻辑位于 `trade`（`QuoteService.java:50-56`）。
    - 生命周期触发条件以及触发器→事件映射是 Java `switch` 语句（`JourneyService.java:152-158,248-259`）。新增一个触发器需要新的枚举值、一次 CHECK 迁移以及代码改动。
12. **没有数据保留策略。** `platform_event/inbox/command/audit` 无限增长。`order.closing.v1` 与 `order.fulfilling.v1` 没有消费者，一直停留在 `PENDING`。`platform_command.response_json` 可能保存了 PII（UNKNOWN）。
13. **测试金字塔头重脚轻。** 14 个持久化模块外加 `platform-runtime` 自身测试数为 **0**。全部 19 个行为测试类都是位于 `commerce-app` 的 Spring Boot + MySQL 测试，且 Worker 处于关闭状态，因此 `EventWorker` 的装配未被测试。
14. **代码密度。** 单行方法最长达 1,324 个字符（`CatalogJobService.java:35`）；`JourneyService` 有 28.5 KB。只有专家才能审阅这些代码，这提高了上手与变更成本。
15. **未声明的 Maven 依赖以及 `ModuleBoundaryTest` 的缺口。** SQL/表访问以及 `runtime.persistence` 的使用均未被检查。

### P3 — 清理项

16. 每次加载看板都对 `member_record` 做全量 `COUNT`；每条流水都重新计算积分钱包；浏览事件会获取会员行锁。
17. 优惠券/权益的过期在读取时计算，没有状态流转——结果正确，但对报表不可见。
18. 未使用的 validation starter、无用的 `JourneyMapper.published`、硬编码的测试 Schema 名。
19. 旧的 ACTIVE-SKU 创建路径绕过了 SPU/模板规则。

---

## 6. 缺失的业务能力

每个缺口均由代码或 Schema 证据推断，而非来自通用电商清单。"建议方向"只是候选，不是已批准的需求。

| # | 缺口 | 证据（为何判定缺失） | 当前变通方式 | 业务影响 | 技术影响 | 建议方向 |
|---|---|---|---|---|---|---|
| G1 | **未支付订单自动过期** | `expire` 已实现但只能由管理员触发（`PaymentController.java:22`）；`EventWorker` 中没有 | 管理员手动调用端点 | 被遗弃订单占着库存/优惠券/预算/积分 | 热点行持续被占用 | 在 Worker 中加入 `orders.tick()` 并独立隔离；截止时间改为配置驱动 |
| G2 | **支付长尾与对账单对账** | 无 Webhook（grep）；自动检查上限 5 次；无对账单/文件对账 | 手动 `adminReconcile` | 迟到的支付卡在 `PAYMENT_IN_PROGRESS/CLOSING`；缺乏财务真相 | 恢复依赖人工 | 回调端点 + 签名校验；慢车道重查计划；每日对账单导入与差异队列 |
| G3 | **退款失败生命周期** | `ck_refund_status IN ('UNKNOWN','SUCCEEDED')` | 无 | 被拒退款永远卡住，额度泄漏 | 支付状态机存在不变量缺口 | 增加 `FAILED` + 释放额度 + 重发策略 + 售后 `REFUND_FAILED` 处理 |
| G4 | **订单反映售后结果** | `OrderState` 无已退款/退款后关闭状态；发货前全额退款后订单仍为 `PAID` | 单独查看售后单 | 订单视图与"已支付"事实具有误导性（`OrderMapper.xml:10`） | 派生事实错误 | 增加读模型订单状态或显式 `REFUNDED`/`PARTIALLY_REFUNDED` 投影；不要回滚历史 |
| G5 | **退款时的营销成本结算** | 预算只有 reserve/confirm/release（`CampaignFundingService`）；旅程券与订单无关联（`CouponApi.java:23`） | 无 | 预算被高估；已退款订单仍保留营销奖励 | 补偿不完整 | 预算增加 `REFUNDED` 冻结流转；旅程发放的券关联来源订单 |
| G6 | **身份与账号生命周期** | 无登录/注册/IdP；Token 由 SQL 种子写入 | 种子脚本 | 无法接入真实用户或员工 | 安全态势阻碍对外暴露 | IdP 适配器（OIDC）→ `Actor` 映射；Token 签发/吊销 API；限流 |
| G7 | **角色/权限模型与双人复核** | 角色 ADMIN/MEMBER/OPERATOR；CATALOG 是唯一权限；同一管理员审批自己的变更 | 依赖信任 | 无门店范围的员工角色（客服、财务、营销） | 新路径默认允许 MEMBER | 按资源的权限目录；审批人 ≠ 提交人；优惠券批次/规则/兑换需审批 |
| G8 | **人可用的审计轨迹** | `platform_audit` 仅有 tenant/actor/operation/key；无读取方 | 数据库查询 | 资金/权益变更无法追责 | — | 增加目标对象、结果、原因；查询 API；将 pump 类操作排除出"静默"操作 |
| G9 | **购物车、地址簿、运费** | 购物车是 React state（`Shop.tsx:52`）；每单手输地址；无运费模型 | 每单重新输入 | 转化流失，无配送定价 | 报价无法计算运费 | 持久化购物车（会员所有）、地址簿（加密）、运费模板作为报价组成部分 |
| G10 | **真实履约集成** | 地址是只写密文（无解密路径）；单包裹；沙箱 WMS | 沙箱 | 无法发出真实商品 | 无法轮换密钥（无密钥 ID） | 带密钥版本的加密 + 面向 WMS 适配器的可审计解密；需要拆单发货时再引入包裹模型 |
| G11 | **商户结算 / 开票** | 出资分摊已计算（`QuoteService.java:81-83`）但从未结算；无发票表 | 无 | 仅在多商户场景下相关 | — | 推迟到多商户被确认为业务线之后 |
| G12 | **站外通知与偏好** | NOTIFY 只写 `journey_notification` | 站内列表 | 旅程无法触达站外用户 | — | 先接一个真实渠道；模板、回执、退订、免打扰时段、全局频控 |
| G13 | **实验能力** | 旅程或活动模型中无对照组/留出组；报表注明"不代表因果提升" | 相关性报表 | 无法证明 ROI | — | 在旅程版本上设置留出比例；同期群对比已存在 |
| G14 | **商户/门店生命周期** | 状态列支持 FROZEN，但没有 API 修改它 | 直接改库 | 无法暂停门店 | — | 状态流转命令 + 对商品/报价的影响 |
| G15 | **库存运营** | 只有 `receive`；无调整、盘点、流水、仓库 | 继续入库 | 库存只能手动增加 | — | 库存流水 + 带原因的调整；仓库维度待 WMS 落地后再加 |
| G16 | **搜索相关性** | 对标题做 `LOCATE` 子串匹配 | — | 商品规模增大后发现性差 | 全表扫描 | 先用 MySQL FULLTEXT/ngram；有证据后再引入外部搜索引擎 |

自 2026-09-23 探索以来已补齐（FACT，V16–V34）：会员等级/周期、积分流水、标签/行为、SPU/规格模板/修订版本、商品搜索/任务/渠道价、动态人群、定向优惠券批次、生命周期旅程触发器、效果投影。

---

## 7. 领域 / 模块边界分析

以下为定性评级；项目没有打分标准。

| 模块 | 边界 | 数据 | 事务 | 负载 | 故障隔离 | 迁移成本 | 拆分建议 |
|---|---|---|---|---|---|---|---|
| marketing（规则内核） | 强：纯函数，仅使用白名单 JDK | 无 | 无 | CPU 负载轻 | 纯函数 | 低 | **保持为库**；无需拆分 |
| order（状态机） | 强：纯函数 | 无 | 无 | — | — | 低 | 保持为库 |
| member | API 清晰；子能力众多 | 归属清晰；`member_level` 有 3 个写入方 | 通过 handler 参与订单/退款事务 | 突发时单会员行锁成为热点 | 低 | 高 | KEEP_MODULE；内部拆分（忠诚度 vs 档案） |
| catalog | 清晰 | 清晰 | 基本自有事务；报价同步读取价格 | 读多 | 中 | 中 | KEEP_MODULE；若浏览负载增长，是读模型的首选 |
| inventory | API 清晰 | 清晰 | 位于订单事务内 | 热点 SKU 行 | 无 | 高 | KEEP_MODULE |
| trade | 编排者 | `trade_quote` | 在订单事务中被消费 | 每次结算 | 无 | 高 | KEEP_MODULE |
| order-runtime | 核心 | 清晰 | 持有跨模块事务 | 每次结算 | 无 | 极高 | KEEP_MODULE |
| payment | 端口清晰（`PaymentChannel`） | 清晰 | 渠道 I/O 已在事务外 | 外部延迟 | 好（异步重查） | 中 | **未来最佳拆分候选**（适配器/网关），仅当真实渠道的延迟/合规有此要求时 |
| fulfillment | 端口清晰（`WmsPort`） | 清晰 | 小 | 低 | 好 | 中 | KEEP_MODULE；WMS 适配器留在边缘 |
| aftersales | 编排者 | 清晰 | 横跨库存/支付/会员 | 低 | 低 | 高 | KEEP_MODULE |
| marketing-runtime | 混合：活动 + 人群 + 预算 | 两个服务写人群表 | 预算位于订单事务内 | 人群扫描重 | 低 | 高 | KEEP_MODULE；人群计算是唯一计算密集部分 |
| benefit | 清晰 | 清晰 | 位于订单/旅程事务内 | 中 | 低 | 高 | KEEP_MODULE |
| marketing-automation | 三个包（journey/ops/insight） | `insight` 通过 SQL 读取旅程表 | 节点事务包含发放 | Worker 负载重 | 低（共享事件事务） | 中 | KEEP_MODULE；**旅程运行时**可在任何服务拆分之前，先作为独立 *Worker 部署*（同一份代码，仅 Worker 实例） |

**当前可拆分的服务候选：0 个。** 设计文档的规则已要求在任何拆分前具备实测负载、团队归属和故障隔离的证据（`BACKEND_ARCHITECTURE.md`，Future Service Map）。这些证据目前都不存在：单一作者、无负载数据、单一部署。

---

## 8. 数据归属与一致性

| 数据资产 | 当前归属 | 写入方 | 读取方 | 边界风险 | 建议 |
|---|---|---|---|---|---|
| `platform_command`、`platform_audit` | platform-runtime | 每个命令 | 无（无 API） | 无限增长；可能包含响应 PII | 保留策略；审计读取接口 |
| `platform_event`、`platform_inbox` | platform-runtime | 所有模块经由 `Outbox` | dispatcher | 无消费者的类型一直 PENDING；无归档 | 将无订阅的类型标记为已投递或跳过；归档任务 |
| `platform_credential` | platform-runtime | 仅种子脚本 | 过滤器、`OperatorDirectory` | 无生命周期 API；无 `(tenant,actor)` 索引 | 归入身份模块 |
| `member_record.member_level` | member | 管理员创建、成长值应用、周期评定 | 报价事实、旅程 | 三个写入方靠布尔返回值协调 | 单一"等级解析器"写入方 |
| `marketing_audience_snapshot/_member` | marketing-runtime | `AssetMapper` 与 `SegmentMapper` | 活动事实 | 同一模块内两个服务 | 归属合并到人群 |
| `journey_*`、`automation_coupon_*` | automation/journey | journey 包 | **insight 包通过 SQL 读取**（`EffectsMapper.xml:19-53`） | SQL 耦合对 jdeps 不可见 | 通过 API 读取或声明投影 |
| `order_record`、`member_behavior_event` 索引 | order / member | **V32（automation 的迁移）添加了索引** | — | 迁移集中在 app 中；归属模糊 | 按归属标记迁移；保持单一 Flyway 历史 |
| `marketing_budget` | marketing-runtime | 订单事务（reserve/confirm/release） | 报表 | 热点行；退款时不回退 | 增加退款流转；实测后再考虑预算分片行 |
| `order_record.address_cipher` | order-runtime | 创建订单 | **无人读取** | 履约无法读取；单密钥，无 ID | 带密钥版本的加密 + 可审计解密端口 |

**事务（FACT）。** 跨模块工作是一个本地 MySQL 事务，参与方使用 `Propagation.MANDATORY`。远程 I/O 保持在事务之外（沙箱渠道上使用 `Propagation.NEVER`）。这套机制可用且经过测试。这意味着系统的一致性来自共置，而非协议。冻结/确认/释放表已经建模了 TCC 风格的预留，如果将来要拆分，它们就是迁移路径。

**缓存一致性。** 不适用；没有缓存。

---

## 9. 规则 / 流程 / 状态模型

| 领域 | 当前表示方式 | 评估 |
|---|---|---|
| 促销资格 | 有界 JSON AST → sealed Java 树、三值逻辑、白名单可信事实、版本化不可变资产并快照进活动 | **足够。** 规则引擎（Drools）已被明确否决（`TECH_SELECTION.md:5`）；旧的 Drools 迁移状态为 `UNCHANGED_BLOCKED`（`docs/PROGRESS_STATE.json`）。不要引入规则引擎 |
| 促销组合 | 按折扣取单个最优活动；优惠券叠加/互斥；积分最后抵扣，全部在 `QuoteService` 中 | 当前够用。缺少：优先级/互斥组。**只有**在出现第二条组合规则需求时，才在 `marketing` 中抽出一个小的 `PricingPolicy` |
| 旅程触发器 | 枚举 + SQL CHECK + Java `switch` | 硬编码，但只有 4 个生命周期触发器。等到新增下一个触发器时，再引入注册表（触发器 → 事实查询），而不是提前做 |
| 订单/支付/售后 | 显式状态枚举、CAS 版本号、数据库 CHECK、每次流转发事件 | 良好。缺口在于*缺失的状态*（退款 FAILED、订单已退款），而非缺少框架 |
| 旅程 | 自研持久化 DAG 运行时（≤32 节点、CAS 检查点、SKIP LOCKED、截止时间） | **不要替换为工作流引擎。** 规模问题的修复（批量扫描）是局部的 |
| 审批 | 各实体自带状态字段（活动、旅程、页面） | 分散。共享审批组件（提交人 ≠ 审批人、原因、审计）是合理的：已有 3 份拷贝，另有 5 个实体需要（G7） |
| 配置 | 硬编码常量 | 将超时、批大小、尝试次数迁移到类型化的 `@ConfigurationProperties`。不需要配置中心 |

---

## 10. 集成架构

| 集成点 | 当前模式 | 保持同步？ | 建议 |
|---|---|---|---|
| 报价/订单预留 | 同步，同一事务 | **是** | 正确性依赖原子性 |
| 支付渠道 ensure/observe | 事务外同步调用 + 异步重查 | 混合 | 增加回调（异步）+ 慢车道重查；保留锁内校验 |
| 支付事实 → 订单 | Outbox | 异步 | 保持，但隔离消费者事务 |
| 订单已支付 → 履约 / 效果 / 旅程 | Outbox，**共享消费者事务** | 异步 | 按消费者拆分事务（P0-1） |
| 退款 → 售后 → 补偿 | Outbox 链 | 异步 | 增加失败分支（G3） |
| WMS | 沙箱端口，事务前调用 | 请求同步 / 状态后续异步 | 适配器 + 解密端口 |
| 通知 | 无（站内记录） | 异步 | 由 Outbox 驱动、带回执的发送器 |

**Outbox 特性（FACT）。**
- 至少一次投递。
- 通过 `uk_event_fact` 做生产端去重。
- 每个消费者一个 Inbox。
- 使用 `SKIP LOCKED` 认领，因此多实例安全。
- 2^n 秒退避，5 次尝试后隔离。
- 带审计的手动重试。
- 无按聚合的顺序保证；handler 通过重新读取权威状态来弥补。

吞吐上限约为每实例每秒 20 个事件（4 个租户 × 5）（INFERENCE）。在实测积压时长证明有必要之前，**不**应引入消息中间件。

---

## 11. 可靠性 / 性能 / 安全 / 可观测性

| 维度 | 当前成熟度 | 主要缺口 |
|---|---|---|
| **可靠性** | 数据层很高：幂等命令、CAS、CHECK、对 UNKNOWN 安全的支付、持久化检查点、崩溃重启证据（`docs/evidence/s9a/restart.json`） | 消费者/Worker 隔离（P0）；过期未调度；退款失败；单 MySQL，无备份/恢复演练（`architecture-risks.md`） |
| **性能** | 处处有边界（keyset 分页、批大小上限、64 KiB 请求体、10 s 事务） | 热点预算/SKU 行位于 10+ 条语句的事务中；连接池 8 vs Tomcat 64 线程；生命周期扫描约 4 会员/秒；人群约 100 会员/秒；周期边界惊群；`LOCATE` 搜索。**没有已证实的瓶颈**：不存在负载数据。应将这些视为可能的未来瓶颈，优化前先测量 |
| **安全** | 租户只取自凭证；每条 SQL 都过滤 `tenant_id`；带 AAD 的 AES-GCM；只读容器；规则/低代码中不含脚本 | 无 IdP/登录；长期有效的种子 Token；无限流；非 admin 路径默认允许 MEMBER；无双人复核；审计缺少目标对象；应用数据库用户拥有 `GRANT ALL` 并在启动时执行 Flyway；地址密钥无法轮换 |
| **可观测性** | Health + 错误中的 traceId + 各模块 ISOLATED 列表 | 不写 SQL 就无法回答"什么失败了 / 影响半径 / 从何时开始"。无指标、告警、MDC 或全局积压视图 |

---

## 12. 未来架构方向

演进驱动力，按证据排序：

| 驱动力 | 重要性 | 证据 |
|---|---|---|
| 从本地走向真实运营（身份、支付、WMS） | HIGH | README 与 `architecture-risks.md` 明确推迟了这些；沙箱与端口已存在 |
| 同一批事件上更多营销自动化与消费者 | HIGH | 已有 9 个消费者；最近 12 次提交大多在增加消费者/触发器 |
| 会员/数据增长（人群、周期、扫描） | MEDIUM | 硬上限（10 万、100 个活动）与逐会员循环 |
| 被第二个应用复用（能力平台） | MEDIUM | `.engineering/exploration/CAPABILITY_GAPS.md` 中已确认的产品方向（"能力中台＋品牌自营商城"），但尚无第二个消费方 |
| 多商户结算 | LOW | 出资分摊已存在；代码中无商户入驻或结算需求 |
| 高并发秒杀 | LOW（未证实） | 无负载数据 |
| AI/LLM 集成 | LOW | 代码中没有；无需求证据 |

```text
当前（本地完备的模块化单体，外围为沙箱）
  → 近期：稳定化的单体
      隔离的消费者与 Worker、定时过期、退款失败路径、
      指标 + 告警、配置属性、数据保留
  → 中期：生产就绪的单体
      IdP + 权限模型 + 双人复核、真实支付/WMS 适配器、
      对账单对账、购物车/地址/运费、站外通知
  → 长期（仅在出现证据时）：选择性分离
      同一 jar 的仅 Worker 部署；如合规/延迟需要，将支付网关适配器
      独立为单独进程；通过版本化 API + 应用级授权向第二个应用
      开放共享能力
```

---

## 13. 演进路线图

| 阶段 | 范围 | 退出标准 |
|---|---|---|
| **Phase 0 — 稳定化** | (1) 在 `EventDispatcher` 中按消费者拆分事务，复用现有 Inbox。(2) 每个 tick 单独 try/catch，并为周期/积分/人群的到期行增加尝试次数/退避；将支付/退款与其他 tick 分开。(3) 调度 `orders.expire`。(4) 退款 `FAILED` + 额度释放。(5) Micrometer 指标：积压时长、ISOLATED 数量、UNKNOWN 支付/退款、卡住的旅程，外加 MDC 中的 traceId。(6) 超时与批大小改用 `@ConfigurationProperties`。(7) 消费或跳过 `order.closing/fulfilling`。 | 失败的旅程无法阻塞履约（集成测试）。毒化的周期行无法中断商品任务（测试）。被遗弃订单自动释放（开启 Worker 的测试）。已为积压时长和 ISOLATED>0 定义告警 |
| **Phase 1 — 强化内部架构** | 将 `MemberGrowthHandler`/`BenefitCompensationHandler` 移入归属模块或显式的 `process` 模块。扩展 `ModuleBoundaryTest` 以覆盖 commerce-app、SQL 表前缀以及已声明 vs 实际使用的 Maven 依赖。让 `member_level` 只有单一写入方。统一人群表归属。增加共享审批组件（提交人 ≠ 审批人）。修复创建与释放之间的锁顺序。为纯逻辑增加模块级单元测试。 | 边界测试覆盖 app 与 SQL。创建与释放在并发下的死锁测试通过。活动、旅程、页面、优惠券批次和规则均强制双人复核 |
| **Phase 2 — 补齐业务能力** | 身份（OIDC 适配器、Token 生命周期、限流）+ 权限目录。真实支付适配器 + 回调 + 慢车道重查 + 每日对账单对账。带密钥版本的地址加密 + WMS 解密端口。持久化购物车、地址簿、报价中的运费。预算/旅程券的退款补偿。订单退款投影。一个带退订的站外通知渠道。 | 真实渠道沙箱契约测试通过（重复、乱序、超时）。存在财务差异报表。会员可以登录、保留购物车并复用地址 |
| **Phase 3 — 平台化重复能力** | 抽取到 `platform-runtime`：(a) **可恢复批处理任务**框架（目前有 5 份拷贝：商品任务、人群运行、优惠券投放、旅程扫描、事件）；(b) 带分车道预算的 **Worker 调度器**；(c) 审批 + 审计查询；(d) 通知。旅程触发器注册表。 | 新的批处理/Worker 功能复用该框架，不再复制退避/隔离 SQL |
| **Phase 4 — 服务拆分（有条件）** | 仅在有实测数据时：先做仅 Worker 的部署配置（同一 jar）。仅当渠道延迟或合规范围要求时，才独立出支付网关进程。任何拆分之前，先用现有的冻结/确认/释放协议加 Outbox 替换共享事务。 | 每个候选都有记录在案的负载证据、归属团队以及故障隔离收益 |
| **Phase 5 — 高级能力（仅在合理时）** | 面向第二个应用的版本化外部 API（应用级凭证、配额）。实验/留出组。按实测需求引入 FULLTEXT 或搜索引擎。 | 存在第二个消费方；留出组报表被用于决策 |

---

## 14. 优先级矩阵

| 建议 | 业务价值 | 技术价值 | 紧迫度 | 成本 | 风险 | 优先级 |
|---|---|---|---|---|---|---|
| 按消费者拆分事件事务 | 高 | 高 | 高 | 低 | 低 | **P0** |
| Worker 隔离（捕获异常、退避、分车道） | 高 | 高 | 高 | 低 | 低 | **P0** |
| 调度未支付订单过期 | 高 | 中 | 高 | 低 | 低 | **P1** |
| 退款 FAILED 路径 | 高 | 中 | 高 | 低–中 | 中 | **P1** |
| 指标、告警、MDC 链路 | 高 | 高 | 高 | 中 | 低 | **P1** |
| 身份 + Token 生命周期 + 限流 | 高 | 高 | 对外暴露前 | 中 | 中 | **P1** |
| 双人复核 + 批量权益审批 | 高 | 中 | 真实运营前 | 中 | 低 | **P1** |
| 解除硬上限（100 个活动、10 万人群上限、逐会员扫描） | 中 | 中 | 增长前 | 中 | 中 | **P1** |
| 真实支付适配器 + 回调 + 对账单对账 | 高 | 中 | 真实资金前 | 高 | 高 | **P1**（与上线同步） |
| 预算 + 旅程券的退款补偿 | 中 | 中 | 中 | 低 | 低 | **P2** |
| 订单退款投影 | 中 | 低 | 中 | 低 | 低 | **P2** |
| 购物车 / 地址簿 / 运费 | 高 | 低 | 中 | 中 | 低 | **P2** |
| 锁顺序修复 + 死锁测试 | 中 | 高 | 中 | 低 | 中 | **P2** |
| 边界测试扩展 + 应用外壳 handler 迁移 | 低 | 高 | 中 | 低 | 低 | **P2** |
| 常量改为配置属性 | 低 | 中 | 中 | 低 | 低 | **P2** |
| 数据保留/归档 | 中 | 中 | 低 | 中 | 中 | **P2** |
| 批处理任务/Worker 平台化 | 中 | 高 | 低 | 中 | 中 | **P2** |
| 站外通知渠道 | 中 | 低 | 低 | 中 | 中 | **P3** |
| 留出组实验 | 中 | 低 | 低 | 中 | 低 | **P3** |
| FULLTEXT 搜索 | 低 | 低 | 低 | 低 | 低 | **P3** |
| 商户结算 | 低（当前） | 低 | 低 | 高 | 高 | **P3**（有条件） |

---

## 15. 不应做的事

| 诱人的做法 | 为何在此不合理 |
|---|---|
| 拆成微服务 | 无负载数据、单一作者、单一部署。一致性依赖跨 4–7 个模块的共享事务；把调用换成 HTTP 会悄无声息地破坏原子性（设计文档已明确指出）。 |
| 引入 Kafka/RabbitMQ | Outbox 可用。上限（约 20 事件/秒/实例）尚未对照真实流量测量。真正的问题是消费者隔离，而中间件本身解决不了它。 |
| 为库存/预算引入 Redis | 会为资金敏感计数器增加第二个事实来源。热点行风险尚未证实；先测量，并在引入缓存前优先考虑在 MySQL 中做预算分片。 |
| 采用工作流引擎（Temporal/Camunda） | 自研旅程运行时已提供持久化等待、CAS 检查点与补偿。缺口在于扫描吞吐与触发器，而不是编排语义。 |
| 采用规则引擎 / 迁移 Drools | 已被明确否决（`TECH_SELECTION.md:5`）。带可信事实的有界 AST 更安全，且该迁移已被正式 BLOCKED。 |
| 在第二个消费方出现前建设"中台" | 复用是既定方向但尚未证实。先加固 API 与应用级授权，再按真实第二个应用的需要做平台化。 |
| 事件溯源 / CQRS | 需要的地方已经有只追加流水（积分、权益、修订版本）。完整 ES 会带来成本，却没有它独有能解决的查询/审计需求。 |
| K8s / 服务网格 | 单容器、本地范围。部署拓扑不是约束所在。 |
| 分布式事务（XA/Seata） | 如果将来要拆分，现有的冻结/确认/释放表就是合适的补偿协议。 |
| 为"清理"而自动退款 UNKNOWN 支付 | 违反整个设计赖以建立的核心不变量（未知 ≠ 未支付）。 |

---

## 16. 面试 / 简历价值

只列出有代码与测试证据的故事。

1. **"超时不等于未支付"：CLOSING 状态。**
   - *问题：* 支付结果未知期间发生取消或超时，可能导致资金损失或超卖。
   - *决策：* 引入显式 `CLOSING` 状态，已收款优先于取消。资源在可信事实到达之前保持冻结。渠道关单与支付均为条件更新，因此只有一方胜出。
   - *证据：* `OrderLifecycle.java:33-37`、`PaymentMapper.xml:12-13`、`PersistedCommerceTest:216,226,238`。
   - *权衡：* 安全但不保证活性；资源可能一直被占用，恢复最终依赖人工对账。
   - *可能的追问：* 为什么不用分布式锁？如果渠道永远不响应怎么办？如何在不破坏不变量的前提下加入 Webhook？
2. **模块化单体中的多资源原子结算。**
   - *机制：* 报价只消费一次、积分按 FIFO 批次、优惠券、预算上限、权益额度和库存，各自在同一事务内以 `MANDATORY` 传播做条件更新。jdeps 强制 `.api` 边界。
   - *权衡：* 无需 Saga 即获得强一致，代价是大型热点行事务以及未来更难拆分。
   - *追问：* 死锁顺序、如何拆分、冻结表与 TCC 的映射。
3. **精确到分的资金守恒。**
   - *机制：* 每层折扣按最大余数分摊（`MarketingDecisionService.java:86-110`）；平台/商户出资分摊带数据库 CHECK；现金与整数积分的部分退款按累计差额计算（`AftersaleService.java:31-35`）；超额退款保护把 UNKNOWN 退款计入（V6/V7 增量迁移 + 回填）。
4. **不依赖工作流框架的持久化旅程引擎。**
   - *机制：* 每事务执行一个节点并做 CAS 检查点、`SKIP LOCKED` 认领、以数据库时间计算等待、由唯一索引保护的确定性来源键、隔离 → 截止 → TIMED_OUT，以及由退款驱动的取消（`JourneyService.java:178-221`；测试 `PersistedCommerceTest:652,668`）。
   - *应坦诚讨论的弱点：* 共享的消费者事务。
5. **基于批次的积分：结算冻结、欠款与过期豁免。**
   - *机制：* 在 ≤200 个批次上做 FIFO 分配，CHECK `remaining+held+expired<=credited`，退回原批次（若已过期则作废），欠款由未来入账抵扣。
   - *证据：* 并发测试结果恰好为 {200, 409}（`PointsCheckoutTest:128-142`）。
6. **可安全重放的会员忠诚度对账。** 按来源、在原策略快照与下单时间下计算净贡献，因此重复、重放、以及先退款后完成的情况都会收敛（`MemberGrowthService.java:56-74`、`MemberCycleService.java:84-134`）。
7. **安全的规则 DSL。** 三值逻辑：过期或缺失的事实求值为 UNKNOWN，且不会被 NOT 翻转。以有界 AST 与可信字段白名单替代 Drools（`RuleEvaluator.java:29-56`）。

没有证据支撑（不要声称）：高 QPS 或 TPS、分布式系统或微服务、基于本项目的 Redis/MQ 专长、多地域、生产流量。

还要准备好坦诚说明：本项目由单一作者在 2 天内完成（git log），所有数字仅来自本地测试，支付、WMS 与 IdP 均为沙箱。

---

## 17. 未知项

| 未知项 | 为何重要 |
|---|---|
| 真实流量、订单量、会员数、活动数 | 决定热点行、上限以及约 20 事件/秒的吞吐上限是否构成问题 |
| 测试当前是否通过 | 本次只读分析未执行；最近的记录证据在 `docs/evidence/` 和 CI 中 |
| 生产部署目标、高可用、备份/恢复 RPO/RTO | 单 MySQL；无演练证据 |
| 真实支付/WMS/IdP 供应商选择与契约 | 决定回调、对账与解密的设计 |
| 多商户结算是否为真实业务线 | 决定 G11 是否启动 |
| 是否会有第二个应用消费这些能力 | 决定平台化（Phase 5）是否启动 |
| `platform_command.response_json` 是否包含 PII | 影响数据保留与合规 |
| 创建/释放竞争下的实际死锁频率 | 锁顺序相反是推断得出，并未观测到 |
| 团队归属模型 | 目前为单一作者；拆分标准依赖于此 |
| 未提交前端改动的行为 | 不纳入评判 |
