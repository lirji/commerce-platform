# 后端架构

Owner：backend-architecture-design。用户约束：DDD + 模块化单体。应用目标一个业务进程；外部 IdP、支付渠道、WMS 等按适配边界保留。S0–S10 已实现单一 Spring Boot HTTP进程、MySQL持久化、持久支付沙箱、本地事件和旅程 Worker，以及同源 React 管理台/会员端。下表以当前逻辑所有权为准。

## 逻辑模块及数据权威

| 模块 | 聚合/权威数据 | 对外协作 |
|---|---|---|
| member | Member、主体绑定、会员状态 | MemberEligibility API / MemberRegistered |
| merchant | Merchant、准入、收款主体绑定 | MerchantAvailability API |
| store | Store、商家归属、营业与履约范围 | StoreAvailability API |
| catalog | Product、SKU、发布版本 | CatalogSnapshot API |
| inventory | Reservation、可售量/额度 | Reserve/Confirm/Release API；仓内库存归 WMS |
| marketing + marketing-runtime | 纯规则/优惠计算；campaign包拥有活动、人群、规则、预算 | Decision / Campaign / Budget API |
| marketing-automation | journey/ops包拥有旅程定义、实例、通知、低代码页面 | Journey API、OpsPage API；通过其他域API执行动作 |
| benefit | Entitlement、GrantOrder、WalletEntry | Hold/Commit/Release/Compensate API；内部台账单一权威 |
| trade | Quote、价格/优惠分摊（购物篮为前端当前会话状态） | Quote API；编排只消费其他模块 api |
| order | order纯状态机 + order-runtime的订单、行、加密地址 | Place/Cancel/Confirm API；OrderPaid/Cancelled/Completed |
| payment | PaymentAttempt、ChannelEvidence、RefundAttempt | 请求渠道适配，输出可信支付/退款事实 |
| fulfillment | FulfillmentOrder、物流或服务履约进度 | 请求 WMS/物流；Delivered/Failed |
| aftersales | AfterSaleCase、退货、退款审批、补偿计划 | 编排支付退款、入库及权益冲正，不能直接改其表 |

shared-kernel 仅 Money、稳定标识校验等无业务所有权的值类型。禁止发展成通用 Service/Repository 仓库。每个业务模块内部按需要使用 api/domain/application/infrastructure，不为简单模型制造空接口。

依赖：装配层 → 各模块 application → 本域 domain；跨域只引用 api 与不可变值。trade 编排 member/merchant/store/catalog/inventory/marketing/benefit/order 的端口，order 不反向调用 trade 实现。payment 与 order 的协作由应用装配器连接，不互引 Mapper。所有持久化 Entity 和 SQL 留在本域。Maven 模块和架构测试约束依赖；所有领域不是一开始就创建空模块。

## 一致性和恢复

物理库可共用 MySQL，表按域前缀、单写 Owner、各域 Mapper 独占。下单用例协调报价消费、订单创建、库存/权益预占，允许同库本地事务跨端口；这是明确的事务耦合，未来拆进程须重新设计，不能把端口换 HTTP 即声称等价。任何远程支付、发券、WMS 调用在该事务外进行。

支付成功/订单变更与 Outbox 同事务落库。发布至少一次；消费者用 tenant+consumer+eventId 去重并同事务完成业务效果，聚合版本防旧消息覆盖。一个单体内可先用数据库持久任务投递，不默认增加 Broker；连接既有平台时才接已有消息系统。任务有限批次、退避、隔离和人工重放；数据库行锁及条件版本控制并发，旅程另有截止时间。不能用内存事件监听器证明重启可恢复。同一事件的每个消费者独立事务提交 Inbox 与效果，失败只回滚并重试该消费者，其余消费者照常生效；全部消费者成功后才标记投递，隔离只阻塞未完成的消费者。调度线程内各后台任务相互隔离，单个任务或单行坏数据异常不跳过其他任务。

事件调度契约（EventDispatcher，每轮约1秒预算后固定延迟1秒）：租户内按到期时间FIFO，不做全局FIFO；单次访问一个租户最多5条。每轮先访问最近10秒内新到期事件的租户（最多用半个预算，按最早到期排序），再按租户字典序游标轮转积压，走到末尾回到开头，直到时间或2000次尝试预算用完、或整圈无尝试。因此任一有到期事件的租户最迟在一整圈轮转内被尝试，新到期事件通常下一轮即被尝试；一圈耗时约为各租户min(到期数,5)之和×单条成本÷调度占空比，由commerce.events.rotation.last观测。全局只有一个租户有积压时连续处理，不重复扫描。失败重试按2/4/8/16秒退避，同一事件累计5次失败置ISOLATED并记录last_error（消费者与异常类型，不含载荷），只能由管理员审计重放，重放只执行未成功的消费者；毒事件最多占用5个名额。多实例并发时领取即事务内SKIP LOCKED行锁，无跨事务租约：崩溃回滚释放锁，已提交消费者由Inbox去重，不重复执行、不丢失。platform_event(status,tenant_id,available_at)索引使发现与租户内取数只扫描待投递行。没有消费者的事件类型见下文“无消费者事件”。

后台运行时（第三阶段）：10个后台车道（payments、refunds、orders、events、segments、journeys、cycles、points、deliveries、catalog-jobs）各自是独立的固定延迟1秒任务，共享有界调度线程池（commerce.worker-threads，默认3，上限6）；调度器按下次触发时间先后取任务，慢车道只占一个线程，任一车道等待开始不超过约⌈(车道数-1)/线程数⌉个车道运行时长，LaneMonitor记录开始延迟与耗时。每个后台线程同一时刻最多占用一个数据库连接，3线程在8连接池中给请求线程保留5个。租户型车道共用platform-runtime的TenantRotation：按租户字典序游标分批发现（每批50租户）、单次访问最多quantum项、一轮受时间与项数预算约束、走到末尾回到开头直到预算用完或整圈无尝试，单租户独占时连续处理；租户内顺序、事务与重试状态由各车道负责。车道预算：orders到期10单/访问、200单或500毫秒/轮；payments与refunds核对5笔/访问、100笔或1秒/轮；points 20批次、cycles 10名会员、deliveries与catalog-jobs 20步、journeys 25步（均200项或500毫秒/轮），segments 3步（30步或500毫秒/轮）；events沿用5条/访问、2000条或1秒/轮并保留新到期优先阶段。订单到期改为逐单事务（SKIP LOCKED领取并复核），单个坏订单不再让同租户整批回滚；积分过期与周期考核由全局到期FIFO改为租户轮转。发现与租户内取数由(状态,租户,到期)索引支撑（V37），发现按单一状态沿租户顺序扫描并显式指定索引，避免优化器误选主键扫过大租户历史。

失败语义（第三阶段）：FailureClass只按异常类型层级、SQLState类别与领域错误码分类，不匹配异常文本。瞬时类（DEPENDENCY_UNAVAILABLE、CONCURRENCY_RETRYABLE、TRANSIENT）不消耗毒工作预算：事件与订单到期单独累加瞬时次数，2秒起指数退避至5分钟封顶、20%抖动，300次（约27小时）才隔离；支付与退款核对退回已领取的核对次数；带业务截止时间的车道只延后约32秒且不计次，由截止时间终止。其余类（BUSINESS_REJECTED、DATA_CORRUPTION、CONFIGURATION_ERROR、PERMANENT、UNKNOWN）走2/4/8/16秒、第5次隔离的有界预算。同一事件任一消费者非瞬时失败即按毒事件计数。车道依赖熔断：连续3次瞬时失败暂停该车道5秒起翻倍至1分钟（依赖恢复后最迟1分钟内恢复处理），冷却期不领取、不计数，首个成功关闭；发现查询失败（含事件新到期阶段）同样计入。新到期优先阶段只看从未失败过的事件，到期重试走轮转，重试风暴不挤占新事件。隔离证据：failure_class、last_error（消费者与异常类型）、两类计数、首末失败时间、人工重放次数，不含载荷。管理员重放（事件ISOLATED或SKIPPED、订单停止自动到期）走审计命令，只重置状态与计数，保留证据，只执行未完成的消费者。

无消费者事件（B1）：发布方以UnconsumedEventType声明本进程无消费者的类型，Outbox写入即为终态SKIPPED并记录skip_reason，行保留供审计与管理员重放；当前只有order.fulfilling.v1（订单状态以order_record为准，无外发中继、无契约要求消费者）。未声明又没有消费者的类型视为缺少必需消费者，保持PENDING、计为unrouted并以EVENT_NO_REQUIRED_CONSUMER告警，绝不静默跳过；声明的类型同时存在消费者时启动失败。V36把历史PENDING的order.fulfilling.v1转为SKIPPED。

事件载荷兼容：类型名带版本（*.v1），处理器以聚合ID回读权威状态为主。JsonCodec读取时未知字段忽略、缺失的基本类型字段失败；新增必填基本类型字段会使历史行失败并进入隔离。已知历史缺口只有8c20699时期券报价快照缺platformFundingBps，按V10迁移默认值0（商家全额承担）在读取时补齐，不引入通用事件升级框架。

积压诊断：GET /v1/admin/events/health只返回本租户到期数、最老到期秒数、重试/隔离/无消费者计数和近期最大投递延迟；全局Micrometer指标commerce.events.*不带标签，commerce.lanes.*只带固定车道名标签。跨租户聚合（B2）只经GET /v1/platform/runtime提供给PLATFORM_OPERATOR角色（能力EVENT_RUNTIME_METRICS_READ，路由与用例两层校验）；租户管理员不隐含跨租户能力，访问任何/v1/platform路径为403，平台运维在平台命名空间内访问不存在路径为404、访问任何租户接口为403。车道告警每分钟评估一次：LANE_STARVATION（等待开始>30秒）、LANE_DEPENDENCY_UNAVAILABLE（熔断打开或新增熔断）、LANE_BACKLOG_AGE（最老到期>5分钟）、LANE_QUARANTINE_GROWTH、LANE_ROTATION_SLOW（一圈>5分钟），事件另增EVENT_NO_REQUIRED_CONSUMER与EVENT_DEPENDENCY_UNAVAILABLE。调度线程每分钟输出一次全局健康日志，超过阈值以固定代码告警：EVENT_BACKLOG_AGE（最老到期>5分钟）、EVENT_ROTATION_SLOW（一圈>5分钟）、EVENT_BACKLOG_GROWING（连续5分钟增长）、EVENT_QUARANTINE_GROWTH、EVENT_FAILURE_RATE（≥20次尝试且失败>20%）、EVENT_NO_PROGRESS；外部告警投递未接入。调度线程完全停止时不会产生日志，需外部探测管理端接口。

调度、重试、恢复、重放、保留（第四阶段）彼此独立：调度只表达业务何时到期；重试是同一执行在可恢复失败后的自动继续（RetryPolicy两类预算）；隔离是自动处理停止；恢复是租户管理员把停止的工作放回（RETRY）或终止（SKIP，仅事件）；重放是刻意对历史DELIVERED事件重新执行单一消费者；保留期只删除可证明不再需要的数据。恢复只重置重试状态并保留失败证据，不改变业务到期时间；重放从不改变事件状态；保留期从不触碰非终态行。

逐项重试（第四阶段）：积分过期与周期考核在会员域member_work_retry记录失败项（仅失败期间存在，成功事务内删除），瞬时失败只累加瞬时次数，5次非瞬时失败或300次瞬时失败隔离；退避与隔离中的项不参与到期查询，一个坏项不再反复占用同租户的处理机会。周期考核到期由member_record.cycle_due_at（非空，未考核取纪元值，注销取9999-12-31）与(tenant_id,cycle_due_at)索引表达，考核后取周期结束与下一策略生效时间中较早者；新策略按会员主键每批500名推进（rolled_out/rollout_cursor比较交换），不在发布事务里更新整租户；发现只读每个有策略租户在该索引上的第一项（ORDER BY必须写全索引前缀），代价与策略租户数而非会员数成正比。

恢复（第四阶段）：各模块以runtime.api.RecoverableWork登记可恢复工作类型（event、order.expiry、member.points.expiry、member.cycle.assessment），RuntimeRecovery统一授权（RUNTIME_RECOVERY_READ/EXECUTE，仅租户管理员、仅本租户）、限定范围（每次1至50个显式标识、可附带期望失败分类护栏、必须填写原因）、幂等（Commands）与审计：platform_recovery逐项记录操作者、时间、工作、前后状态、失败分类、原因与结果（含被拒绝项），与状态变更同一事务。恢复只做条件更新并先加行锁读取，保留失败分类、首末失败时间与最后错误，之后由原车道按原预算执行；事件恢复只执行尚无Inbox的消费者。旧的单项重试与各模块的重试/取消控制也写同一审计。

重放（第四阶段）：消费者以EventHandler.replaySafety声明副作用分类（PURE、IDEMPOTENT_WRITE、DEDUP_PROTECTED、COMPENSATABLE、IRREVERSIBLE、EXTERNAL、FINANCIAL）与依据，未声明即未分类。ReplayGate在创建、恢复与每一项执行前校验：资金、外部、不可逆副作用无论声明如何一律拒绝，未分类拒绝，重新执行已处理事件只允许纯投影。当前只有marketing-effects-v1可重放，其余消费者REPLAY_NOT_SUPPORTED，未完成工作走恢复。任务（platform_replay）限定本租户、单一消费者、事件类型、31天内区间与1万事件上限，每租户最多3个活动任务；独立重放车道小预算（200毫秒或100项），有消费者的实时到期事件达到阈值时整轮让路；每项一个事务锁任务行与事件主键（共享锁），效果与游标同一事务提交；UNPROCESSED以Inbox去重，失败项计数跳过，10次失败任务失败；可暂停、恢复、取消，均写审计。

保留期（第四阶段）：保留时长属于产品/法务决定，commerce.retention默认关闭；开启时每类时长须显式配置且不低于下限（事件7天、命令30天）。只删除DELIVERED、SKIPPED事件及其Inbox（同一事务，先Inbox后事件）与已完成的命令；PENDING、ISOLATED、审计、恢复审计、重放任务永不由此删除，运行中或暂停的重放区间内的事件不删除。第12条车道每5秒一轮，READ COMMITTED、SKIP LOCKED、每批500、每轮至多2000行或300毫秒，实时积压时让路；删除数与锁定数不一致时回滚。告警代码新增REPLAY_BLOCKED、RETENTION_LAG_HIGH、RETENTION_FAILURE，统一经OperationalAlertPublisher发布（外部供应商未选定前只写日志）。

支付超时进入未知/待查，不能当失败退款或释放库存；取消已发起支付的订单进入 CLOSING，待可信未收款事实或资金处理。开启后台任务时，到期未付订单由调度自动请求取消（与管理员到期同一转换）；进入 CLOSING 时支付域重新安排渠道核对，自动关闭或确认迟到付款。订单确认/释放与下单预占使用同一加锁顺序：积分→券→预算→权益→库存（SKU 升序）。退款/权益冲正是新业务效果，不通过订单状态回退删除历史。报价是不可变快照，绑定 SKU/活动/规则/人群版本；可接受陈旧窗口由报价 TTL 和下单二次校验共同定义。

## 安全、边界和演进

租户、商家、店铺、主体由可信授权上下文及主数据映射获取，不能信任前端 tenantId 或人群事实。低代码只操作允许的字段/节点/操作符，配置发布要 schema 校验、版本、预览、审批/审计和可回退版本；当前为单活版本，不声称已实现流量灰度。规则不访问数据库/网络，不执行任意脚本，不承担授权决策。HTTP 授权默认拒绝：管理、经营和会员路径分别显式登记，新增接口未登记即 403。状态码契约：调用方无权进入的命名空间一律 403（匿名 401），不论路径是否存在，不借 404 暴露接口清单；有权进入的命名空间内不存在的路径才是 404（如管理员访问 /v1/admin/不存在、会员访问 /v1/members/me/不存在）；AuthorizationCoverageTest 以真实控制器映射校验会员清单，ModuleBoundaryTest 同时约束应用壳只经 api 调用各域。

日志关联 requestId/orderId/eventId，避免敏感地址及高基数指标；追踪渠道未知结果、预占超时、Outbox 延迟、权益悬挂和补偿积压。生产容量和恢复指标待真实测量。新增数据迁移先扩展后收缩；源仓迁移必须独立兼容评估，不能在新项目内悄悄替换旧路径。

Future Service Map：各逻辑边界当前均 KEEP_AS_MODULE；支付外联/规则计算/旅程运行若出现实测资源竞争、独立发布或隔离需求，再评估抽取。拆分触发需要实际负载、团队/运维责任、失败隔离收益和成本证据，当前没有拆服务决定。

边界验证：architecture-tests 对全部生产模块编译产物执行 jdeps，跨域只可引用 api；真实数据库测试覆盖租户、原子预占、退款、重复事件及旅程并发。静态约束不能证明反射/运行时完全隔离，同库事务也不等于可直接拆成远程服务。生产容量、容灾和外部契约仍待专门验证。
