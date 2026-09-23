# 统一电商架构审查

审查基线：S10b 当前工作树，2026-09-23。源文件相对项目根目录；验证摘要见 docs/evidence/s10b/。审查以本地建设验收为范围，生产上线条件单独记录。

## 1. 范围与能力状态

已实现：DDD Maven模块、单Boot应用、MySQL权威数据、状态机、原子预占、幂等命令、Outbox/Inbox、支付/退款未知状态、券预算权益补偿、持久旅程、低代码治理及真实前端。可选开关：sandbox-enabled 和 workers-enabled 在 application.yml 默认 false，本地 Compose 显式配置；不是健康检查通过就自动有后台履约。外部 IdP/支付/权益/WMS 按用户要求后置。没有 Redis、独立 MQ、配置中心或 LLM 调用。

## 2. 十个维度的发现

| 维度 | 证据与结论 |
|---|---|
| 架构边界 | architecture-tests/ModuleBoundaryTest 对生产模块编译类执行 jdeps；跨业务所有者只能引用 api，kernel/runtime共享。App装配跨模块事务；这种同库耦合不能直接换HTTP拆服务。静态检查不覆盖所有SQL语义/反射。 |
| 数据一致性 | Commands 把幂等回执、业务和审计放同一事务；Outbox与状态一起写；EventDispatcher同事务Inbox和副作用。PaymentService在事务外查渠道，在行锁内验证金额/币种/租户/订单/终态。没有把发送成功当收款。 |
| 并发 | 真实MySQL测试覆盖库存、预算、券/权益、退款及旅程竞争。JourneyService本次修正失败计数使用实际领取版本；SKIP LOCKED、条件版本及唯一键承担约束。未做真实多进程并发容量试验。 |
| 可用性 | 只有一个应用和一个MySQL，无主从故障转移。后台四类工作在单个Scheduled方法顺序运行；有界批次但真实渠道慢请求可能延迟后续类别（当前渠道是本地沙箱）。 |
| 性能 | 请求体64KiB、连接池8、Tomcat64线程、SQL10秒、事务10秒；这些是资源边界而非吞吐保证。热点库存/预算/权益定义行串行，发布列表与租户扫描需以实际分布压测。 |
| 消息 | 无外部Broker；数据库任务至少一次尝试，Inbox仅保证本地事务副作用。失败退避并隔离；未订阅事实保留PENDING，不属于正在失败的消费，但会增长。 |
| 数据库 | Flyway V1–V15、表/列注释、唯一/检查约束、有界游标查询；历史迁移不可更改。无归档和恢复演练，迁移兼容性不能推导任意版本回退安全。 |
| Redis | 不适用，无缓存权威或缓存失效窗口；不因10倍流量假设直接增加Redis。 |
| 安全 | 只接受摘要校验Bearer，服务端ADMIN/MEMBER及tenant检查；无任意脚本/SQL低代码。地址AES-GCM并绑定tenant/order AAD，响应不带明文。当前本机HTTP/长期演示令牌/单地址密钥，不构成生产身份和密钥治理。 |
| AI额外风险 | 不适用；平台业务未接入LLM、RAG、MCP或执行模型生成命令。 |

## 3. 风险清单

未发现待处理P0的具体证据；这不是完整渗透测试结论。

### [P1] MySQL单点和恢复证据缺失
- 问题：应用依赖单MySQL，未验证备份可恢复或主切。
- 触发条件：数据库损坏/停机/主机丢失。
- 影响范围：鉴权、所有读写与后台处理；地址密钥遗失还影响密文恢复。
- 源码证据：compose.yaml仅引用外部网络；commerce-app/src/main/resources/application.yml单datasource；order-runtime/.../AddressCipher.java单密钥。
- 修复方案：生产前由环境负责人确定HA/备份与恢复目标，使用隔离目标演练数据库、密钥及未完成任务一起恢复。当前不触碰共享库。
- 改造成本：中；优先级：生产前P1。
- 能力状态：规划缺口，非本地验收阻塞。

### [P1] 真实身份及外部副作用适配未验收
- 问题：当前仅本地Bearer和显式沙箱，不能处理真实渠道验签/IdP映射。
- 触发条件：直接暴露到公网或将沙箱误作真实收款/发货。
- 影响范围：账号与资金事实、权益/履约结果可信度。
- 源码证据：commerce-app/.../SecurityConfiguration.java；payment/.../SandboxPaymentChannel.java、SandboxRefundChannel.java；deploy/env.example默认sandbox关闭。
- 修复方案：按已预留端口完成真实适配、验签与查单、超时/重复/乱序联调；部署接TLS和受控身份。用户要求整体完成后再进行。
- 改造成本：中；优先级：真实联调/生产前P1。
- 能力状态：明确后置，不能冒充已完成真实支付/WMS。

### [P2] 数据积累与保留策略未落地
- 问题：命令、审计、订单密文、事件、旅程及测试租户持续增长，未订阅事件保留PENDING。
- 触发条件：长期运营/反复灌数，事件/命令表增长导致索引和备份成本上涨。
- 影响范围：数据库存储、工作队列扫描、敏感数据保留。
- 源码证据：platform-runtime/.../EventDispatcher.java仅消费types；mappers/runtime/EventMapper.xml；scripts/seed-local.py按日期新建版本；无归档作业。
- 修复方案：先确认保留/删除依据及幂等回放兼容窗口，再实现有界归档任务、索引及恢复后的删除传播。禁止随意删历史账本。
- 改造成本：中；优先级：长期运营前P2。
- 能力状态：规划缺口。

### [P2] 热点事务与共享调度线程缺少容量证据
- 问题：连接池8、热点行预占和单Scheduled串行四类任务，有界不等于公平延迟达标。
- 触发条件：热门SKU/单活动大促，或未来真实渠道发生长时间超时。
- 影响范围：下单锁等待、用户请求超时、支付后权益/旅程延迟。
- 源码证据：application.yml；inventory/.../InventoryMapper.xml；marketing-runtime/.../BudgetMapper.xml；commerce-app/.../EventWorker.java；PaymentService.tick。
- 修复方案：先量测真实分布和积压；为真实适配明确短超时/总截止时间，再依据证据隔离调度预算或线程，不盲目扩大池/增加中间件。
- 改造成本：中；优先级：容量验收前P2。
- 能力状态：容量规划缺口；当前无QPS或p99达标承诺。

### [P2] 地址密钥轮换和外部授权解密待设计
- 问题：AddressCipher只使用一个运行密钥加密，尚无密钥版本轮换或WMS按授权解密入口。
- 触发条件：密钥轮换/丢失，或真实仓储需要收件地址。
- 影响范围：历史地址可恢复性与最小披露。
- 源码证据：order-runtime/src/main/java/com/lrj/commerce/ordering/infrastructure/AddressCipher.java。
- 修复方案：外部WMS联调时增加受控密钥版本和审计解密适配；保留旧密钥直到对应密文过期/迁移完成，不把明文暴露给普通订单接口。
- 改造成本：中；优先级：真实WMS前P2。
- 能力状态：规划缺口。

### [P3] 前端体积、资产列表与监测能力有界但有限
- 问题：构建包提示较大；部分营销资产/会员列表显示首批50条；只有健康、日志和运营状态列表，没有生产告警平台。
- 触发条件：低速网络、大量资产，或无人值守长期运行。
- 影响范围：首次打开、资产查找、失败发现时间。
- 源码证据：frontend/src/features、frontend构建日志；application.yml仅暴露health；EventDispatcher.list。
- 修复方案：按真实使用拆路由包、补齐游标交互，接积压时长/隔离数量/支付未知告警和处置手册；不要把数据库行数当高基数指标标签。
- 改造成本：低至中；优先级：P3。
- 能力状态：已实现能力的后续增强。

本次修复记录：ApiErrors将缺query、不存在资源、错误HTTP方法分别映射400/404/405（新增集成验收）；JourneyService失败回写绑定锁定节点版本，已有并发/隔离恢复测试通过。没有通过抹除失败日志伪造第一次运行成功。

## 4. 10倍流量推演

没有生产基线QPS，以下按当前调用扇出、热点行和资源边界推演，不是实测排序或达标承诺。

| 资源 | 最可能的压力/事故 | 最小响应 |
|---|---|---|
| 数据库 | 热SKU/预算行锁先排队，8连接饱和 | 实测锁等待/执行计划/事务时间，缩短临界段并限制热点并发 |
| Redis | 未使用，不存在缓存雪崩 | 不无证据加缓存到资金/库存决策 |
| MQ | 数据库Outbox积压，异步效果变慢 | 量测最老已订阅事件年龄；调整有界批次及租户预算 |
| JVM | 768MiB容器和UI/API共进程，序列化/GC上涨 | 测堆/GC/响应大小；保留输入/批量上限 |
| 线程池 | 64请求线程等待8连接，排队和503增加 | 按DB容量设置入口并发与拒绝；不能只加线程 |
| RPC | 当前没有真实远程渠道 | 真实接入先规定连接/读取/总超时和幂等 |
| 下游 | 单MySQL全链路强依赖 | 恢复优先级和隔离环境容灾演练 |
| 网络 | 同源静态大包首次下载、DB流量增加 | 实测静态压缩/缓存策略及路由拆包；TLS在生产入口处理 |
| 存储 | 事件/幂等/审计增长、测试租户积累 | 业务确认保留策略后归档，备份恢复验证 |

## 5. 故障演练与证据边界

| 故障 | 期望/代码行为 | 本次证据与缺口 |
|---|---|---|
| Redis宕机 | 无此依赖 | 不适用 |
| Kafka/MQ不可用 | 无独立Broker；DB不可用会停止任务 | 不适用Broker演练，不能声称验证MQ高可用 |
| MySQL主切 | 鉴权失败关闭，事务不成功；恢复后持久任务继续 | 未做主切/断连/备份恢复，禁止影响共享实例 |
| 第三方超时 | 支付/退款保留UNKNOWN与原意图，不盲目释放或重复资金副作用 | 真实DB测试验证沙箱UNKNOWN/后续对账；真实渠道网络故障未测 |
| 单实例宕机 | 已提交检查点恢复、未提交事务回滚 | S9a真实WAITING停止应用/重启后COMPLETED；S10b另有容器重启持久数据检查 |
| 多实例同时恢复 | SKIP LOCKED/条件版本/唯一键/Inbox阻止重复效果 | 集成测试并发调用通过；未进行多容器同时宕机恢复演练 |
| 消息积压/毒任务 | 有界租户轮转、退避、5次隔离、管理员重放 | 集成测试失败注入/重试/隔离恢复；未做大量积压吞吐压测 |
| 配置中心不可用 | 无配置中心，环境启动时加载；缺密钥/DB配置失败 | 不适用；没有动态配置灰度/刷新能力 |

## 6. 分期改进

短期：完成本地容器/浏览器/CI证据、私密文件检查，保持外部后置事实清晰。真实联调前补齐身份、支付验签、WMS地址治理和超时合同。中期：按实际数据量做热点压测、积压告警、保留策略、隔离恢复演练。长期：只有独立扩容、故障隔离或团队所有权的实测收益足够时再评估模块抽取；当前不建议新增微服务、K8s、ES或第二种消息系统。

## 7. 源码证据索引

- 边界：architecture-tests/src/test/java/com/lrj/commerce/architecture/ModuleBoundaryTest.java。
- 事务/事件：platform-runtime/src/main/java/com/lrj/commerce/runtime/{Commands,EventDispatcher,Outbox}.java，src/main/resources/mappers/runtime/{CommandMapper,EventMapper}.xml。
- 支付：payment/src/main/java/com/lrj/commerce/payment/application/{PaymentService,RefundService}.java，api/{PaymentChannel,RefundChannel}.java。
- 库存/预算：inventory/src/main/resources/mappers/inventory/InventoryMapper.xml，marketing-runtime/src/main/resources/mappers/campaign/BudgetMapper.xml。
- 旅程：marketing-automation/src/main/java/com/lrj/commerce/journey/application/JourneyService.java，src/main/resources/mappers/journey/JourneyMapper.xml。
- 安全/错误：commerce-app/src/main/java/com/lrj/commerce/app/{SecurityConfiguration,ApiErrors}.java。
- 验证：commerce-app/src/test/java/com/lrj/commerce/app/PersistedCommerceTest.java；frontend/tests/commerce.spec.ts；docs/evidence/s9a/restart.json；docs/evidence/s10b/。

Review gate：本地建设范围 PASS_WITH_ASSUMPTIONS；真实联调/生产部署不在此结论内。修复、验证、文档与Git均沿用户既有授权继续，不据此自动授权生产上线。
