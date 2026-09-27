# P6.10 运营检查与恢复手册

## 读取与关联

租户 `ADMIN` 在 `GET /v1/admin/marketing-executions?after=&limit=50` 查看有界参与列表，在 `GET /v1/admin/marketing-executions/{orderId}/{campaignId}` 查看固定活动、规则、人群、权益版本、报价 ID、规则原因、grant ID、评价时间和最终状态。详情还显示原权益状态以及 `benefit.grant.requested.v1` 的事件 ID、投递状态和失败分类；不返回错误全文或个人事实快照。接口在用例层检查 `MARKETING_EXECUTION_READ`，查询始终带凭据租户。

创建、提交、审批、发布、暂停/回退走现有 `Commands` 幂等及命令审计，记录身份、对象、动作和时间。运营恢复走原 `runtime/recoveries` 权限、审计和条件检查；营销只投影完成事件，不另建重试队列。规则 Trace 用固定原因码，正常 `MISS`/额度耗尽属于业务结果，不作为运行时故障告警。

## 故障处理

1. 先查执行详情。`GRANT_REQUESTED` 加 `REQUESTED` 表示尚未确认发放；`GRANT_FAILED` 且事件 `ISOLATED/SKIPPED` 表示原运行时已停止处理。绝不能仅凭活动规则 `ELIGIBLE` 判定权益已到帐。
2. 用事件 ID 在本租户运行时恢复查询核对失败分类、最近错误、重试预算及权益 `benefit_grant`/账本状态。只有原因可修复且安全门允许时，由有 `RUNTIME_RECOVERY_EXECUTE` 的管理员按原 API 执行 `RETRY`；不手工插入第二条 grant。
3. 恢复后查详情是否 `GRANTED`、权益是否 `AVAILABLE`，核对该 grant 只有一条 `GRANT` 账本。若支付状态仍不确定，先使用支付对账流程，不直接发权益。
4. 发布异常先暂停/回退未来活动候选并检查活动版本、规则/人群/权益引用。历史发放不会随配置回退自动逆转；需要退款/权益补偿时走其原状态机。

现有低基数运行时指标 `commerce.events.pending`、`commerce.events.retrying`、`commerce.events.quarantined` 与事件失败分类覆盖积压/隔离；本阶段未新增活动 ID/用户/租户标签。执行列表和详情承担逐笔诊断；专属营销聚合指标和告警阈值需结合真实流量设定，不能从本地夹具编造阈值。

## 查询路径检查

在隔离 MySQL 8.4 测试库执行 `EXPLAIN ANALYZE`：候选活动使用 `ix_campaign_store(tenant_id,store_id,status)`，单行夹具实际 0.0038–0.0046 ms；固定人群成员按 `(tenant_id,audience_id,version,member_id)` 主键点查，单行夹具 0.0053 ms。订单权益按租户和订单已有唯一键；营销详情按 `(tenant_id,order_id,campaign_id)` 主键。营销列表在当前单行租户夹具上优化器选 `uk_marketing_execution_grant` 并 filesort，实际 0.0245–0.0246 ms；接口限制页长，真实大租户部署前应以实际分布复核该计划和延迟。测试库共有约 1,960 条跨租户活动，单租户最多 2 条，这些微小样本时间不是 p95、吞吐或容量承诺。事实准备批量查询人群引用，纯规则器无逐节点数据库查询；候选超 100 拒绝。配额/发放路径由事务并发测试证明正确性，未建立独立延迟基准，列为性能认证限制。
