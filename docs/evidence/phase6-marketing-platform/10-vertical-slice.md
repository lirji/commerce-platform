# P6.7 真实纵向切片

配置：真实店铺 SKU、固定会员人群快照、订单金额规则、活动 v1、预算和内部 CREDIT v1 → 提交/审批/发布 → 报价选中并冻结 Trace、版本及权益引用 → 下单事务预留库存/预算/CREDIT，写一条 `marketing_execution` → 支付事务将订单与执行置于受理态 → 现有 Outbox/Inbox 发放 CREDIT、写权益账本，并同事务发出 `benefit.grant.available.v1` → 营销投影条件更新为 `GRANTED` → 管理接口查询固定版本、grant 与事件结果。

`PersistedCommerceTest.marketingExecutionTracksRealOrderGrantAndTenantBoundary` 是金牌用例，检查 `RESERVED → GRANT_REQUESTED → GRANTED`、最终 `AVAILABLE`、仅一条 GRANT 账本、租户边界、重复订单命令和执行列表。`marketingExecutionKeepsVersionsAcrossPublishAndRollback` 验证 v1/v2/回退 v1 分别写入原版本与金额。`marketingExecutionShowsIsolatedGrantAndUsesExistingRecovery` 验证隔离时详情呈 `GRANT_FAILED`，通过原运行时恢复后回到 `GRANTED`，没有额外授予。

失败状态说明：`GRANT_FAILED` 是详情把持久 `GRANT_REQUESTED` 与原运行时 `ISOLATED/SKIPPED` 合并得到的诊断结果；执行表不复制运行时失败权威。列表为有界租户审计页，详情才读取 grant/事件，避免每行 N+1。纯优惠活动支付后为 `APPLIED`，无权益时不伪造 `GRANTED`。
