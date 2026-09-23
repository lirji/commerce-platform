# S4持久化闭环契约

用户授权连续执行S4–S10；2026-09-23明确真实外部联调后置。仅建设本地可运行应用及明确沙箱，真实支付/身份/WMS/权益接口默认未配置，不能静默当成功。

- 数据库：复用dev-infra MySQL8.4.11，独立commerce_local与commerce_test_20260923，专用账号仅有这两个库权限；无共享数据清空。
- 应用：Java21、Spring Boot4.1.1 BOM、MyBatis4.0.1、Flyway由BOM管理；单装配应用。官方版本依据见TECH_SELECTION增量。
- 本地身份：Bearer opaque token，DB只存SHA256，绑定tenant/actor/role/member及有效期；角色ADMIN、MEMBER。不是外部SSO适配的完成声明。服务默认绑定127.0.0.1，显式本地seed才创建凭据，原始值只落.gitignore的.local。
- 所有/v1业务端点必须认证；请求不能自带可信tenant/actor。管理写需要ADMIN，会员查询/报价只读自己的主体。未授权401，无权限403，跨租户/归属资源统一404。
- 错误统一code/message/traceId，不返回SQL/堆栈/原始token。INVALID_INPUT 400，NOT_FOUND 404，CONFLICT/IDEMPOTENCY_CONFLICT 409，UNAVAILABLE 503。未知异常500通用说明。
- 写请求Idempotency-Key为1..64合法ID；作用域tenant+actor+operation+key。JSON规范化摘要，同键同请求返回原持久化结果，不延长时效；同键异请求409。业务效果/回放结果/审计同一事务，失败全部回滚。事务10s，锁等待5s，批次/分页最多100。
- 主数据：会员memberId与actorId显式一对一；商家/店铺/SKU均在tenant下有稳定ID。店铺属于一个merchant。首阶段会员报价单商家单店，CNY整数件数。列表以稳定ID游标after+limit，不开放任意排序/SQL。
- 商品：SKU创建发布不可变revision=1，单位金额decimal(14,2)，0..999999999999.99；后续改价产生新revision，不覆盖历史报价。
- 活动：先存DRAFT不可变版本，发布需version条件更新并审计；PUBLISHED/PAUSED状态控制新报价。C1–C3条件树用受限JSON节点{kind,field,operator,valueType,value,children}，只支持ALL/ANY/NOT/COMPARE，验证节点/深度/数量后转换为已有内核。预算/券在S8完成。
- 报价请求只含storeId与[{skuId,quantity}]，不接受价格、人群事实或候选活动。服务端读取商品、会员等级和已发布活动；事实首期memberLevel来自会员表。最多100行/100活动，重复SKU合并且总数量≤10000；规则候选超过100明确拒绝，不截断改变定价语义。
- 报价ID服务端生成UUID，绑定tenant/member/merchant/store、创建时间/到期时间（300秒）、SKU revision、活动version及完整结果；HTTP金额以十进制字符串输出，UTC ISO8601时间。GET仅本人；重启后保持相同结果。报价只是定价快照，不是库存/券保证。
- 下单消费引用保留给S5；S4不创建订单或模拟支付。

端点：POST/GET /v1/admin/members、merchants、stores、skus；POST/GET /v1/admin/campaigns；POST /v1/admin/campaigns/{id}/{version}/publish与pause；GET /v1/catalog?storeId&after&limit；POST /v1/quotes；GET /v1/quotes/{id}；GET /v1/me；GET /actuator/health。

S4a模块装配+数据库安全身份；S4b主数据+活动版本；S4c报价持久化；S4d真实HTTP/MySQL集成验证。所有S4子片串行，跨域访问只经api，事务可跨同库端口但不跨远程调用。
