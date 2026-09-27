# P6.1 当前营销能力清单（设计基线）

基于 `origin/main` 的 `df955f1`。本表只描述源码与迁移中可核对的能力，不把待建能力视为已完成。Phase 5 对订单、权益、券、积分和事件一致性的保证见其 `PHASE5_REPORT.md`。

| 能力 | 当前模型/入口 | 规则所在 | 状态与版本 | 主要扩展风险 |
| --- | --- | --- | --- | --- |
| 活动/优惠 | `CampaignApi`、`CampaignService`、`marketing_campaign`；`/v1/admin/campaigns` | 受限 `RuleNode`、金额/比例/阶梯与 SKU 范围 | 内容版本不可变，审批、发布、暂停；每活动单发布版本 | 无统一营销执行记录；报价择优与权益发放分散在交易和订单路径 |
| 活动预算 | `CampaignFundingApi`、`marketing_budget`、`marketing_budget_hold` | 条件额度更新 | 活动版本绑定，订单预占/确认/释放 | 只限制货币优惠预算，不能替代权益份数额度 |
| 可复用规则资产 | `MarketingAssets`、`marketing_rule_asset` | `RuleNode` 比较/ALL/ANY/NOT 转 `Condition` | DRAFT/PUBLISHED，固定版本引用 | 扩展事实字段须维护可信字段目录；没有规则脚本语言 |
| 人群快照 | `MarketingAssets`、`marketing_audience_snapshot/member` | 固定名单及新鲜度判断 | 不可变版本与有效期限 | 一次上限 500 人；不适合未经设计的大规模 CDP |
| 动态人群 | `SegmentApi`、`marketing_segment*` | `RuleNode` 与会员可信事实 | 定义版本、持久化刷新任务、发布为人群快照 | 与即时动态资格不同，使用时是固定快照 |
| 报价决策 | `MarketingDecisionService`、`QuoteService`、`trade_quote.snapshot_json` | 纯 Java 条件与金额计算 | 报价保存选中活动和 Trace，报价消费一次 | 未形成独立执行状态；历史报价已可回放 |
| 内部 CREDIT 权益 | `EntitlementApi`、`benefit_definition/grant/ledger` | 活动 policy 的固定引用；发放规则在权益服务 | 定义版本固定，额度条件更新；REQUESTED→AVAILABLE 事件消费 | 订单唯一一份授予，不能直接代表多权益礼包；异步结果未投影到营销执行 |
| 优惠券 | `CouponApi`、`benefit_coupon*` | 券门槛与叠加规则在报价/券服务 | 券定义版本、发行份数、占用/核销/返还 | 不属于本次活动 CREDIT 提供者，跨活动组合策略另定 |
| 积分 | `member_point*`、`PointsSpendApi` 与积分兑换 | 积分策略、抵扣与兑换规则 | 账本/批次/订单来源可追溯 | 财务样余额不能接入通用营销重放 |
| 旅程 | `JourneyApi`、`JourneyService`、`journey_definition/instance/effect` | 节点条件及触发频控 | 定义版本、等待/触达/恢复 | 长流程编排与同步活动决策必须保持边界 |
| 定向发券 | `CouponDeliveryApi`、`automation_coupon_*` | 固定人群、频次间隔 | 批次、收件人、运行控制 | 是独立任务发券，不应塞进订单活动执行器 |
| 经营效果 | `MarketingEffectsService`、`marketing_effect_order` | 非决策，成交/退款投影 | 订单幂等可重建投影 | 不是权益授予或资金权威 |
| CPS/购物返现、随单赠品、红包/现金等价物、兑换码 | 本仓库未发现对应活动定义/权威表/履约适配器 | 不适用 | 未实现 | 不纳入首个真实切片，不借“平台化”假设其已可用 |

## 规则位置与边界

| 规则族 | 当前事实/配置 | 位置 | 当前决策输出 |
| --- | --- | --- | --- |
| 会员/人群资格 | `memberLevel`、标签、画像等可信字段；固定人群引用 | `RuleNode`、`MarketingAssets`、`CampaignService.candidates` | `MATCH/NO_MATCH/UNKNOWN`，MISS/UNKNOWN 不被 NOT 反转 |
| 订单金额/门槛 | 可信报价原金额 `orderAmount`、`minimumSpend` | `MemberRuleFacts`、`MarketingDecisionService` | `BELOW_MINIMUM` 或 `ELIGIBLE` |
| 商品范围 | included/excluded SKU、阶梯 | `DecisionModels.Pricing` | `OUTSIDE_PRODUCT_SCOPE` 等 Trace |
| 优惠金额 | 固定额、万分比、阶梯、最高可减原金额 | `MarketingDecisionService` | 精确金额、逐行分摊、选中活动版本 |
| 时间 | 活动 `validFrom` 含、`validTo` 不含；显式评价 `Instant` | `MarketingDecisionService` | `OUTSIDE_VALIDITY` |
| 额度 | 营销预算与权益定义份数 | `CampaignFundingService`、`EntitlementService` 的数据库条件更新 | 预占/成功或冲突；不在纯规则器中写额度 |
| 频次 | 旅程、定向发券独立限制 | `JourneyService`、`CouponDeliveryService` | 各自流程结果；活动当前一次订单由报价消费和来源唯一性约束 |

现有配置既有 Java 条件、数据库 JSON，也有按业务用途分开的状态表；`RuleNode` 已是有界且可验证的 JSON→类型化条件适配器。没有证据支持把所有规则移入 Drools/DRL。旧参考仓规则迁移在原设计中仍为独立阻塞项。
