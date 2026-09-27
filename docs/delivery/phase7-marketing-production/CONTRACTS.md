# Phase 7 增量契约

既有 S8/Phase 6 契约保持历史版本；本文件只描述当前增量。

## 活动权益

`policy.terms.grant` 保留 CREDIT `{benefitId,version}`。新增可选 `policy.terms.coupon={definitionId,version}`，两者互斥；没有 coupon 时序列化不输出新字段，使 CREDIT 活动/报价在滚动窗口可由旧二进制读取。券引用必须为同租户同店 SOURCE_ONLY 定义、发行窗口覆盖活动窗口、`validityDays>0`。草稿和发布受 `COMMERCE_MARKETING_COUPON_ENABLED` 门禁；默认关闭，OLD 全退后才启用。

`CampaignFundingApi.Commitment` 追加可选 coupon 引用并冻结到报价快照。下单重复校验资方/活动快照，再由券 API 预留。新 coupon 报价不得由旧二进制消费：OLD 会忽略 coupon 而丢失承诺。新功能开启后回退目标必须保留券能力；跨激活点回退 OLD 还需隔离券活动及管理入口，不能只排空在途报价/订单。

## 竞争预览

原 `/v1/admin/campaigns/{id}/{version}/preview` 追加可选 `includePublishedCompetition`（缺失或 null 为 false）。true 时草稿替换同 ID 的当前发布版本，与同店当前有效活动一起调用生产决策器，候选仍至多 100；无副作用。返回追加 `selected={campaignId,version}`。Trace 追加 `OUTRANKED_BEST_OF`，表示合资格但优惠较低/平局落选；选中活动仍为 ELIGIBLE。纯优惠/零优惠原行为保持。

## 券订单来源与执行

V43 `benefit_campaign_coupon_hold` 主键 `(tenant,order)`；固定会员、定义版本和提前分配的券 ID。合法状态 HELD→ISSUED 或 HELD→RELEASED，终态重复调用无效果。额度由定义的 reserved+issued 与 CHECK 决定，来源钱包唯一键 `(tenant,CAMPAIGN,order)`。

`marketing_execution.benefit_type` 为 CREDIT/COUPON，可空以兼容旧写入；grant_id 非空的旧空类型解释为 CREDIT。查询 View 追加 benefitType。券的 GRANTED 与支付事务同步提交，grantStatus 显示 ISSUED；CREDIT 仍以原事件投影 GRANT_REQUESTED→GRANTED，未新增第二失败队列。

## 运维配置

`COMMERCE_MARKETING_PROFILING_ENABLED` 默认 false，仅在隔离测量或获授权诊断时开启；输出固定报价阶段的微秒耗时与候选数量，无业务 ID/个人数据。容量报告区分 SQL 微基准、HTTP 总耗时和生产承诺。

下单与支付结算性能日志复用上述 profiling 开关，分别记录额度、预算、库存、执行/订单落库和赠券发放时间；均不含业务标识。只代表成功方法段耗时，事务提交和失败请求尾延迟另以 HTTP/锁计数观察。

## 报价 Trace 的滚动门禁

`COMMERCE_MARKETING_EXTENDED_TRACE_ENABLED` 默认 false。纯决策与竞争预览仍能给出 OUTRANKED_BEST_OF，但滚动期报价持久快照/返回映射为旧 ELIGIBLE，selected/金额/命中事实不变；旧节点能读取和消费。全部 OLD 退出后才开启扩展解释码生产。若已写扩展报价，历史 quote 和幂等命令结果也含新枚举，单纯等 TTL 不足以回退 OLD，回退目标必须支持该枚举；不改写历史快照。
