# 既有 Journey 能力盘点

限定 Journey 及直接协作能力；没有重新盘点全仓业务。以下是源码事实，现有测试基线通过不等于所有 Phase 8 新验收已经证明。

| 能力 | 当前模型 | 持久化 | Runtime / 版本 | Retry / Recovery | 缺口与风险 |
|---|---|---|---|---|---|
| 定义 | JourneyApi.Definition + typed Node、entry、next/yesNext/noNext | journey_definition.definition_json | 固定内容version；状态和lockVersion可变 | Commands审批审计 | 无结构化校验结果/预览；发布未重检全部图与权益引用 |
| 图 | WAIT、DECIDE、GRANT、COUPON、NOTIFY、END；entry相当于起点，边在Node内 | typed JSON | 32节点DAG、全可达、有限深度RuleNode | 创建拒绝环与悬空边 | 通用 INVALID_INPUT；公共契约不必增加 START/ACTION 名字重写既有UI |
| 发布 | DRAFT→IN_REVIEW→APPROVED→PUBLISHED→PAUSED；REJECTED | 单发布版本generated唯一键 | group行锁与expected版本；新发布暂停旧版本 | 显式pause/publish | pause只阻止新入组；运行实例不检查当前发布状态，保持原版本 |
| 触发 | ORDER_PAID、会员事件、生命周期扫描、MANUAL | 既有Outbox/Inbox；event_key | JourneyService implements EventHandler；支付来源orderId | 原事件运行时重试 | adapter与图运行同类但方法分开；无独立引擎必要 |
| 入组 | tenant+journey+version+event_key唯一，校验来源会员/订单 | journey_instance；journey_member_cap | 支付键orderId；可选跨版本窗口频控 | 重复返回原实例 | 明文产品命名缺失；应记录现行 ONCE_PER_TRIGGER/version语义，不能改成一次终身 |
| 实例 | RUNNING/WAITING/ISOLATED/COMPLETED/CANCELLED/TIMED_OUT | current_node、due_at、deadline、steps、attempts、version | 绑定journeyVersion；每节点短事务 | 原检查点retry/cancel，RecoveryAudit | 当前节点不能还原逐节点路径、条件决策、失败分类与动作结果 |
| 等待 | 1..604800秒，最长旅程2592000秒 | due_at+绝对deadline | 注入Clock；WAIT提交后指向next；due<=now追赶 | 重启可发现 | 需要真实进程重启/kill证明；现有测试多为手动推进due |
| 条件 | RuleNode→Condition、RuleDecisionPort、MemberRuleFacts | 定义冻结AST；当前Facts临时构造 | MATCH走yes；NO_MATCH走no；UNKNOWN终止 | 无副作用 | 缺历史决策/时间证据；execute重复查询member facts；不新增规则DSL |
| 权益 | EntitlementApi.grantFromJourney、CouponApi.grantFromJourney、持久NOTIFY | benefit各自权威表；journey_effect/notification | 引用固定benefit/coupon版本；source hash(instance/node) | 领域来源幂等；效果与advance同事务 | bounded switch已有多个类型；应提取小型显式handler边界，禁止直接写benefit表 |
| 调度 | journeys既有车道，TenantRotation | 数据库due/discovery查询 | 每租户20扫描步+5实例步，每轮200项/500ms；3调度线程 | FailureClass、RetryPolicy、breaker、5次ISOLATED | rotation登记backlog supplier为null，指标无法报告Journey到期与隔离积压；due OR查询需真实计划 |
| 恢复 | journey.control retry/cancel + scan retry | Commands+RecoveryAudit | 管理员与tenant服务边界 | 原节点retry；不replay | 无实例pause，首期优先定义pause/resume；是否扩展实例pause须有产品需要 |
| 安全 | Actor.requireAdmin、会员本人实例/通知；Mapper tenant | 认证与审计体系既有 | 401/403/404既有约定 | 取消/重试审计 | 不另造权限模型；新历史/预览必须同约束 |
| 运维/保留 | WorkLanes、BackgroundLaneMetrics、LaneMonitor；RetentionLane | 原事件/命令保留 | 固定lane标签；保留默认关闭 | 既有运维恢复 | 没有Journey专用step历史保留；先保留，不自行删除活跃/审计版本 |
| UI | Journeys.tsx、JourneyScans.tsx、实例管理页 | 真实API | 现有低代码节点表单 | 原管理控制 | 不重设计；只验证受影响路径和关键交易回归 |

## 源码与现有测试

- `marketing-automation/src/main/java/com/lrj/commerce/journey/api/JourneyApi.java`
- `marketing-automation/src/main/java/com/lrj/commerce/journey/application/JourneyService.java`
- `marketing-automation/src/main/resources/mappers/journey/JourneyMapper.xml`
- `commerce-app/src/main/resources/db/migration/V14__persistent_journeys.sql`；V21、V32演进
- `commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/journey/JourneyController.java`
- `commerce-app/src/main/java/com/lrj/commerce/app/runtime/scheduling/EventWorker.java`
- `platform-runtime/src/main/java/com/lrj/commerce/runtime/work/{TenantRotation,WorkLanes,FailureClass,RetryPolicy}.java`
- `marketing-runtime/src/main/java/com/lrj/commerce/campaign/rule/api/{RuleNode,MemberRuleFacts}.java`
- `commerce-app/src/test/java/com/lrj/commerce/app/{PersistedCommerceTest,MemberJourneyEffectsTest,LifecycleJourneyTest}.java`

## 首个真实场景

现有 ORDER_PAID → WAIT → DECIDE（当前 memberLevel/可信事实）→ GRANT固定CREDIT或无权益END → END。资格来自现有RuleNode，权益来自EntitlementApi；不是虚构复购指标或新营销发放子系统。现有 paid/refund旅程、生命周期券旅程是产品证据。完整新组合、true/false、事实改变、v1/v2与逐步trace尚待Phase 8执行证明。
