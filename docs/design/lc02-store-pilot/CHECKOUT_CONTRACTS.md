# LC-02 试点报价来源契约

S3 依次交付商城报价证据、唯一交易引用、营销签价与应付冻结、原生订单关联以及支付前资金预留。操作范围见[试点方案](PILOT_OPERATIONS.md)。

## 商城报价证据

`GET /internal/v1/checkout-pilot/quotes/{quoteId}` 接收服务端持久化的商城报价 UUID。机器凭据独立配置，`Authorization` 和 `X-Trade-Tenant` 各只接受一个值，交易租户须匹配授权配对。商城租户、商户、门店、参与 SKU 与范围版本取自配置。仅服务端持有该凭据。

商城按同一只读视图核对原报价值、启用门店、启用商户及当前 WEB 商品修订。要求 CNY、参与 SKU 及未应用其他优惠。其他商品可以随符合条件的报价返回，以便交易核验完整购物篮。

- 成功返回 `scopeVersion`、`tradeTenantId`、`commerceTenantId`、`merchantId`、`storeId`、`storeVersion`、`eligibleSkuId`、`commerceQuoteId`、`ownerRef`、`currency`、`expiresAt` 及 `items`。响应设置 `Cache-Control: no-store`。
- `items` 的每条记录含 `skuId`、`skuRevision`、`channelPriceVersion`、`quantity` 及 `unitPriceMinor`。金额使用十进制分值字符串，过期时间使用 UTC。
- `ownerRef` 是商城以 JsonCodec 对固定数组 `["commerce-checkout-owner-v1", commerceTenantId, memberId, quoteId]` 序列化并计算的 SHA-256 小写十六进制摘要。原始会员 ID 留在商城。结算入口只能使用从已认证买家原报价得到的主体引用。交易凭据和此摘要都不授予对其他商城租户的读取能力。
- 无效授权返回 403，格式错误返回 400，缺失或不在范围返回 404，冻结、价格修订、失效或优惠冲突按既有错误语义拒绝。

## 交易报价引用准备

`POST /internal/v1/checkout-pilot/quote-references` 接收 `commerceQuoteId` 和当前结算的 `ownerRef`。交易验证 JWT 的既有签发方、接收方和时间，并检查 `trade.checkout-pilot.prepare`、固定交易租户和已配对的机器主体。首次请求在本地写事务外读取商城，逐项验证门店和商品证据，再持久化与该商城报价唯一对应的 `tradeQuoteRef`。网络超时相同请求可重试。

成功返回 `tradeQuoteRef`、`commerceQuoteId`、`scopeVersion`、`storeId`、`sourceDigest` 及 `expiresAt`。同一商城报价、范围和主体在有效期内获得同一回执。主体不同或过期返回 409，商城依赖不可用返回可重试的 503。

## 营销签价与交易冻结

通路依次调用营销 `POST /api/v1/decisions/checkout-pilot:evaluate` 和交易 `POST /internal/v1/checkout-pilot/quote-references/{reference}:freeze`。营销需要认证机器、`decision:evaluate` 和 `decision:checkout-pilot`。将 `tradeQuoteRef` 同时用作请求编号和签名参与编号，命令键为 `checkout:` 加该引用。商城主体引用填入 `subjectToken`，`checkout` 携带原交易引用、摘要、交易租户、范围版本、参与 SKU、出资方及有效期。购物篮中的行键等于商城 SKU。低于门槛时返回标准金额，响应省略 `checkoutQuoteToken`。

交易在固定机器授权及 `trade.checkout-pilot.freeze` 作用域下，验签并重新向商城核对报价来源、商品与门店版本。首份有效签价持久化后可按原签名重试。同一个引用提交不同签名会返回 409。创建原生订单及其支付许可仍需后续关联和预留。较详细的交易配置、协议和回执字段见[交易报价契约](https://github.com/lirji/transaction-center/blob/6049254c38392d013a21a8f7590c5535b1d93582/docs/CHECKOUT_PILOT.md)。

## 受控配置

商城使用 `COMMERCE_CHECKOUT_PILOT_*`。交易使用 `TRADE_CHECKOUT_PILOT_*`。开关默认关闭，启用时两侧须指定同一个范围版本、商城租户、商户、店铺和 SKU。交易的 `TENANT_ID` 匹配商城的 `TRADE_TENANT`，交易的 `COMMERCE_TOKEN` 对应商城的 `BEARER_TOKEN`。交易 `CALLER_KEY` 使用现有 `Actor.key()` 约定，由可信 issuer 和 subject 得到。交易到商城的地址使用 HTTPS，回环隔离验证可以使用 HTTP。两侧分别保存凭据并沿既有授权渠道轮换。

## 订单展示与查询编号

商城本地订单返回独立的 `businessNo`。新号形如 `COM-ORD-261126-0123456789`，`orderId` 继续用于原有生命周期与财务关联。订单列表和详情优先展示可读编号，详情支持复制，会员和后台可用原 ID 或可读编号查询。V75 建立唯一号索引及供尾号查询使用的业务日期索引。历史订单由交易仓库 `scripts/backfill-business-numbers.py` 的 `commerce-orders` 数据集分批按租户补录。

试点原生订单由交易中心发出 `TRD-ORD`。商城端接入原生试点下单后，应展示交易返回的 `businessNo` 和原生归属。营销资金凭交易原生证据读取同一编号。

商城支付、退款和售后新增 `COM-PAY`、`COM-RFD`、`COM-AFS` 展示号。V76 将它们与原支付、退款及售后主键分开保存，原渠道回调及售后关系按原字段核对。历史数据集分别为 `commerce-payments`、`commerce-refunds`、`commerce-aftersales`。

商城本地报价使用 `COM-QTE`，历史报价补号后可按可读号或原报价 ID 查询和消费。与试点营销签名关联的报价引用仍由原合约校验。中央导出任务使用 `COM-EXP`，详情、启动、推进和下载沿用申请主体与有效期校验。

商城券钱包和权益运营记录分别显示 `COM-CPN`、`COM-GRT`，权益流水使用 `COM-LED`。发放回执、订单预占和履约事件仍使用原券或权益的关联字段。历史券和权益创建时间缺失的记录继续用原号核对。
