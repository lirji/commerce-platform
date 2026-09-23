# LP08 设计推演记录

本记录保留实施前推演。最终决策及已实现接口以 LP08_CONTRACT.md 为准；成长RR修复、当前偏好校验、生命周期扫描、券节点和两类效果比较均已落实。

## 可复用事实

- JourneyService已有DAG WAIT/DECIDE/GRANT/NOTIFY/END、定义审批版本、实例检查点、会员事件触发与频控。MemberGrowthApi.Facts已包含behavior，生日/30天浏览加购/完成订单/净消费/最近完成下单/入会天数/旅程偏好。
- MemberBehaviorApi只统计完成订单。加购未购买不能只看完成订单，必须经OrderApi增加同会员同店“加购后已有PAID/FULFILLING/COMPLETED订单”的可信查询，入组及触达前复核。
- marketing-automation同一模块拥有journey_effect与marketing_effect_order。可在明确只读边界下做同模块分析JOIN，避免跨服务写数据。marketing_effect_order目前缺member_id/coupon_id，须追加迁移并通过既有project/rebuild从OrderApi/QuoteApi填充，旧值为空要明确覆盖不足。
- 既有效果投影已含paid/refunded/discount/platformFunding/merchantFunding，订单键幂等重建；退款在窗口外也应继续冲减历史队列净收款。

## 生命周期建议

Definition追加可空Lifecycle配置和BIRTHDAY/DORMANT/REPURCHASE/CART_ABANDONED触发，保留旧构造/JSON/hash。配置含阈值天数或加购等待、扫描间隔、观察窗天数。必须使用已配置的entry/notification频控。

持久扫描表固定journey版本、会员创建截止/游标/nextDue/状态；每批最多100会员，事务内创建实例与写检查点。复用原pump/worker；小批租户公平性保持。暂停旅程只停止新入组，实例取消已有API。扫描失败退避、隔离/人工重试要有入口。

生日按UTC月日，2/29不在平年自动改2/28。沉睡用最近完成下单天数，暂无成交可按入会天数单独定义清楚；复购需有既往净消费。加购基于最新信号，且必须无信号后的已付订单。

去重键避免每轮扫描无限造实例：生日按年/会员；沉睡复购按会员、交易锚点和entryWindow固定窗口；加购按最新加购锚点。所有自动入组和节点副作用执行前检查journeyEnabled；关闭后既有等待实例后续触达/发权益应取消或明确抑制。原GRANT无金额成本，不编造货品或渠道成本。

## 效果比较建议

按journeyId/version展示独立会员入组数、触达、后续观察窗付费会员/订单、现金收款/成功退款/净收款、订单优惠承担；同一旅程重复入组会员以窗口内第一次入组为锚点去重。一笔订单可以同时出现在不同旅程比较行，明确不可跨行相加，不称因果提升/利润/真实ROI。

按定向券批次使用实际couponId匹配成交，显示发放/跳过/撤销/保留、使用订单与实际券优惠。使用QuoteApi.coupon.discount，不能把订单总优惠都记到定向券成本。权益货品成本未知则明确未计入。

## 必须复核的历史并发问题

GrowthMapper的source/refund/refunds以及写路径account仍用RR普通读；等待会员锁前已创建旧快照时，两笔不同退款可能读取旧贡献。LP04只修了points。LP06行为净额依赖成长来源，需要为本次效果可信性补一个窄修复：写路径accountCurrent+source/refund/refunds当前锁读，读接口保留普通读；用CyclicBarrier建立旧RR快照并发两次20/30退款验证100→50，并同时验证行为净额50。避免全仓库重构。
