# S5订单和库存原子闭环

S5a实现库存权威数量、报价一次性消费、订单落库、取消/零元单、地址加密、同事务事件。S5b权益占用将在S8券/权益正式契约实现时接入；当前报价只有不消耗权益的活动减免，不伪造券占用。本次将S6依赖细化为S5a，不宣称S5b完成。用户已授权按整体计划连续实施，外部联调后置。

- 商品均按实物件数处理；库存为本平台可售额度，WMS仓内数量仍待外部适配。ADMIN入库增加可售数，单次数量1..1000000，总available/held均≤1000000000。
- 库存键tenant/store/sku；预占键tenant/order/sku；RESERVED→CONFIRMED或RELEASED，终态重试不得重复扣减/释放。多SKU按ID稳定顺序占用，任一不足整单回滚。
- POST /v1/orders：Idempotency-Key + {quoteId,address:{recipient,phone,detail}}。会员只能消费本人未过期未消费报价；数据库FOR UPDATE后检查失效，原成功同键回放不重验报价TTL。不同键消费同报价409。
- 订单使用报价固化的SKU/价格/活动版本，不重算当下优惠；会员与店铺必须仍可交易。报价消费、库存预占、订单/明细、事件、命令和审计同一事务。
- 地址字段长度128/32/512；AES256-GCM，随机nonce、AAD=tenant/order；只存密文和keyVersion，普通订单API不返回地址明文。密钥由本地私密env提供，不写入Git；真实KMS以后适配。
- 订单PENDING_PAYMENT开始，15分钟到期；零元单可直接进入PAID且paymentKind=NO_PAYMENT_REQUIRED，确认库存但不生成已收款事实。
- GET /v1/orders/{id}、GET /v1/orders?after&limit：仅本人；管理查询另有/admin路径。POST /v1/orders/{id}/cancel：未发起支付可直接取消释放，支付中只能CLOSING保留预占。
- 持久化状态更新必须带旧version，影响行数非1拒绝；不存在/非本租户/非本人统一404。超时本身不能证明渠道未收款。
- 库存端点POST /v1/admin/inventory/receipts {storeId,skuId,quantity}；GET /v1/admin/inventory?storeId&after&limit。
- 事件是本地Outbox事实，S6实现投递/消费/恢复；当前不声称已经发送到外部。
- 验收：成功下单、同键并发单效果、不同订单争抢无超卖、跨SKU失败全部回滚、报价重复消费冲突、过期拒绝、取消仅释放一次、零元单无渠道收款、地址DB无明文、事件与命令原子。
