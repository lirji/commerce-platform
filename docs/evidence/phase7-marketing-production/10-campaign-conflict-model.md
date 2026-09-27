# 活动冲突语义

当前产品只支持同一订单单个活动。Phase 6 的“最大实际优惠、活动 ID 升序破平局”显式命名为 `BEST_OF`，由纯 `CampaignConflictResolver` 实现；不增加未决定的 EXCLUSIVE 组、PRIORITY 或 STACKABLE 数学规则。候选从可信店铺/有效期过滤，规则与人群先确定合资格，再用实际优惠比较；没有达到正优惠的活动不参与选择。

| 场景 | 合资格活动 | 策略 | 结果 | 解释 | 确定性 |
| --- | --- | --- | --- | --- | --- |
| 单个 | A=3 | BEST_OF | A | ELIGIBLE | 是 |
| 清晰胜者 | A=3，B=5 | BEST_OF | B | A=`OUTRANKED_BEST_OF` | 是 |
| 平局 | A=3，B=3 | BEST_OF | ID 小者 | 落选者=`OUTRANKED_BEST_OF` | 是 |
| 独占/叠加 | 产品未定义 | 未支持 | 不接受新模式 | 不猜测计算顺序 | 不适用 |

输入反序、购物行反序的领域测试验证同一结果。竞争预览增加 `includePublishedCompetition`：将当前草稿替换同 ID 已发布版本，与其余当前有效活动一起调用**同一**决策器；默认旧单活动预览语义保持不变。预览结果带 `selected`，可与真实报价选中 ID 比较。历史执行仍保存原活动版本和报价，不重判旧订单。

滚动期解释兼容：纯决策/预览保留落选原因；实际 quote 默认将该新 enum 映射为旧 ELIGIBLE 以供 OLD 读取，selected 仍明确唯一赢家。只有 OLD 全退并开启 extended-trace 才在持久快照输出新解释码。此门禁不会改变 BEST_OF 或金额。
