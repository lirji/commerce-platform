# 运营风险与防误操作

等级采用 P0—P3。已复现的是入口、查询或显示行为。本轮静态风险评估和只读行为取证没有证明资损发生。P0 候选表示需在正式渠道、批量或授权组合中验证的严重后果。当前可复用的保护包括分离读写能力、原对象核权、命令幂等、锁版本、状态机、后台有界处理与投递/审计记录。

| 编号 | 风险与判断 | 当前证据及防线 | 拟处置与验收 | 优先级 |
| --- | --- | --- | --- | --- |
| R01 | **实测**。旧订单页展示店铺选择，订单列表请求未使用该字段。演示租户单店，未实测跨店误处置。 | `Orders` 列表依授权，壳层还有门店选择，后端保留租户和资源权限检查。 [壳层](../../../frontend/src/app/App.tsx#L670)、[订单查询](../../../frontend/src/features/Orders.tsx#L118)。 | 即时标明列表实际范围，新增结构化门店过滤前验证 Owner 支持，跨门店作业需要明确目标选择。用两个可见门店和一个不可见门店验收。 | P1 |
| R02 | **实测/源码确认**。中央组织入口拒绝，中央运营页面筛选显示已应用但服务端未收到。 | `/operations` 返回 401。页面筛选只传 `after` 和固定50。 [入口](../../../frontend/src/iam/navigation.ts#L6)、[读取](../../../frontend/src/iam/CentralOpsPages.tsx#L572)。 | 精确补页面壳映射与筛选契约接线。验收匿名业务 API 仍拒绝，权限撤回、筛选返回、页大小回归。 | P1 |
| R03 | **潜在**。数量、价格、门店及批量对象在不同区核对，单位错填或选择陈旧可能造成大范围变化。 | 规格修订与计划使用实际版本、原因、时间窗，历史报价保持原价。入库是有上限的正增量，核门店和商品，服务端行锁/鉴权。 [批量 UI](../../../frontend/src/features/CatalogScheduling.tsx#L71)、[库存服务](../../../inventory/src/main/java/com/lrj/commerce/inventory/application/InventoryService.java#L41)、[渠道价](../../../catalog/src/main/java/com/lrj/commerce/catalog/pricing/application/ChannelPriceService.java)。 | 给变更前后对照及实际目标数。异常数量阈值由业务确认，按 Owner 返回预计并区别真实回执。验收金额单位、时间、并发冲突、无权目标、部分完成。 | P1，批量正式环境 P0 候选 |
| R04 | **潜在**。活动发布时发布资格、人群新鲜度、资金占用和发行额度散于各处。发布或退款可能被误读为释放预算。 | 预算 Owner 管上限、预占和已消耗，活动有固定内容及锁版本，资格模拟为独立路径。 [活动发布](../../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/management/application/CampaignService.java#L170)、[预算 SQL](../../../marketing-runtime/src/main/resources/mappers/campaign/BudgetMapper.xml)、[模拟](../../../frontend/src/iam/CentralCampaigns.tsx#L417)。 | 同版预算摘要、代价及窗口、快照状态和预览。实际资金阈值、发布门槛和双人审批由业务确认。验收无效窗口、预算不足、临界金额、旧版或撤权时失败关闭。 | P1，真实资金 P0 候选 |
| R05 | **源码确认**。停发、撤券和逐人结果是不同语义，简化为单一成功容易错误处置已占用/已用/到期。 | 批次双方向、冷却、额度、受理、跳过及撤回保留，专门的作业推进与持久数据。 [批次状态 UI](../../../frontend/src/iam/CentralCouponDeliveries.tsx#L208)、[发券服务](../../../marketing-automation/src/main/java/com/lrj/commerce/journey/delivery/application/CouponDeliveryService.java)。 | 命令输入显示固定模板、人群、实际作用范围、上限和时效。回执按方向展示实际已发、保留、未处理和原因。重试相同意图，勿换批次替代重试。 | P1 |
| R06 | **潜在**。订单关闭、付款未知、退款未知、已退货的库存回补容易被理解为同一完成状态。真实渠道 P0 风险待验。 | 状态机、原交易支付和退款核对代码，审批未发货可启动原退款，已发货先待退货，收件负责原库存回补。 [订单状态](../../../order/src/main/java/com/lrj/commerce/order/api/OrderState.java)、[售后服务](../../../aftersales/src/main/java/com/lrj/commerce/aftersales/application/AftersaleService.java#L157)、[支付](../../../payment/src/main/java)。 | 四组事实分区，显示最后可信回执和核对时间。门店试点另验证冻结报价、支付金额和退款基准对齐，对“未知”只能核对原支付/退款。验收成功、迟到、重复、未知、拒绝和保留占用。 | P0 候选 |
| R07 | **潜在**。门店经营授权、跨店商品导出和中央作用域的审批、访问、下载错读能扩大暴露。 | 旧授权商家范围含未来新增门店。中央按真实资源复核，外部导出独立、限时、下载重新判权。 [旧授权](../../../frontend/src/features/AdminData.tsx#L61)、[范围控制器](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/store/CentralScopeController.java#L32)、[导出页面](../../../frontend/src/iam/ProductExport.tsx)。 | 确认中显示允许的门店及时间、当前筛选、实际受授主体和未来门店规则，受理不等同于可下载。验收查改导三者分离、撤销中途回收、下载与跨组织拒绝。 | P1，数据外泄 P0 候选 |
| R08 | **潜在**。历史重放和原任务恢复执行动作相似，可能被用来替代业务回滚，造成副作用重复。 | 原工作类型、权限、版本、状态，消费者安全分类及试运行，任务、控制和审计持久化。 [重放门控](../../../platform-runtime/src/main/java/com/lrj/commerce/runtime/replay/ReplayGate.java)、[运行恢复](../../../platform-runtime/src/main/java/com/lrj/commerce/runtime/recovery/RuntimeRecovery.java)、[运行界面](../../../frontend/src/features/RuntimeOperations.tsx#L566)。 | 分开原责任恢复、补齐未消费、允许的重执行，使用实际预检范围与逐项结果。负例包括新选择、历史缺来源、原授权失效、运行已恢复。 | P1 |
| R09 | **兼容风险**。营销执行存储允许订单和活动组成复合键，现有原生下单只记录单个选中活动。若后续一单关联多活动，当前游标可能漏读。 | SQL 用 `order_id > after` 并按订单、活动排序。现有创建路径从报价取单个活动并插入一条记录。 [Mapper](../../../marketing-runtime/src/main/resources/mappers/campaign/CampaignExecutionMapper.xml#L8)、[下单调用](../../../order-runtime/src/main/java/com/lrj/commerce/ordering/order/application/OrderService.java#L144)、[活动写入](../../../marketing-runtime/src/main/java/com/lrj/commerce/campaign/execution/application/CampaignExecutionService.java#L59)。 | 保持当前单活动契约并验证运行数据。只有引入多活动或发现真实历史多行时，再确定兼容复合游标，以边界页回归。 | P2 |
| R10 | **潜在**。权益处置、积分负数调整、会员冻结和账本操作对同名客户可能选择错对象。 | 策略和版本、独立命令权限与原因，精确输入原会员或权益号。 [中央积分](../../../frontend/src/iam/CentralPoints.tsx#L367)、[权益处置](../../../benefit/src/main/java/com/lrj/commerce/benefit/entitlement/application/EntitlementService.java#L223)。 | 双向校验目标和原范围、数量单位、变更前后和回执。权限撤回及并发版本冲突用实际库验收。保留独立写岗，不为预填扩张读权。 | P1 |
| R11 | **潜在**。效果延迟、未覆盖或聚合来源缺权可能被当作真实零。 | 总览说明 UTC、异步投影和非一致快照，中央仪表盘组合校验来源。 [仪表盘控制器](../../../commerce-app/src/main/java/com/lrj/commerce/app/http/operations/DashboardController.java#L50)、[效果报表](../../../frontend/src/features/MarketingEffects.tsx)。 | 解释“读取时间”“最后核对”“未进入覆盖”，异常指标要能追到同一权限与口径的明细。使用回填延迟、历史缺数据和权限失败验证。 | P1 |

## 监测、响应与审计

- **队列**　区分未到期、处理中、隔离、失败、未知、已补偿。一个告警应能定位同一租户范围内的原业务、责任人及合法后续动作。事件积压、重放和渠道核对各自关联 Owner。
- **审计**　预检、命令受理与最终状态使用不同事实。关键操作记录原对象、操作者、原因、预期和实际版本、受理时间、最终状态，以及既有约定的请求与业务追踪标识。操作记录的对外展示使用稳定状态和安全摘要，日志/导出执行字段白名单。
- **容量推演**　扩大筛选和批量后关注搜索扫描、单店库存锁、同活动预算争用、批次领取/撤回与交易同时运行、事件消费与重放竞争。预检和导出有固定目标与最大批，测实际执行计划、延迟和拒绝行为后才确定并行度及限制。在役数据与历史归档分别验证，检查高并发下未误报全部完成。
- **故障验收**　在获准隔离环境中验证渠道迟到、支付关闭结果未知、选店/权限切换中迟到响应、库存竞态、部分作业失败、投影延迟、重复回执和恢复重启。业务完成以 Owner 状态和数据库事实为准，不能从 UI 提示推断。

## 待业务或渠道确认

1. 价格涨跌比例、收货异常数量及预算告警阈值，哪些需双人复核。
2. 店长、商家、营销财务、独立审批/操作岗位实际权限组合和主菜单顺序。
3. 退款、退货合计、积分名义退还及真实回补的运营用语和告警归属。
4. 正式支付/WMS/外部权益联调计划、人工核对证据与故障升级路径。
5. 导出审批、脱敏字段、留存时限、审批窗口及高敏会员字段授权。
