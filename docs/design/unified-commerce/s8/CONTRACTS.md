# S8 营销体系契约与细分

继续用户批准方案，按可验收子片推进：S8a可信人群/规则资产/活动审批；S8b券权益/预算/叠加与S5b订单占用、S7b退款补偿。S8父项须全部验收。既有单活动固定减免契约保持兼容；新能力新增显式字段，不能悄悄改变旧报价。

## S8a 可信资产与受治理发布

- ADMIN POST /v1/admin/audiences：{audienceId,version,name,source,watermark,validUntil,memberIds}。不可变人群快照最多500成员/次，唯一版本；watermark不得晚于服务端now，validUntil>watermark且窗口≤24小时。此窗口是本地首版政策，不声明外部实时画像能力。source必须有名称，导入行为审计。
- GET /v1/admin/audiences列最新版本；相同租户+快照中的会员为HIT，未包含为MISS，已过validUntil为UNKNOWN，UNKNOWN不能因NOT变成命中。报价批量读取所需版本，不逐活动查库。
- ADMIN POST /v1/admin/rules：{ruleId,version,name,rule} immutable DRAFT，POST /rules/{id}/{version}/publish使其PUBLISHED；规则字段目录GET /rule-fields列memberLevel(TEXT)、orderAmount(DECIMAL)。引用规则只可用这些可信字段；继承有界AST与三值语义。未来新增字段需正式注册及可信数据源。
- 活动Draft新增可选policy:{audience:{id,version},rule:{id,version}}；至少一项。存在policy的新活动必须DRAFT→IN_REVIEW→APPROVED→PUBLISHED，经/submit、/approve、/reject与expectedVersion、Idempotency-Key。REJECTED内容不原位改写，重新建版本；暂停后可回滚发布仍有效的已审批旧版本。
- 不含policy的旧v1活动保留原ADMIN直接发布行为，明确为兼容窗口；新增管理台走受治理policy。审批人身份写入命令审计，当前未声明双人四眼制度。
- 引用规则须PUBLISHED，创建活动时固化其AST，不能因规则新版本发布改变已创建活动。人群引用固定id/version，发布时必须存在且未过期。可用性检查和版本切换同事务；未知数据不默认全员。
- Quote保存sources:[{audienceId,version,source,watermark,validUntil,match}]，解释绑定可信版本；没有policy的旧报价缺该字段按空列表读取。规则orderAmount从服务器商品金额总和计算，禁止前端注入。有效报价最多保留300秒的报价时资格快照，后续同人群新版本不覆盖该报价；历史成交按固化版本解释。
- 内部条件树增加Literal三值资格节点，仅用于组合可信人群HIT/MISS/UNKNOWN，不开放给运营RuleNode JSON；HIT继续运行原AST，MISS/UNKNOWN直接拒绝，既不增加AST深度也不允许活动NOT绕过人群约束。

## S8b 待实施的稳定范围

- 增加定义/领取/钱包/预占/核销/返还、内部权益发放与消费/冲正台账，外部权益适配后置。
- 固定减免与百分比候选、确定性互斥/叠加、预算及平台/商家资金分摊。
- 报价只提供资格；数据库在订单事务中最终预占券/预算。取消释放，支付未知保持占用，付款确认核销。退款完成按明确券/权益政策冲正，不重算现行活动。
- S8b具体DTO与金额/返还政策在实现前追加；此列表不代表已实现。

## S8b1 券钱包与订单占用（本次可执行）

- 券定义immutable：definitionId/version/storeId/name/minimumSpend/discountAmount/validFrom/validTo/quota/stackable。ADMIN创建；会员可查看本店可领取定义，每会员每定义版本一张；数据库issued<quota条件更新及唯一键，不能超发。已领再次领取返回同一张，不补发。
- POST /v1/coupons/{definitionId}/{version}/claim；GET /v1/coupons个人钱包；GET /v1/coupon-definitions?storeId；ADMIN /v1/admin/coupon-definitions POST与GET。
- Quote.Request新增可选couponId。券资格从钱包和定义读取，失效/跨店/非本人/非AVAILABLE拒绝；原价达到券门槛后可用。每单最多一张券。stackable=true允许与单项最佳活动叠加，封顶整单金额；false在券与活动中择优，同省钱优先不用券。活动间仍互斥，不声称任意组合全局最优。
- Quote.View增加coupon快照，实际活动/券优惠分别保存，行分摊按最终总优惠重新精确分配。券参与报价但未选中时不占用。报价TTL不超过选中券有效期。
- 下单本地事务中AVAILABLE→HELD绑定订单；报价消费、券占用、库存、订单与事件同事务。竞争失败全部回滚。已开始支付UNKNOWN/CLOSING保留券。
- 付款确认HELD→USED；未付取消HELD→AVAILABLE（若过期则EXPIRED）。全量退货完成后USED→AVAILABLE/EXPIRED；部分退款保持USED，累计全量退货才返还。返还标记挂在原订单占用行，防旧退款事件释放已被新订单使用的券。
- 该返还政策是当前隔离平台的明确默认；真实接入前可按商家政策另建版本，不倒改历史订单。权益授予与预算在S8b2继续，S5b/S7b父项待完整权益能力后结案。
