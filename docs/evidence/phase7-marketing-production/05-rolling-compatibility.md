# 真实旧/新二进制滚动兼容

- OLD：独立 worktree `df955f1`（Phase 5、Phase 6 之前）构建的 jar，Java 21，端口 8621。
- NEW：当前 Phase 7 jar，端口 8622。两节点连接相同的隔离 MySQL `commerce_test_20260923`，后台自动 worker 关闭，以管理 API 明确泵送。
- Schema：NEW 已应用 V42/V43；OLD 启动时 Flyway 成功验证 43 个已应用迁移，提示 schema 43 高于二进制最新 40，随后 HTTP 启动成功。NEW 同 schema 启动成功。

## 实际旧生产者 → 新消费者

由 OLD HTTP 完成会员/店铺/SKU/库存、CREDIT 定义、人群、活动审批/发布、报价、订单、沙箱付款事实和对账。NEW 连续泵送共享事件表（3、2、1、0），最终 `order_record=PAID`、`benefit_grant=AVAILABLE`、单条 `GRANT` 账本，相关 `payment.paid.v1`、`order.paid.v1`、`benefit.grant.requested.v1`、`benefit.grant.available.v1` 均为 DELIVERED。旧订单本无 V41 营销执行行，NEW 投影消费者对它安全返回，执行行仍为 0。

## 新事件 → 旧消费者

用符合新 producer 载荷的 `benefit.grant.available.v1` 事件放入共享 Outbox。OLD 手动泵送返回 0，事件仍 PENDING、attempts 0；NEW 泵送返回 1，变 DELIVERED，唯一 Inbox 消费者为 `marketing-execution-projection-v1`。无旧节点毒化或静默 SKIPPED。独立合成事件只验证调度/消费者兼容；真实旧订单链见上一节。

## 兼容边界

旧二进制虽忽略新 `coupon` 字段而不报解析错误，却不能履行赠券承诺，业务上不兼容。`COMMERCE_MARKETING_COUPON_ENABLED` 默认 false；新代码在创建与发布时拒绝券活动，直到旧节点全退。旧版若回滚，必须先暂停所有券活动、停止新券流量、等待未消费的新报价/订单与预留券处理或安全取消；不能仅回滚 jar 或 Flyway。旧节点在 V43 schema 上的 CREDIT/旧活动路径已实际运行。

## 新功能激活后的二进制回退探针

在同一个 benchmark V43 库启动实际 OLD `df955f1`（8625）和最终 NEW jar（8623）。针对真实券活动与名单内用户，两者均 HTTP 200 并选中活动，但 OLD 的 `promotion.coupon` 缺失，NEW 有该固定承诺；OLD 活动列表也会移除 coupon 字段。此证据纠正“旧节点会解析拒绝”的假设：实际是忽略未知字段。

结论：混合期必须禁止新 coupon 配置生产，全部旧节点退出后才能开启开关。新功能开启后的回退目标仍需券能力，不能将老代码容忍 V43 schema 当成容忍新业务。旧节点对原 CREDIT 的留存 schema 回退已认证；开启券后的 OLD 全量接流量未认证，默认禁止。

## 持久化 Trace 的真实 enum 反例

收尾读取 NEW 多候选报价时，OLD 返回 HTTP 500；原因是落选值 OUTRANKED_BEST_OF 不在 OLD enum 中。已新增默认关闭的独立 extended-trace 门禁：混合期只在报价快照使用原 ELIGIBLE，赢家/金额不变，全部 OLD 退出后才生产新解释码。旧 enum JSON 往返回归还验证直接写新值会失败，最终 jar 默认门禁关闭时，真实 API 创建两个合格活动，NEW 报价由 OLD 读取、OLD 下单及取消均 HTTP 200，赢家和金额相同（`results/rolling-quote-final.md`）。开启后的回退目标仍必须支持新 enum，不能通过等 TTL 抹掉历史命令/报价。
