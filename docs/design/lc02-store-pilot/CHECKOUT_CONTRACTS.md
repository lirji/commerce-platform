# LC-02 试点报价来源契约

S3 分两段交付。第一段由商城验证已有报价，交易中心持久化报价引用。第二段消费该引用并锁定优惠金额、关联原生订单及接入资金预留。操作范围见[试点方案](PILOT_OPERATIONS.md)。

## 商城报价证据

`GET /internal/v1/checkout-pilot/quotes/{quoteId}` 接收服务端持久化的商城报价 UUID。机器凭据独立配置，`Authorization` 和 `X-Trade-Tenant` 各只接受一个值，交易租户须匹配授权配对。商城租户、商户、门店、参与 SKU 与范围版本取自配置。仅服务端持有该凭据。

商城按同一只读视图核对原报价值、启用门店、启用商户及当前 WEB 商品修订。要求 CNY、参与 SKU 及未应用其他优惠。其他商品可以随符合条件的报价返回，以便交易核验完整购物篮。

- 成功返回 `scopeVersion`、`tradeTenantId`、`commerceTenantId`、`merchantId`、`storeId`、`storeVersion`、`eligibleSkuId`、`commerceQuoteId`、`ownerRef`、`currency`、`expiresAt` 及 `items`。响应设置 `Cache-Control: no-store`。
- `items` 的每条记录含 `skuId`、`skuRevision`、`channelPriceVersion`、`quantity` 及 `unitPriceMinor`。金额使用十进制分值字符串，过期时间使用 UTC。
- `ownerRef` 是商城以 JsonCodec 对固定数组 `["commerce-checkout-owner-v1", commerceTenantId, memberId, quoteId]` 序列化并计算的 SHA-256 小写十六进制摘要。原始会员 ID 留在商城。结算入口只能使用从已认证买家原报价得到的主体引用。交易凭据和此摘要都不授予对其他商城租户的读取能力。
- 无效授权返回 403，格式错误返回 400，缺失或不在范围返回 404，冻结、价格修订、失效或优惠冲突按既有错误语义拒绝。

## 交易报价引用准备

`POST /internal/v1/checkout-pilot/quote-references` 接收 `commerceQuoteId` 和当前结算的 `ownerRef`。交易验证 JWT 的既有签发方、接收方和时间，并检查 `trade.checkout-pilot.prepare`、固定交易租户和已配对的机器主体。首次请求在本地写事务外读取商城，逐项验证门店和商品证据，再持久化与该商城报价唯一对应的 `tradeQuoteRef`。网络超时相同请求可重试。

成功返回 `tradeQuoteRef`、`commerceQuoteId`、`scopeVersion`、`storeId` 及 `expiresAt`。同一商城报价、范围和主体在有效期内获得同一回执。主体不同或过期返回 409，商城依赖不可用返回可重试的 503。交易接入正常报价前须复核最新范围和快照，与原生订单建立一次性关联，再通过后续资金预留门禁。

## 受控配置

商城使用 `COMMERCE_CHECKOUT_PILOT_*`。交易使用 `TRADE_CHECKOUT_PILOT_*`。开关默认关闭，启用时两侧须指定同一个范围版本、商城租户、商户、店铺和 SKU。交易的 `TENANT_ID` 匹配商城的 `TRADE_TENANT`，交易的 `COMMERCE_TOKEN` 对应商城的 `BEARER_TOKEN`。交易 `CALLER_KEY` 使用现有 `Actor.key()` 约定，由可信 issuer 和 subject 得到。交易到商城的地址使用 HTTPS，回环隔离验证可以使用 HTTP。两侧分别保存凭据并沿既有授权渠道轮换。
