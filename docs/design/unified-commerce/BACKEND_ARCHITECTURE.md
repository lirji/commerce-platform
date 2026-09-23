# 后端架构

Owner：backend-architecture-design。用户约束：DDD + 模块化单体。应用目标一个业务进程；外部 IdP、支付渠道、WMS 等按适配边界保留。S0–S3建立领域库；S4–S6已提供单一Spring Boot HTTP进程、MySQL持久化、沙箱支付与本地事件Worker。未实现模块仍按下述设计逐片建设。

## 逻辑模块及数据权威

| 模块 | 聚合/权威数据 | 对外协作 |
|---|---|---|
| member | Member、主体绑定、会员状态 | MemberEligibility API / MemberRegistered |
| merchant | Merchant、准入、收款主体绑定 | MerchantAvailability API |
| store | Store、商家归属、营业与履约范围 | StoreAvailability API |
| catalog | Product、SKU、发布版本 | CatalogSnapshot API |
| inventory | Reservation、可售量/额度 | Reserve/Confirm/Release API；仓内库存归 WMS |
| marketing | CampaignVersion、AudienceSnapshot、RuleSet、Offer、JourneyDefinition/Instance | Decision API、Published/Qualified；内部按活动/人群/规则/优惠/旅程/低代码分子包 |
| benefit | Entitlement、GrantOrder、WalletEntry | Hold/Commit/Release/Compensate API；内部台账单一权威 |
| trade | Cart、Quote、价格/优惠分摊、交易组 | Quote API；编排只消费其他模块 api |
| order | Order、OrderLine、地址快照、合法生命周期 | Place/Cancel/Confirm API；OrderPaid/Cancelled/Completed |
| payment | PaymentAttempt、ChannelEvidence、RefundAttempt | 请求渠道适配，输出可信支付/退款事实 |
| fulfillment | FulfillmentOrder、物流或服务履约进度 | 请求 WMS/物流；Delivered/Failed |
| aftersales | AfterSaleCase、退货、退款审批、补偿计划 | 编排支付退款、入库及权益冲正，不能直接改其表 |

shared-kernel 仅 Money、稳定标识校验等无业务所有权的值类型。禁止发展成通用 Service/Repository 仓库。每个业务模块内部按需要使用 api/domain/application/infrastructure，不为简单模型制造空接口。

依赖：装配层 → 各模块 application → 本域 domain；跨域只引用 api 与不可变值。trade 编排 member/merchant/store/catalog/inventory/marketing/benefit/order 的端口，order 不反向调用 trade 实现。payment 与 order 的协作由应用装配器连接，不互引 Mapper。所有持久化 Entity 和 SQL 留在本域。Maven 模块和架构测试约束依赖；所有领域不是一开始就创建空模块。

## 一致性和恢复

物理库可共用 MySQL，表按域前缀、单写 Owner、各域 Mapper 独占。下单用例协调报价消费、订单创建、库存/权益预占，允许同库本地事务跨端口；这是明确的事务耦合，未来拆进程须重新设计，不能把端口换 HTTP 即声称等价。任何远程支付、发券、WMS 调用在该事务外进行。

支付成功/订单变更与 Outbox 同事务落库。发布至少一次；消费者用 tenant+consumer+eventId 去重并同事务完成业务效果，聚合版本防旧消息覆盖。一个单体内可先用数据库持久任务投递，不默认增加 Broker；连接既有平台时才接已有消息系统。任务有限批次、退避、截止时间、隔离、人工重放及租约防旧执行器写入。不能用内存事件监听器证明重启可恢复。

支付超时进入未知/待查，不能当失败退款或释放库存；取消已发起支付的订单进入 CLOSING，待可信未收款事实或资金处理。退款/权益冲正是新业务效果，不通过订单状态回退删除历史。报价是不可变快照，绑定 SKU/活动/规则/人群版本；可接受陈旧窗口由报价 TTL 和下单二次校验共同定义。

## 安全、边界和演进

租户、商家、店铺、主体由可信授权上下文及主数据映射获取，不能信任前端 tenantId 或人群事实。低代码只操作允许的字段/节点/操作符，配置发布要 schema 校验、版本、预览、审批/审计、灰度和可回退版本。规则不访问数据库/网络，不执行任意脚本，不承担授权决策。

日志关联 requestId/orderId/eventId，避免敏感地址及高基数指标；追踪渠道未知结果、预占超时、Outbox 延迟、权益悬挂和补偿积压。生产容量和恢复指标待真实测量。新增数据迁移先扩展后收缩；源仓迁移必须独立兼容评估，不能在新项目内悄悄替换旧路径。

Future Service Map：各逻辑边界当前均 KEEP_AS_MODULE；支付外联/规则计算/旅程运行若出现实测资源竞争、独立发布或隔离需求，再评估抽取。拆分触发需要实际负载、团队/运维责任、失败隔离收益和成本证据，当前没有拆服务决定。

边界就绪：领域/数据/依赖/事务/未来映射为 DESIGNED；尚未建设模块的契约、持久化隔离、运行观测为 UNKNOWN。本文件是总体方案，不是全平台 BOUNDARY_READY 或生产就绪证明。首批内核有精确 CONTRACTS，可单独实施验证。
