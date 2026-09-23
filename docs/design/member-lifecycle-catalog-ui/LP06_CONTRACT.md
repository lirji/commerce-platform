# LP06 会员行为、人群与详情

## 边界与口径

member拥有生日月日、旅程偏好、行为日汇总及完成订单行为投影。生日不采集出生年份；按UTC经营日判断，2月29日只在闰年当天命中。journeyEnabled缺省true兼容现有站内旅程，会员可关闭；LP08自动生命周期入口及触达将检查该偏好。无短信/外部渠道。

浏览/加购是认证会员对商品的交互信号，不证明自然人购买意愿，不用于直接赠送资金或积分。接口不接受自报会员、金额、等级、标签和行为时间。app通过CatalogApi检查同租户同店上架SKU；member记录服务器时间。每会员每天最多200条，独立会员行锁隔离；重复eventId+相同内容返回原记录，不同内容冲突。事件日汇总最多31天参与30天滚动窗口计算（UTC自然日，今天及前29天），限额不因换幂等键被绕过。

完成订单来自既有可信Order/Refund消费链，读取member已有完成净消费来源，补齐原订单创建时间。最近成交定义为有完成事实订单的最近下单时间；退款全额不抹去曾成交事实，净消费另计。历史订单可分页补建，无投影时明确为暂无事实，不能伪造“从未下单”的时间。零现金积分订单仍计完成次数。

## API

- GET `/v1/admin/member-behavior/{member}` 与 `/v1/members/me/behavior` 返回 Detail `{member,profile,facts}`；Profile `{birthday:MM-DD|null,journeyEnabled,version}`；Facts `{memberId,browse30,cart30,completedOrders30,netSpend30,lastOrderAt,lastCartAt,daysSinceOrder:Long|null,daysSinceJoin,birthdayToday,journeyEnabled}`。管理只本租户，会员只本人。
- POST 同base `/profile`：`{expectedVersion,birthday,journeyEnabled,reason}`，CAS及审计；生日null可清除，冻结/注销拒绝修改。
- POST `/v1/members/me/behavior/events`：`{eventId,kind:BROWSE|ADD_TO_CART,storeId,skuId}` + Idempotency-Key，返回 `{eventId,kind,storeId,skuId,occurredAt}`。冻结会员409、跨租户/无SKU404或现有目录冲突、日上限409、参数400。
- GET 同base `/events?after=&limit=`：最近原始记录按单调sequenceId正序分页，限100；详情仅展示行为种类/商品/时间，不含认证或地址。
- POST `/v1/admin/member-behavior/rebuild`：`{after,limit}`(1–50)，后台装配层通过OrderApi分页，只补已有可信成长来源，返回`{next,scanned,done}`。同命令幂等，重复补建覆盖投影不重复计数；不是跨域写表。

## 规则事实与性能

MemberGrowthApi.Facts扩展可空behavior，旧JSON/构造兼容。分群批次100会员一次批量behavior查询，禁止逐会员SQL；详情及报价按单会员查询。
新增数值事实memberBrowse30、memberCart30、memberOrders30、memberSpend30、memberDaysSinceOrder、memberDaysSinceJoin；文本memberBirthdayToday、memberJourneyEnabled。无成交时不注入daysSinceOrder，规则返回UNKNOWN而非伪造99999天。现有规则保持不变，客户端不能覆写事实。

查询限定tenant/member及时间窗口，日汇总和订单投影各自聚合后组合，避免JOIN乘法；窗口按同一Clock快照。原始行为保留期限尚未由业务确认，本片不执行清理或宣称完成法定保留治理；详情游标有界，单会员日写入有上限。

## 验收

真实MySQL验证事件去重/冲突、跨主体隔离、日限额、UTC窗口、生日闰日、偏好CAS、订单退款净额/重复/补建、无成交UNKNOWN、新行为规则分群。浏览器验证会员偏好、商品交互、管理员详情及圈选。V30只新增表，旧契约可读。
