# P6.2 领域模型（已批准并实现）

**Activity** 对应现有 `marketing_campaign`。`(tenant,campaignId,version)` 是不可变业务内容身份；`status`/`lockVersion` 是运营激活控制。受治理版本通过 `policy_json` 固定引用规则、人群和 CREDIT 定义，旧无 policy 活动继续按原语义执行。

**Rule** 对应现有 `marketing_rule_asset` 的 `(tenant,ruleId,version)`，JSON 解码为有界 `RuleNode` 再转为纯 `Condition`。直接内嵌规则由活动版本承载；不虚构独立规则版本。

**Audience** 对应 `marketing_audience_snapshot/member` 的固定成员集与新鲜度窗口。动态 segment 可以生成下一版快照，但一个已发布活动始终引用特定版本；执行时按显式时间判断 HIT/MISS/UNKNOWN。

**Benefit definition** 对应内部 `benefit_definition` 的 `(tenant,benefitId,version)` 与配额。`benefit_grant` 是实际授予状态和账本，不是可变定义。首片只使用一个内部 CREDIT，不定义礼包原子性。

**Execution** 是新参与记录，身份为 `(tenant,orderId,campaignId)`。它保存选中活动/规则/人群/权益固定版本、有限理由与来源引用，以及订单预留、支付受理、发放可用或释放等结果。额度仍由 `benefit_definition` 与 `benefit_grant` 拥有，执行记录只用于审计与查询。

## 状态与事务边界

```text
活动：DRAFT → IN_REVIEW → APPROVED → PUBLISHED → PAUSED
            ↘ REJECTED       ↑           ↘ 旧版可再发布

执行：订单事务 RESERVED → 支付事务 GRANT_REQUESTED → 事件事务 GRANTED
                    ↘ 取消事务 RELEASED
     无权益活动支付后 APPLIED；隔离失败在详情中派生 GRANT_FAILED
```

失败或隔离的发放事件保留在现有运行时权威记录中；执行详情展示 `GRANT_FAILED`、失败分类与事件引用，恢复后再收敛为 `GRANTED`。`GRANT_FAILED` 不写入执行表，防止出现第二套失败权威。真实数据库用例验证该读模型与事务耦合，不把事件投递成功当作权益已可用。

## 不变量

- 同租户同订单同活动至多一条执行；同活动至多一个当前发布版本。数据库唯一键与条件更新是最终边界。
- 已发布业务版本及固定引用不原位修改；历史执行不读取当前草稿决定其规则。
- 纯决策只接收可信事实和显式评价时间，结果有有界原因与版本；不写权益或订单。
- 额度预留和参与插入与订单创建同事务；发放结果由已有权益状态和事件证据收敛，不能把规则命中写成已授予。
- 配置回退只改变未来候选版本，不自动逆转已授予 CREDIT。
