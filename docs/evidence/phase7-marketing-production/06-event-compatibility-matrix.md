# 事件与数据库兼容矩阵

| Producer | Consumer | 事件版本 | 兼容 | 行为与证据 |
| --- | --- | --- | --- | --- |
| OLD | OLD | `payment.paid.v1`、`benefit.grant.requested.v1` | 是 | Phase 5 原回归；本阶段未再造 OLD 单独泵送 |
| OLD | NEW | 同上及 `order.paid.v1` | 是 | OLD 真业务产出，NEW 泵送至订单 PAID、grant AVAILABLE、账本 1 条 |
| NEW | OLD | `benefit.grant.available.v1` | 有界兼容 | OLD 不订阅，保留 PENDING、0 次尝试；必须有 NEW 节点消费 |
| NEW | NEW | `benefit.grant.available.v1` | 是 | NEW 一次消费、Inbox 1 条；Phase 6 与本阶段回归 |

载荷契约：`benefit.grant.available.v1` 必需 `orderId`、`grantId`；项目 JsonMapper 对未知对象字段默认忽略；不是严格拒绝。旧 jar 读取含 `terms.coupon` 的实际配置返回 200，但输出和报价承诺均不含 coupon，证明字段兼容不等于业务能力兼容。未知 enum/规则类型仍拒绝，缺少必需字段仍由原校验处理；本轮不修改事件载荷。老版本 `benefit.grant.requested.v1` 仍按旧处理器解码，新增券提供者不复用该事件或冒充 CREDIT。未知事件类型由持久事件治理保持待处理并计入 `unrouted`，不依靠广义 catch 忽略解析失败。

| Binary | Schema | 兼容 | 证据 |
| --- | --- | --- | --- |
| OLD `df955f1` | Phase 5 V40 | 是 | Phase 5 已有回归 |
| OLD `df955f1` | V43 | 是（旧业务） | 真实 OLD jar + V43 启动，并完成 CREDIT 订单生产链 |
| NEW Phase 7 | V43 | 是 | 真实 NEW jar 启动、定向 MySQL 测试 |
| NEW Phase 7 | V40/V41 | 否 | 必须先执行 V42/V43，不能颠倒部署顺序 |

V42 仅添加候选索引；V43 扩展券额度列、来源 CHECK、预留表及可空 `benefit_type`。旧 CREDIT 写入 `benefit_type=NULL` 由新读取端推断为 CREDIT，未改写 V41 历史行。

持久化报价也属于跨版本契约：OUTRANKED_BEST_OF 是新 enum，不受“忽略未知对象字段”的容错保护。OLD 实际读新值 500，因此新解释生产有 extended-trace 默认 false 门禁；全部旧节点退出再打开。此限制与事件订阅兼容分别验证。
