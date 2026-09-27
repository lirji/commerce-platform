# P6.6 人群模型

- 当前活动绑定 `marketing_audience_snapshot` 的 `(tenant_id,audience_id,version)`。名单来自受控导入或动态 segment 刷新后的**固定快照**；一个活动不会自动切到最新快照。当前名单上限 500，未做全量 CDP。
- `MarketingAssets.sources` 按租户、会员、引用和显式评价时间批量查询。命中为 `HIT`，不在名单为 `MISS`，过期/水位不可用为 `UNKNOWN`；后两者都不能通过活动规则内部 `NOT` 变合格。
- 创建和每次发布/回退都会检查引用属于本租户且仍新鲜。活动有效期与人群快照期限是两种独立约束：运行时过期人群使报价不合格，已产生订单的历史执行版本保持固定。
- 证据：`PersistedCommerceTest.governedCampaignRequiresApprovalAndBindsAudienceSource`、`staleAudienceIsUnknownAndCannotBeRescuedByNot`、`audienceMissAndCrossTenantReferenceFailClosed`、`publicationRechecksAudienceAndRejectsDuplicateRuleBranches`。未将巨量名单物化或引入新中间件。
