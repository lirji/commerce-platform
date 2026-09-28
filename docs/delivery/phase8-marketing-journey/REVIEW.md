# Phase 8 有界代码审查

结论：PASS_WITH_LIMITATIONS；无未解决的正确性/隔离阻断。以最终源码和可执行结果复核，未使用并行Agent，未把自审表述为外部独立评审。

| 风险场景 | 源码与证据 | 结论 |
|---|---|---|
| 过期/无效固定引用通过审批后发放 | JourneyService.create/change均validateBindings；发布重检真实测试 | PASS |
| 两执行器重复动作/推进 | dueLock FOR UPDATE SKIP LOCKED、stable action source、effect唯一、advance影响行数；双JVM | PASS |
| 效果写入后crash造成半提交 | action/stepFinish/advance同TransactionTemplate；Error与真实kill回滚 | PASS |
| 回滚后旧错误污染另worker新节点 | failure事务current.version等于attempted.version；竞争测试及mutation | PASS |
| WAIT重启重新计时/提前发放 | 已持久nextNode/due_at，dueLock资格；真实25秒重启/负向变异 | PASS |
| 发布新版移动旧实例 | fixed journey_version，无内容update；v1/v2/pause/OLD-NEW | PASS |
| trace分页与检查点不同快照误报 | history只读REPEATABLE_READ短事务，tenant/本人检查，游标transition_version | PASS |
| 将受理权益误说已履约 | BENEFIT_ACCEPTED/actionRef，AVAILABLE由既有消费者；真实ledger1 | PASS |
| SQL安全/最终完整性 | Mapper绑定参数；V44 PK/FK/CHECK及所有注释；V45扩展索引 | PASS |
| 大未来WAIT拖累到期查询 | 分状态/时间LIMIT5 UNION、全局索引；50k/完整EXPLAIN | PASS |
| 热点挤占其他tenant/车道 | 原TenantRotation/WorkLanes；hot5000/normal5、真实payment/event | PASS（本地样本） |
| 敏感历史/越权恢复 | Step只标识/结果/分类，精确history允许列表，admin恢复与RecoveryAudit | PASS |

残余范围：Backlog聚合按active量计费；500ms为协作预算不是强抢占；多次DEFERRED虽受deadline仍增长历史；未批准Journey保留期；OLD执行只PARTIAL/LEGACY_PARTIAL；历史不精确重构完整事实。上述限制没有被掩盖为生产SLO或全链exactly-once保证，见报告和运维手册。

未顺手改旧模块、依赖、调度器、生产配置或用户数据；没有生产故障开关和通用脚本/Webhook执行。
