# Phase 8 验收矩阵

## Journey节点

| Node Type | Config | Runtime Input | Output | Side Effects | Retry | Idempotency |
|---|---|---|---|---|---|---|
| WAIT | seconds/next | 当前UTC、deadline | WAITING/next/wakeAt | 检查点+step | 未提交重做；提交后到期 | instance版本 |
| DECIDE | 既有RuleNode/yes/no | 当前可信会员/订单事实 | Truth/单路径；UNKNOWN终止 | step，无发放 | 原节点 | 纯规则+固定branch |
| GRANT | 固定benefit/version/next | member/store/source/order | BENEFIT_ACCEPTED/grantId | 原额度预留与Outbox | 原节点 | 原领域hash(instance/node)唯一 |
| COUPON | 固定definition/version/next | member/store/source | COUPON_GRANTED/couponId | 原券owner | 原节点 | 原领域唯一来源 |
| NOTIFY | title/body/next，原频控 | member/窗口cap | NOTIFIED或SUPPRESSED | 站内信/cap/effect | 原节点 | tenant/instance/node唯一 |
| END | 无后继 | fixed instance/deadline | COMPLETED/FINISHED | complete effect+step | 未提交重做 | instance版本/effect唯一 |

## 状态

| Object | State | Allowed Next States | Trigger | Guard | Recovery |
|---|---|---|---|---|---|
| Definition | DRAFT | IN_REVIEW | submit | admin/expected | 新版本改内容 |
| Definition | IN_REVIEW | APPROVED/REJECTED | approve/reject | admin/expected | 无任意设置 |
| Definition | APPROVED | PUBLISHED | publish | DAG/引用/时间/唯一发布 | publish重检 |
| Definition | PUBLISHED | PAUSED | pause/新版发布 | admin/expected | 新入组停止，在途继续 |
| Definition | PAUSED | PUBLISHED | publish | 仍有效/原校验 | 新入组恢复 |
| Instance | RUNNING | RUNNING/WAITING/COMPLETED/CANCELLED/TIMED_OUT/ISOLATED | 节点/失败/治理 | 原版本锁/受影响行数 | 原检查点 |
| Instance | WAITING | RUNNING/COMPLETED/CANCELLED/TIMED_OUT/ISOLATED | due/deadline | 当前资格锁读 | 不占sleep线程 |
| Instance | ISOLATED | RUNNING/CANCELLED/TIMED_OUT | retry/cancel/deadline | admin/未过deadline | audit原节点重试 |
| Instance | COMPLETED/CANCELLED/TIMED_OUT | 无 | 终态 | 不允许恢复/replay | 已有业务owner补偿 |
| Step | EXECUTING | COMPLETED/WAITING/STOPPED或回滚 | 同一节点事务 | 固定instance/version | 不保存未提交假历史 |
| Step | FAILED/DEFERRED/ISOLATED | 记录不修改 | 回滚后的失败事务 | 实际版本未变 | 新attempt保留同ordinal |
| Step | COMPLETED/WAITING/STOPPED | 记录不修改 | 原子commit | checkpoint同步 | 不重放早期动作 |

## 版本

| Journey Version | Referenced Rule/Activity/Benefit Version | Active Instances | New Entrants | Historical Explainability |
|---|---|---:|---|---|
| v1发布 | 内嵌固定RuleNode；固定benefit1，无latest引用 | 保留v1 | 允许 | 原definition1+真实steps |
| v2发布 | 独立内容/固定refs；不复用latest | v1继续，v2新入组 | 仅v2 | v1原图不变，测试1旧+1新 |
| v2暂停 | v2原内容不改 | 全部在途继续 | 不允许 | 保留旧路径与恢复审计 |
| OLD写入/执行 | 原schema1节点与refs | OLD/NEW共存 | 原入组唯一键 | LEGACY_PARTIAL/PARTIAL，不伪补 |

## 失败

| Failure Point | Durable State | Retry? | Recovery? | Duplicate Risk | Evidence |
|---|---|---|---|---|---|
| 节点事务前 | 原检查点 | 是 | 新worker | 无已提交效果 | 原事务机制/故障包装 |
| 权益已写、stepFinish前Error | 全事务回滚 | 是 | 新worker | 原来源与同事务 | JourneyRecoveryTest/crash-after-effect |
| 同窗口真实JVM kill | 原checkpoint、效果/step0 | 是 | 另一真实JVM | 同库回滚，连接显式释放 | process_recovery.py |
| commit后Error | 后继与成功trace | 后继 | 新worker | 不重做早期grant | crashAfterCommitDoesNotReplay... |
| WAIT中kill | 固定WAIT/版本/wake | 到期后 | 真实新JVM | WAIT无需外部效果 | 25秒真实restart |
| UNAVAILABLE | DEFERRED、原ordinal、attempts0 | 有截止 | 原节点自动 | 回滚未提交动作 | transientFailureRollsBack... |
| 永久失败5次 | ISOLATED、旧node/steps | 不自动 | admin retry/audit | 不重发早期动作 | laterPoisonFailure... |
| 失败回滚后另一实例推进 | 成功后继 | 旧失败跳过 | 无需 | 版本守卫防污染 | staleFailureCannotAttach... |
| 取消/全退/截止 | 停止后续；历史保留 | 否 | 原领域补偿 | 不自动撤销效果 | 原cancel/deadline/refund测试 |

## Runtime

| Work Type | Tenant Fairness | Batch | Retry | Breaker | Quarantine | Multi-Instance |
|---|---|---|---|---|---|---|
| Journey节点 | TenantRotation持久候选+进程游标 | 每tenant5；总200/500ms协作 | 原deadline，永久5次 | 共享FailureClass，连续3暂态 | ISOLATED | 真实2 JVM/row lock/唯一效果 |
| 生命周期scan | 同journeys lane | 每tenant≤20 | 原scan检查点 | 同breaker | 原scan isolation | SKIP LOCKED/版本 |
| events | 既有events轮转/优先车道 | 原预算 | Inbox/失败分类 | 既有 | 既有 | 全套原回归 |
| payments/refunds/orders | 原独立车道/调度 | 原预算 | 原对账/过期 | 既有 | 原恢复语义 | 真payment/event与hot Journey同时推进 |
| points/cycles/deliveries等 | 原独立共享调度池 | 原预算 | 既有 | 既有 | 既有 | 原MySQL/架构/公平回归 |

## J1–18

| Scenario | 可执行证据 | 状态 |
|---|---|---|
| J1 publication | 原审批测试、S1发布重检、JourneyRecovery.version | PASS |
| J2 invalid graph | JourneyGraphTest4、Persisted graph/未知kind/rule | PASS |
| J3 duplicate trigger | 真支付不同传输eventId同orderId、manual复用 | PASS |
| J4 WAIT restart | process_recovery真实SIGKILL/两个新JVM | PASS |
| J5 true branch | paidJourneyCompletes...、history MATCH/1权益 | PASS |
| J6 false branch | waitBranchUsesCurrentTags...撤销→NO_MATCH/0权益 | PASS |
| J7 action success | 真实权益AVAILABLE/1ledger；原券/通知 | PASS |
| J8 transient retry | JourneyRecovery.transientFailure... | PASS |
| J9 permanent failure | JourneyRecovery.laterPoison...5次隔离审计原节点 | PASS |
| J10 two instances | 真实8628/8629竞争、唯一grant | PASS |
| J11 effect/completion crash | 真实窗口kill+事务可见效果包装测试 | PASS |
| J12 v1/v2 active | JourneyRecovery.newVersionAndPause... | PASS |
| J13 historical v1 | 同测试固定definition1；OLD/NEW partial | PASS |
| J14 pause entry | 同测试新入组拒绝，在途继续 | PASS |
| J15 tenant | HTTP+同tenant另一member/foreign/recovery | PASS |
| J16 waiting population | 50k真实关系库合成future行、无领取；25秒真实WAIT另证 | PASS |
| J17 hot fairness | hot5k/normal5，新tenant先完成hot未清空 | PASS |
| J18 mixed lanes | hot时sandbox PAID→payment车道→order PAID→fulfillment Inbox | PASS |

## JRN1–20

| Invariant | 证据映射 | 状态 |
|---|---|---|
| JRN1 immutable | 版本创建冲突+DB主键；定义无UPDATE内容 | PASS |
| JRN2 fixed version | instance字段/history版本测试 | PASS |
| JRN3 new publish | v1在途/v2独立；pause不移动 | PASS |
| JRN4 entry dedup | J3 business orderId，不只是Inbox同eventId | PASS |
| JRN5 WAIT restart | J4 | PASS |
| JRN6 tenant starvation | J17及TenantRotation原公平回归 | PASS |
| JRN7 lane starvation | J18及实际schedule/原EventWorkerLaneTest | PASS |
| JRN8 pure conditions | 原RuleEvaluator15+Preview无副作用+当前事实 | PASS |
| JRN9 branch deterministic | J5/J6/UNKNOWN，颠倒分支mutation被捕获 | PASS |
| JRN10 action dedup | 实际来源唯一+并发+恢复ledger1 | PASS |
| JRN11 crash convergence | J11 | PASS |
| JRN12 multi-instance | J10真实双JVM | PASS |
| JRN13 no early replay | laterPoison...以及commit后Error | PASS |
| JRN14 auditable recovery | Commands+RecoveryAudit，恢复测试APPLIED1 | PASS |
| JRN15 replay blocked | control未知replay拒绝+replaySafety=false | PASS |
| JRN16 tenant everywhere | J15与mapper绑定tenant/管理权限 | PASS |
| JRN17 historical explainable | J13、完整/部分覆盖真实说明 | PASS_WITH_LIMITATIONS（不存完整Facts） |
| JRN18 reuse | 既有领域API、架构ModuleBoundaryTest3、无新增infra | PASS |
| JRN19 no cycles/unbounded | J2/32DAG/deadline/5隔离/预算 | PASS |
| JRN20 real full journey | 真支付事件/WAIT/DECIDE/grant/END/AVAILABLE/ledger | PASS |
