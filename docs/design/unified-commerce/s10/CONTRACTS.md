# S10 接口与实施补充

## S10a 前端所需有界读取

- ADMIN GET /v1/admin/orders?after=&limit=50、GET /v1/admin/orders/{id}：本租户订单投影，使用OrderApi.View，不返回地址或支付凭据；详情items，列表不含items。不得改变原/v1/orders本人范围。
- ADMIN GET /v1/admin/orders/{id}/payment与POST /v1/admin/orders/{id}/payment/reconcile：读取同租户付款尝试/主动核对，与原会员接口同View/核对语义。不得用管理请求提供支付金额或伪造正式渠道结果。
- AUTHENTICATED GET /v1/stores?after=&limit=50：租户内店铺目录，不含密钥或内部地址；状态字段明确显示，不让冻结店铺进入成交。
- AUTHENTICATED GET /v1/runtime-capabilities：{sandboxEnabled,workersEnabled}来自类型化配置，用于控制本地沙箱管理入口文案，不是授权替代物。
- 其余页面只使用S4–S9已发布契约。身份/v1/me返回tenantId/actorId/role；令牌不进入URL、日志、源代码或浏览器持久存储。

## S10分片

S10a：管理台/消费端及必要读取API、类型检查/构建、真实浏览器闭环。S10b：幂等数据库演示seed、容器与CI文件、真实本地运行验证、独立代码审查整改、文档最终同步及正常Git交付。外部渠道联调保留待接入清单，不以此伪造完成证据或阻塞内部计划。
