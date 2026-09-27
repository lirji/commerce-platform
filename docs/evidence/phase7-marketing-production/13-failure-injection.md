# 故障注入与恢复边界

| 注入点 | 已有/本阶段证据 | 恢复语义 |
| --- | --- | --- |
| 报价决定后、订单预留前 | 现有命令事务及重复订单用例 | 无订单/权益效果，原报价可按 TTL 重试 |
| 券额度预留后、执行记录前 | 最后一份额度争用使整个失败订单事务回滚；30/90 批量最终只有 30 预留/执行 | 事务整体回滚，不留假授予 |
| 支付事实后、订单结算前 | Phase 5 支付事件重试/隔离回归 | 保留可信支付事实，订单状态不提前变 PAID |
| CREDIT grant 后、营销投影前 | Phase 6 隔离恢复与权威 grant 查询 | 原 Inbox/恢复路径重试，最终投影 GRANTED |
| 新事件到旧节点 | 真实 OLD/NEW 泵送测试 | OLD 不领，保留 PENDING；NEW 领取 |

券源发放在支付订单事务里；数据库或状态冲突将回滚订单迁移，由现有支付事件工作队列分类和重试。新 `campaignCouponGrantRollsBackWithTransactionAndThenRecovers` 在真实事务执行 `confirmCampaign`（额度转移、钱包写入、hold ISSUED）后主动抛异常；检查 reserved=1、issued=0、无钱包券、HELD 保留，然后正常支付并再次泵送，仅一张券到账。故障覆盖发券效果提交前的崩溃窗口；`twoNewInstancesIssueOneCampaignCoupon` 验证重复事件竞争仍唯一。已有 CREDIT 隔离/恢复和支付失败分类用例全量通过。未做操作系统 kill -9 或真实网络分区；不把事务回滚注入说成完整基础设施灾备演练。不可逆外部提供者仍未接入。
