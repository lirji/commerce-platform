# S7 履约、部分退货与退款

在已授权单店实物/CNY范围内实现，真实WMS/退款渠道联调后置。复用有界MySQL事务任务与沙箱端口，不新增中间件。

- order.paid.v1/order.ready.v1驱动唯一tenant/order履约单READY；重复事件不重建。GET /v1/orders/{id}/fulfillment仅本人；管理列表独立/admin/fulfillments。
- POST /v1/admin/fulfillments/{orderId}/ship {trackingNo}和/deliver为明确隔离WMS事实入口，默认沙箱开关关闭时不可用，管理员幂等命令与审计。READY→SHIPPED→DELIVERED同步推进订单PAID→FULFILLING→COMPLETED。正式WmsPort预留，未配置不得伪造WMS成功。
- POST /v1/aftersales {orderId,reason,items:[{skuId,quantity}]}申请；GET列表/详情本人。仅PAID/FULFILLING/COMPLETED，单订单最多一个活动申请；完成后可对剩余数量再次申请。已发货允许部分退货；未发货仅接受整单退款并阻止发货。
- SKU数量按订单快照，累计已退+本次≤原数量。退款金额按固化行应付金额计算：floor(linePayableCents*(priorReturned+quantity)/originalQuantity)-floor(linePayableCents*priorReturned/originalQuantity)，逐行求和，最终退完恰好等于原实收，不重算现行活动。
- /v1/admin/aftersales/{id}/approve、/reject、/receive-return均需幂等键与管理员；驳回释放申请和履约阻拦。未发货批准直接请求退款；已发货批准WAIT_RETURN，管理员确认收到退货后才能请求退款与库存回补。
- 退货入库与退款分开：有可信收货事实可归还库存，退款未知仍保留REFUNDING不能显示完成。原库存CONFIRMED记录保留，inventory_return唯一tenant/case/sku防重复回补，累计归还不得超过原确认数。
- 支付退款唯一caseId，锁支付聚合校验累计退款请求≤实收；退款UNKNOWN状态持久化，渠道证据匹配原支付/退款/金额/CNY。后台有界核对、原请求幂等恢复，最多5次未知后人工继续。零元退款直接成功并明确NO_PAYMENT_REQUIRED，无伪造银行流水。
- GET /v1/admin/refunds，POST /v1/admin/refunds/{id}/reconcile；POST /v1/admin/sandbox/refunds/{id}/success仅改变沙箱账本。渠道成功与refund.succeeded.v1同事务，Inbox消费才推进售后COMPLETED及后续权益补偿事件。
- 可观察恢复：未消费付款事件仍可通过订单可信PAID状态补建唯一履约行；禁止仅根据客户端发货/退款成功声明改订单。退款成功重复消费不重复回补库存或累计退款；证据不匹配不得完成。
- 权益/券冲正在S8定义政策后消费aftersales.completed.v1；S7先提供稳定事实，不伪造已完成尚未存在的权益逻辑。
