# P6.3 规则评价

- 可信事实由 `CampaignService.candidates` 和 `MemberRuleFacts` 准备：租户/店铺、会员等级与行为、报价原金额、SKU 行及显式评价 `Instant`。活动特有的 SKU 范围、阶梯和固定人群引用保留在活动版本，不扩张为无界通用事实 DTO。
- `RuleNode` 解码为有界、类型化的 `Condition`；字段白名单、深度、节点数、子节点数及重复同级分支在配置入口拒绝。未知字段/节点关闭，缺失事实为 `UNKNOWN`；人群 `MISS/UNKNOWN` 在应用层先关闭资格，不能由内部 `NOT` 反转。
- `MarketingDecisionService` 对相同事实、活动版本和评价时间给出相同结果。输出是报价的选中活动、精确金额和有界 `Reason` Trace；授予由订单及权益服务执行。金额口径是报价前 SKU 原金额，人民币两位小数。
- 活动冲突沿用单选：优惠额最高，平局按活动 ID 稳定排序。其他叠加或专属优先级需要产品决定；本切片不改报价语义。
- 证据：`marketing/src/test/java/com/lrj/commerce/marketing/MarketingDecisionTest.java`、`PrecisePromotionTest.java`；`PersistedCommerceTest` 的 `staleAudienceIsUnknownAndCannotBeRescuedByNot`、`publicationRechecksAudienceAndRejectsDuplicateRuleBranches`、`draftPreviewUsesProductionDecisionWithoutParticipationOrQuotaWrites`。无规则引擎直接写订单/权益的依赖。
