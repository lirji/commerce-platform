# Phase 6 验收矩阵

| AC | 行为证据 | 判定 |
| --- | --- | --- |
| 01 发布完整性 | `publicationRechecksAudienceAndRejectsDuplicateRuleBranches`、`reusableRuleMustBePublishedAndCannotChangeFrozenCampaign`、`governanceRejectAndOptimisticVersionCannotBeBypassed` | 通过：过期人群/重复规则/未发布规则/跳审均拒绝 |
| 02 预览无副作用 | `draftPreviewUsesProductionDecisionWithoutParticipationOrQuotaWrites` | 通过：同决策结果、执行/发放/额度均未写 |
| 03 单个有效版本 | `concurrentCampaignPublicationKeepsOneEffectiveVersion` 与数据库 `uk_campaign_active` | 通过：并发 200/409，只一版生效 |
| 04 可信决策 | `governedCampaignRequiresApprovalAndBindsAudienceSource`、`MarketingDecisionTest`、`PrecisePromotionTest` | 通过：Trace、来源、金额与稳定择优 |
| 05 原子参与与额度 | `marketingExecutionTracksRealOrderGrantAndTenantBoundary`、`lastEntitlementQuotaLetsOnlyOneConcurrentOrderReserve` | 通过：一次参与，最后一份额度 200/409 |
| 06 发放和恢复 | `marketingExecutionShowsIsolatedGrantAndUsesExistingRecovery`、`twoAppInstancesCompleteOneMarketingGrant` | 通过：故障可见，原恢复后只一条授予账本 |
| 07 历史版本 | `marketingExecutionKeepsVersionsAcrossPublishAndRollback` | 通过：执行 v1/v2/v1 与金额 1/2/1 不漂移 |
| 08 扩展 | `PrecisePromotionTest` 的固定/比例/阶梯、SKU 范围，复用同一报价/订单/权益链 | 通过：无第二套控制器或发放消费者 |
| 09 回归/打包 | 全量 `verify.sh` 与最终 `build.sh`、受影响 Playwright | 构建与浏览器结论见 `14-regression.md` |

故障模拟通过修改隔离测试租户的运行时事件状态并调用原恢复 API，不影响其他租户或生产数据。浏览器只覆盖现有受影响页面和订单旅程；新执行只提供后端管理 API。未授权的生产部署、远程 CI、Git 提交/推送均不属于本轮验收动作。
