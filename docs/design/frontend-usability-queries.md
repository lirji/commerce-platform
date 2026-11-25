# 列表只读查询补充契约

范围经用户确认：全站前端及必要只读查询。接口原路径、响应DTO、租户与门店权限、业务写入、幂等和稳定ID顺序继续有效；未提供查询条件的旧调用保持原语义。所有条件由对应Owner在SQL的LIMIT之前执行，返回前继续原授权复核。

| 列表路径（均以 `/v1` 开头） | 关键词查找范围 | 其他新增条件 |
| --- | --- | --- |
| `/admin/members` | 会员编号、认证账号、显示名称 | 状态 |
| `/admin/merchants`、`/admin/stores` | 商家/门店编号、名称，门店也可查所属商家编号 | 状态 |
| `/catalog`、`/admin/skus` | 规格编号、商品编号、名称 | 状态；仍只返回当前可售目录 |
| `/operations/products`、`/operations/skus` | 商品/规格编号、名称，商品也可查分类和品牌 | 规格状态 |
| `/orders`、`/admin/orders` | 订单、会员、门店、商家编号 | 订单状态、创建时间 |
| `/admin/fulfillments` | 订单编号、运单号、仓配渠道 | 履约状态 |
| `/aftersales`、`/admin/aftersales` | 售后、订单、会员编号，管理员还可查退款编号 | 售后状态 |
| `/admin/refunds` | 退款、售后、订单编号、渠道 | 退款状态 |
| `/admin/campaigns`、`/admin/rules` | 活动/规则编号、名称，活动还可查门店编号 | 状态；最新内容版本 |
| `/admin/audiences` | 人群编号、名称、来源 | 最新快照版本 |
| `/admin/segments` | 人群定义编号、名称 | 启用状态；当前定义版本 |
| `/admin/journeys`、`/admin/journey-instances`、`/admin/journey-scans` | 定义编号/名称/门店；实例编号/旅程/会员；扫描旅程编号 | 对应生命周期状态 |
| `/admin/coupon-deliveries`、`/operations/catalog-jobs` | 发券批次/商品任务编号、名称 | 状态；原门店条件仍必需 |
| `/coupon-definitions`、`/admin/coupon-definitions`、`/admin/entitlement-definitions` | 定义编号、名称 | 无状态筛选；保留原门店条件 |
| `/point-offers`、`/admin/point-offers` | 积分兑换活动编号、名称 | 启用/停用生命周期状态；原门店条件仍必需 |
| `/coupons` | 优惠券编号、定义编号、名称 | 实际展示状态，过期判定与钱包DTO一致 |
| `/entitlements`、`/admin/entitlements` | 权益实例、订单、会员、权益编号、名称 | 实例状态；会员端仍仅本人资产 |
| `/admin/ops-pages` | 页面编号、标题、门店编号 | 页面状态；最新内容版本 |
| `/admin/inventory`、`/admin/member-tags` | 库存规格编号；标签编号/名称 | 原门店条件或标签权限 |
| `/admin/campaign-budgets`、`/admin/store-grants` | 预算/活动编号；授权/账号/资源编号 | 授权启用状态 |
| `/admin/events` | 事件编号、事件类型、业务对象编号 | 事件处理状态；不搜索载荷 |

`q` 为去掉首尾空白后最多64字符的字面子串，空白相当于未筛选。`%`、`_` 不作为SQL通配符。`status` 为真实状态代码，空白不筛选；无状态事实的目录拒绝状态筛选。`enabled=true/false` 仅用于人群定义与旧门店授权，两种布尔值都实际生效。

`from`、`to` 仅用于订单创建时间，以ISO8601 Instant传入；起点包含、终点不包含，可单独填写，同时填写时起点必须早于终点。无时间事实的Owner拒绝时间条件。前端以本地时间输入并转换为UTC，分享/刷新时按使用者时区还原输入。

保留既有 `after` 和有界 `limit`；前端每页选项10/25/50/100。更改条件或每页数量清空游标与访问轨迹，首页/上一页/下一页使用真实访问记录。URL轨迹最多保留最近50次访问，不据此推算服务器总数；有游标但缺少访问轨迹的分享链接显示“续查位置”。原商品作用域接口继续使用可信 `total`、`nextCursor` 和原25条容量。

原有会员、门店、任务类型、消费类型、时间窗口和资源版本等上下文筛选保留。历史/账本、统计、任务运行等列表继续各自已存在的协议，本次不新增无数据来源的筛选、排序、全量导出或总数。所有列表仍读取真实接口，浏览器测试夹具仅用于明确测试边界。

验证入口：MemberOperationsTest验证跨页关键词、字面特殊字符、输入边界和所有新增关键词SQL；CentralOrderOperationsMySqlTest验证门店范围、订单状态/时间及关联履约售后退款；SegmentTest与StoreAccessTest验证 `enabled=false` 不能丢失。
### 交易精确筛选

`GET /v1/orders` 支持可选 `storeId`，`GET /v1/admin/orders` 支持可选 `storeId`、`orderId`。履约、管理售后、退款列表支持同样两项，履约另支持 `blocked=true|false`。会员售后只支持精确 `orderId`。编号复用 1–64 位业务标识规则。条件在可信租户与本人/员工原权限内、游标和 LIMIT 之前求交集，完全相等，未授权范围和不存在范围都返回空列表。共享关键词仍保持部分匹配。重置及更改查询回到首批。

订单列表在原 JSON 字段基础上添加可空 `storeName`，来自本页已授权交易的门店。冻结历史门店仍可补名称，来源缺失时原编号继续呈现。命令和事件保持原数据形状。

### 跨模块精确读取

运营门店目录接受 `merchantId`，活动预算接受 `campaignId`。按完整业务编号在目标域权限与分页条件内求交集。活动预算仍包含该活动各版本。目录、预算以及发券来源的读取能力分别授予。

`GET /v1/admin/audiences/snapshot?audienceId=…&version=…` 返回对应历史快照摘要。`GET /v1/admin/coupon-definitions/revision?storeId=…&definitionId=…&version=…` 使用当前定义目录门禁并核对固定门店。找不到指定版本时返回 404，撤权时返回 403。人群摘要只含成员数量等原有摘要字段。

订单详情按原 `orderId` 跳到独立的支付、履约、售后或退款读取。中央链接保留当前组织和环境，仅显示本人菜单中可达的页面。带条件进入时从第一页查询。导出任务的本人状态 DTO 返回创建时的检索条件，下载仍按原有限时、范围和当前权限校验。

### 门店异常待办

待办读取与明细使用同一业务端点及门店条件。订单使用 `CLOSING`，履约使用 `blocked=true`，售后使用 `REQUESTED`，退款使用 `UNKNOWN`。每队列最多读取首批 10 条，满批显示至少 10 条。明细继续使用原游标分页。拒绝与读取失败各有反馈。
