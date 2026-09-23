# LP05 积分兑换契约

本片承接已确认的券/权益兑换。由 benefit 编排，member 只拥有积分账本，不依赖 benefit。沿用本地 Commands 事务、Outbox 权益受理与会员行锁，不新增基础设施。

## 对外接口

- POST `/v1/admin/point-offers`：Offer `{offerId,storeId,name,kind:COUPON|ENTITLEMENT,assetId,assetVersion,points,quota,perMemberLimit,validFrom,validTo}`；积分1–10亿，配额1–100万，每会员1–1000，窗口UTC且被引用定义发行窗口覆盖。创建后内容不可改，默认ACTIVE。
- POST `/v1/admin/point-offers/{id}/status`：`{expectedVersion,active,reason}`；停用只阻止新兑换，不撤销已发权益。状态变更有审计。
- GET `/v1/admin/point-offers?storeId=&after=&limit=`：含已停用；GET `/v1/point-offers` 同参数，仅有效窗口且ACTIVE。返回 `{content:Offer,status,issued,version}`。稳定offerId游标，limit1–100。
- POST `/v1/point-offers/{id}/redeem`：无业务请求体；认证会员、Idempotency-Key。返回 Receipt `{redemptionId,offerId,memberId,points,kind,assetId,createdAt}`，assetId为券钱包ID或权益grantId；权益受理后异步AVAILABLE，页面必须明确待发放。
- GET `/v1/point-redemptions?after=&limit=`：仅本人稳定redemptionId游标。不存在/跨租户404，越权403，停用/失效/不足/限额409，参数错误400。

## 数据与业务不变量

- 兑换仅成功一次扣费；幂等同键重试返回原回执，变更请求复用键冲突。领取不同兑换编号可重复兑换，受会员上限、活动配额、资产配额共同限制。
- 顺序：积分会员锁→兑换活动锁→会员累计→资产定义额度/发放。积分够才FIFO扣除前200有效批次；有欠项时可消费额扣除欠项，不因兑换新增欠项；碎片超200批次拒绝并回滚。扣分动作EXCHANGE，来源为兑换ID，member持久化独立exchange事实以便跨模块重试防重，不伪造订单冻结。
- 积分消费总开关spendEnabled同时控制兑换。资产不足时扣分/次数/券/权益受理/Outbox整体回滚；权益消费者失败保持REQUESTED及既有重试/隔离机制，运营通过权益钱包追踪。无外部权益调用。
- 本片成功兑换不支持主动退积分；过期或用掉的券/权益不可自动换回积分。订单退券沿用原有效期且不退兑换积分。
- Coupon Definition增加可选`issuanceMode:PUBLIC|SOURCE_ONLY`，空值按PUBLIC解释，旧构造和命令hash兼容。积分兑换只允许SOURCE_ONLY券，防止免费领取绕过价格。公众定义列表排除SOURCE_ONLY，管理列表可见。钱包允许同定义多张来源券，免费领取仍一会员一定义版本一次；来源唯一键保证不会重复发行。
- Entitlement sourceType增加POINTS。来源及兑换台账同事务记录，保留业务追溯；LEVEL/JOURNEY/ORDER原规则不变。
- V29扩展既有表并创建兑换目录、会员次数、回执、积分兑换事实。新代码读取旧定义为空默认PUBLIC；积分与来源券写入开启后不支持回退到旧应用，沿用LP04单写版本发布要求。

## 可观察验收

真实MySQL验证券/权益分别扣分、幂等、每会员与总配额并发、SOURCE_ONLY免费领取拒绝、失败回滚、停用/到期/冻结会员/身份隔离、原积分批次到期及奖励退款后欠项。浏览器验证经营创建、会员兑换、真实账本和钱包。种子通过API写库。
