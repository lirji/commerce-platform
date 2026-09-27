# P6.8 并发、幂等与多实例

| 不变量 | 数据库边界 | 可执行证据 |
| --- | --- | --- |
| 同订单同活动只参与一次 | `marketing_execution` 主键及订单命令幂等 | `marketingExecutionTracksRealOrderGrantAndTenantBoundary` |
| 同订单只预留一次内部 CREDIT | `benefit_grant.uk_order_entitlement` | `entitlementIsReservedThenGrantedOnceAfterTrustedPayment` |
| 最后一份额度不能超发 | `benefit_definition` 条件更新和 `reserved+issued≤quota` CHECK | `lastEntitlementQuotaLetsOnlyOneConcurrentOrderReserve`，并核对仅一条营销执行 |
| 双实例竞争事件不重复授予 | 现有事件领取/Inbox、权益账本幂等、营销条件投影 | `twoAppInstancesCompleteOneMarketingGrant` 与 Phase 5 双实例证据 |
| 并发发布仅一个有效版本 | 同活动行锁、乐观版本及 `uk_campaign_active` | `concurrentCampaignPublicationKeepsOneEffectiveVersion` |
| 陈旧草稿/审批不能覆盖 | 内容新建版本、状态 `lock_version` | `governanceRejectAndOptimisticVersionCannotBeBypassed` |
| 发放恢复不重复账本 | 原事件隔离恢复、权益条件状态、Inbox | `marketingExecutionShowsIsolatedGrantAndUsesExistingRecovery` |

并发测试使用 `CountDownLatch` 同时释放请求，没有以睡眠制造竞争。营销执行不使用 JVM 锁。财务样补偿及礼包原子性不在本次单 CREDIT 保证内。
