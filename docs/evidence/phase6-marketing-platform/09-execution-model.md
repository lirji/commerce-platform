# P6.2/P6.7 活动执行矩阵（已实现）

| 阶段 | 输入 | 输出 | 事务 | 失败模型 | 证据目标 |
| --- | --- | --- | --- | --- | --- |
| Match | 租户、店铺、评价时间 | 至多 100 个已发布候选 | 报价只读 | 候选超界拒绝 | `CampaignMapper.published`、`MarketingDecisionTest` |
| Audience | 会员 ID、固定快照引用 | HIT/MISS/UNKNOWN | 报价只读 | 失效/缺失关闭资格 | `MarketingAssets.sources`、租户测试 |
| Rule | 可信会员/订单事实、规则固定版本 | Trace 与合资格结论 | 纯计算 | 未知节点或事实关闭 | `RuleEvaluator`、稳定性测试 |
| Benefit decision | 合资格行、活动条款、权益引用 | 精确金额、唯一选中活动、待授予 CREDIT 引用 | 纯计算/报价快照写入 | 预算与权益引用在发布时拒绝无效 | `MarketingDecisionService`、`QuoteService` |
| Grant | 已消费报价、订单 ID、选中版本 | 预算与 CREDIT 预留，再转 REQUESTED/AVAILABLE | 订单事务、支付事务、权益事件事务 | 额度竞争回滚订单；异步失败进入已有隔离恢复 | `OrderService`、`EntitlementService`、真实 MySQL 并发测试 |
| Record | 订单/活动参与与权益状态 | 固定版本、状态、原因、失败引用 | 与订单/事件各阶段同事务或幂等投影 | 重投递不重复行，不把待受理当成功 | `marketing_execution`、Inbox、管理查询及 `marketingExecutionShowsIsolatedGrantAndUsesExistingRecovery` |

查询/运营可以把执行记录与现有运行时隔离记录关联，但不能因读请求产生授予副作用。多实例安全由数据库唯一键、行锁和条件更新证明，不依赖进程内锁。
