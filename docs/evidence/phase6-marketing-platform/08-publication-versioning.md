# P6.4 发布、版本与回退

- 受治理活动路径为 `DRAFT → IN_REVIEW → APPROVED → PUBLISHED → PAUSED`；驳回为 `REJECTED`。旧无 policy 活动保持兼容的直接发布路径。内容由 `(tenant,campaignId,version)` 固定，状态和 `lock_version` 只控制生效，不编辑历史业务内容。
- 创建时检查规则字段、活动基本参数、权益与人群引用；发布时在同活动版本行锁内再次验证活动有效期、规则 AST、规则资产固定版本、人群新鲜度、权益绑定、预算/价格参数及审批状态。数据库生成列唯一键保障一个活动最多一个 `PUBLISHED` 版本；陈旧 `expectedVersion` 显式冲突。
- 草稿预览使用相同 `MarketingDecisionService`，只读事实/候选，不生成报价、订单、参与或权益预留。预览时间可显式指定；历史 `at` 不回溯会员画像，这一点由接口消息明确说明。
- 回退是重新发布旧版本，仅改变**未来报价**的候选。已经消费的报价/订单、权益账本和 `marketing_execution` 的版本不被重算或自动撤销。
- 证据：`PersistedCommerceTest` 的 `governanceRejectAndOptimisticVersionCannotBeBypassed`、`concurrentCampaignPublicationKeepsOneEffectiveVersion`、`marketingExecutionKeepsVersionsAcrossPublishAndRollback`、`draftPreviewUsesProductionDecisionWithoutParticipationOrQuotaWrites`、`publicationRechecksAudienceAndRejectsDuplicateRuleBranches`。
