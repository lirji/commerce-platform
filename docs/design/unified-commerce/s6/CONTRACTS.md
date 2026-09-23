# S6 支付与可靠事件契约

用户授权连续设计和实现，真实外部联调后置。本切片只交付明确标记SANDBOX的隔离支付和可替换PaymentChannel端口。运行配置默认禁用沙箱；本地env显式启用。正式渠道没有配置就503，不模拟正式收款。

- POST /v1/orders/{id}/payments：会员本人、幂等键；只允许未过期待付款单。锁订单→创建唯一tenant/order支付尝试→订单支付中；同订单不创建第二尝试。响应含paymentId/orderId/amount/currency/provider/status。UNKNOWN是待核实，不是失败或成功。
- GET /v1/orders/{id}/payment；POST /v1/orders/{id}/payment/reconcile：本人可查询/触发服务端核对，客户端不能提供支付金额或成功状态。reconcile先读可信渠道，网络IO不放业务事务。渠道证据必须匹配租户、订单、金额、币种；PAID/CLOSED终态不可反向覆盖。服务端渠道证据快照持久化，tenant/provider/channel_transaction_id唯一防止同笔收款认领两单。
- POST /v1/admin/sandbox/payments/{id}/fact {status:OPEN|PAID}：仅沙箱启用时管理员可操作，是真实数据库内的隔离渠道账本，不对外假称真实支付回调；OPEN才可原子关单，UNKNOWN关闭仍UNKNOWN；PAID/CLOSED互斥且终态不可修改。支付成功后取消交售后，取消中遇到PAID仍优先履行已收款事实。
- 支付业务PAID/CLOSED与payment.paid.v1/payment.closed.v1事件同事务；异步消费推进订单并确认/释放库存。重复eventId不重复效果；金额仍二次校验。零元单无渠道支付尝试。
- 支付自动核对每轮最多4租户×5条，按租户游标公平轮转；条件领取持久化next_check_at/次数，幂等渠道请求可在崩溃后重查。最多5次未知后保留人工核对，不擅自释放；手动reconcile始终可用。
- 数据库Outbox轮询用于本模块化单体，无新增MQ。每轮至多20条、每租户至多5条；事件行FOR UPDATE SKIP LOCKED，消费Inbox与副作用及投递标记同本地事务。处理器只允许本地数据库操作，不能在事务里远程调用。
- 失败整笔回滚后另事务记录尝试与退避，最多5次转ISOLATED；退避2^attempt秒加0..1秒抖动。进程崩溃回滚行锁，下次可重放。无处理器的事实保留PENDING，不伪造已消费。新增订阅者不追溯已完成事件，需显式迁移/重放。
- GET /v1/admin/events 提供当前租户状态列表；POST /v1/admin/events/{id}/retry 只允许隔离事件重放并审计。后台轮询可关闭用于确定性测试，管理员POST /v1/admin/events/pump触发当前租户有界投递。
- POST /v1/admin/orders/expire：本租户每批最多20个到期订单；未发起付款可以取消，支付中只能CLOSING。渠道未知不能释放库存。过期扫描不自动清除资金未知。
- 保留策略：开发阶段不自动清理资金/事件/幂等数据，不声称满足法律留存期；上线前需确定保留/归档策略。事件不含地址/令牌。指标通过有界状态计数和运维列表呈现。
- 验收：未知保留、终态证明/金额校验、取消与成功竞态、重复消费、消费事务失败恢复、重试隔离及租户隔离；真实渠道签名/证书/网络故障联调留待用户指定目标。
