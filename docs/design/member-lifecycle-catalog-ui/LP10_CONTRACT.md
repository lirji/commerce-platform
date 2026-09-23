# LP10 批量定时经营与渠道价格契约

复用现有MySQL、Commands、商品修订记录与有界worker。主体租户和销售渠道均由认证端确定，不接受客户端自报低价渠道。

## 批量任务

POST /v1/operations/catalog-jobs：`{jobId,storeId,name,action:PRICE|PUBLISH|UNPUBLISH,runAt?,deadline,targets:[{skuId,expectedRevision,unitPrice?}],reason}`。1–100个唯一SKU、固定预期版本；PRICE每项必须有非负精确两位价格，其他动作不得携带价格。runAt空为立即执行，指定时须现在至未来30天；deadline在runAt之后最多7天。时间检查在幂等边界内，回放不因时间推移失效。

持久保存创建时真实Actor和固定输入；SCHEDULED→RUNNING→COMPLETED，另有CANCELLED/EXPIRED/ISOLATED。逐SKU事务写价格/状态及修订历史、SUCCEEDED或CONFLICT回执、任务进度；版本变化不强制覆盖，也不阻断其他项。扫描有界且按租户公平，失败回滚该项并退避，5次隔离；重试保留游标。

GET /catalog-jobs?storeId&after&limit<=100；GET /catalog-jobs/{id}/items?storeId&after=0&limit<=100。POST /catalog-jobs/{id}/control `{storeId,expectedVersion,action:CANCEL|RETRY,reason}`，创建者或ADMIN且仍有当前门店经营权限；取消只停止未提交项。POST /catalog-jobs/pump?storeId推动小批。

执行前重验原OPERATOR有效身份及当前门店CATALOG授权，撤权/到期不继续修改。ADMIN创建的任务视为已授予平台后台执行委托，退出或凭据到期不自动取消，需明确取消任务；不把后台执行伪装为用户仍在线。所有商品写仍由catalog拥有，复用价格/状态/修订规则。

## 可信渠道

Actor追加channel枚举WEB/MINI_APP，旧3参数构造与旧凭据默认WEB。认证从platform_credential受控字段装配，不从Header、Query或Quote.Request取渠道；没有会员自行切换身份渠道的接口。MINI_APP凭据通过受控运维/测试夹具配置，不将令牌展示或提交。

GET /operations/skus/{id}/channel-prices?storeId 返回最多2个当前渠道价；POST同路径 ` {storeId,channel,expectedVersion,unitPrice,validFrom,validTo,active,reason}`。首次expectedVersion0，后续CAS；有效期非空且递增，结束晚于现在，起点最多未来一年，跨度最多366天。SKU锁串行化更新，保存不可变渠道价修订历史。GET /.../channel-prices/{channel}/history?storeId&after=0&limit<=100。

会员目录与详情及报价统一选择认证渠道当前有效价；未配置/禁用/未开始/过期回退基础价。经营端搜索始终展示基础价，避免管理台误把渠道价当基础价。会员的价格区间按当前有效售价过滤。

CatalogApi提供有界批量价格投影（基础SKU修订、渠道价版本、结束时间）；Quote.View追加channel，Line追加channelPriceVersion（旧JSON缺省0），报价期限不超过任一已使用渠道价的结束时间。生成后价格快照仍按原期限有效，改价不会重写旧报价。消费报价必须与认证渠道相符。Order.View及订单头记录渠道，行保留渠道价版本与实际成交价，旧订单默认WEB。

WEB报价保留原命令hash，非WEB将可信渠道纳入hash；同一actor跨渠道同键不能回放到另一价格结果。请求体传channel继续作为未知字段400。所有旧租户未配渠道价时行为不变。

## 验收与发布

真实DB/HTTP验证未到时、逐项版本冲突、重复/并发推进、取消、撤权与隔离恢复；渠道主体不可伪造、展示报价一致、期限回退、旧报价保留、跨渠道消费拒绝和旧JSON兼容。页面支持批量选择/预期版本预览/定时/回执及渠道价历史；种子走实际API。

V34追加字段和表，旧数据默认WEB。启用MINI_APP凭据/渠道价格及任务前所有目录/报价/订单写入和worker实例须升级。旧代码不理解渠道与新报价行，不能混跑新渠道流量；回滚须先停新任务/渠道入口并保留新快照可读取的版本，不能只回滚镜像假称取消已调价效果。
