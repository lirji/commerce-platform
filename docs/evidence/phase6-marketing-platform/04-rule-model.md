# P6.2 规则矩阵（现状与本次验证目标）

| 规则类型 | 输入事实 | 配置契约 | 输出 | 副作用 | 版本 | 测试目标 |
| --- | --- | --- | --- | --- | --- | --- |
| 可信字段比较 | 会员等级/标签、订单原金额、会员行为等 | `RuleNode.COMPARE`：字段白名单、TEXT/DECIMAL、受限操作符 | MATCH/NO_MATCH/UNKNOWN | 无 | 规则资产版本或活动内嵌版本 | 类型/未知字段拒绝、缺失事实、稳定结果 |
| ALL/ANY/NOT | 子条件结果 | `RuleNode` 深度≤8、节点≤128、单组合≤16；NOT 单子节点 | 三值结果 | 无 | 随所属规则版本 | 组合真值、未知不能被 NOT 误提升 |
| 活动金额门槛 | 报价原金额 CNY | `minimumSpend` 精确十进制 | `BELOW_MINIMUM/ELIGIBLE` | 无 | 活动版本 | 边界金额与明确金额口径 |
| 固定/百分比/阶梯优惠 | 合资格 SKU 原金额 | `discountAmount`、`percentageBps`、递增 Tier（≤8） | 分摊金额/选中活动 | 无 | 活动版本 | 精确分、封顶、顺序无关与第二变体验证 |
| SKU 包含/排除 | 可信已发布 SKU 与报价行 | `Pricing` 的去重允许/排除列表 | `OUTSIDE_PRODUCT_SCOPE` 或合资格行 | 无 | 活动版本 | 包含/排除及空集边界 |
| 有效期 | 显式评价 `Instant` | `validFrom` 含、`validTo` 不含 | `OUTSIDE_VALIDITY` | 无 | 活动版本 | 起止边界与时间固定 |
| 固定人群资格 | 可信会员 ID、快照版本、评价时间 | `MarketingAssets.Ref` 与有效期 | HIT/MISS/UNKNOWN | 查询快照，无写入 | 人群快照版本 | 跨租户、失效、缺失版本 |

预算与权益份数属于执行配额，不是纯规则引擎的隐藏副作用。当前受限 AST 已覆盖这些真实规则；未发现仓库内 `activity_dynamic_rules` 表或 DRL 运行路径，不引入 Drools。
