# 契约 v1

通用：/v1，认证主体决定 tenant/actor；所有写入 Idempotency-Key；重复相同请求返回原结果，不同 payload 返回409。版本写入 expectedVersion；非法输入400、无权403、不可见404、并发或状态冲突409。人民币两位小数，时间UTC。列表游标和limit默认50上限100。

|能力|路由族|业务契约|
|---|---|---|
|会员生命周期|POST /admin/members/{id}/profile、/status|资料含 displayName、expectedVersion、reason；状态 ACTIVE→FROZEN→ACTIVE，ACTIVE/FROZEN→CLOSED；CLOSED终态；绑定actor不变|
|经营授权|/admin/store-grants；/operations/stores|资源商家或店铺，主体来自本租户已存在运营凭据，动作限定商品经营；撤销必须生效；平台权限不下放|
|商品管理|/operations/products、/operations/skus/{id}|同店商品SPU与规格组合，标题/售价/状态更新需revision；下架不允许新报价，旧快照不被调价覆写|
|成长等级|/admin/member-growth/*、/members/me/growth|版本化门槛/净消费成长率、账本游标、人工调整原因；规则未启用保留旧等级|
|标签与人群|/admin/member-tags/*、/admin/segments/*|有界规则，人工标签可撤销；刷新任务有检查点与完整快照版本；实际会员数据计算|
|精细促销|现有 /admin/campaigns 扩展及 /preview|默认旧规则；可选商品范围/阶梯配置；模拟只读不占额度；不可变版本与资金分摊不变|
|旅程|现有 /admin/journeys 扩展|会员注册/等级变化/人群入组事实触发；频控与步骤效果幂等；不接外部渠道|
|效果|/admin/marketing-effects|日期/店铺/活动版本限定，实付/退款/净额/平台与商家补贴、观察窗口与口径|

细分 DTO 随对应切片在此追加，必须在产品实现前确定并保持向后兼容；若改变上述业务边界，先更新计划与影响分析。实际代码DTO为字段精确契约，完成时同步示例。

## OP02 授权 DTO

POST /admin/store-grants `{grantId,actorId,resourceType:MERCHANT|STORE,resourceId,permission:CATALOG,reason}`，初始ACTIVE/version0；POST /admin/store-grants/{id}/status `{expectedVersion,active,reason}` 撤销或恢复，不改绑定。GET同族按grantId分页。GET /operations/stores仅返回当前有效授权覆盖的正常店铺，商家授权含其未来新店铺（界面提示）。ADMIN可管理全部，MEMBER无经营权，OPERATOR仅CATALOG授权范围；不授予会员库、资金、订单、全租户营销发布或二次授权权力。授权主体必须有本租户有效OPERATOR凭据；凭据签发继续沿用既有身份接入流程。

## OP03 商品 DTO

POST /operations/products `{productId,storeId,title,category,brand}`，GET同路由需storeId/after/limit；SPU是单店经营商品，本轮不自动跨商家共享所有权。POST /operations/products/{id} `{storeId,expectedVersion,title,category,brand}` 修订元资料。POST /operations/skus `{skuId,productId,storeId,title,unitPrice,specifications:[{name,value}]}`：1–8个规格，属性名不能重复，排序规范化后同SPU组合唯一；无规格商品使用“款式:标准”明确建模。新SKU默认FROZEN（待上架）。POST /operations/skus/{id} `{storeId,expectedVersion,title,unitPrice,status:ACTIVE|FROZEN,reason}`；GET同族按storeId分页返回全部状态；GET /operations/skus/{id}/history需storeId和after版本游标。

已有旧SKU可在经营端编辑，不强行补造SPU/规格。规格组合创建后不可换绑，变更组合须创建新SKU，避免库存与历史订单语义混淆。上下架/调价在同一修订事务提交并记录原因；原快照报价在有效期内仍可提交，紧急禁售需要额外取消报价/订单流程，不在本轮暗改成交承诺。
