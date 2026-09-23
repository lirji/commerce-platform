# 当前资产与缺口

证据是当前工作树源码抽样，指纹见 ../../evidence/source-evidence.json。扫描不等于原项目全量验收；多个仓库存在用户未提交修改，禁止整仓复制或清理。

| 能力 | 当前证据 | 新平台处理 |
|---|---|---|
| 会员 | transaction-member；OrderService 经 MemberTradingPort 验证身份 | 保留会员业务身份，身份提供方 subject 经显式映射；不与登录账号混用 |
| 商家 | MerchantService 按 tenant 查询配置，收款主体不取客户端参数 | 新建商家上下文，明确租户与商家关系；原模型不能直接证明支持多商家 |
| 店铺 | 营销决策有 shop scope；规则仓有门店查询 | 新建独立店铺归属/营业/履约能力，统一权威来源 |
| 商品/交易 | QuoteService 使用发布 SKU 快照、精确金额、报价 TTL | 商品拥有 SKU/版本，交易拥有购物车/报价/优惠分摊；原 catalog 中报价应逻辑分离 |
| 订单/库存 | OrderService 经端口预占库存，命令/订单/事件本地事务 | 参考并保留安全不变量，提取可测试状态机，库存是独立支持域 |
| 支付/退款 | transaction-payment / transaction-refund 有 ChannelAdapter UNKNOWN 状态及恢复服务 | 先建适配契约及隔离沙箱，再接真实渠道；未知结果不能重付/释放 |
| 履约 | OrderFulfillmentService、WMS FulfillmentService | 电商拥有履约请求/进度，WMS 拥有仓内执行和实物库存；通过契约协作 |
| 活动/规则 | drools ActivityRuleRuntimeService；营销 RuntimeManifestRegistry | 首先自建受限纯计算契约；旧 Drools 迁移另受旧门禁约束，不复制引擎 |
| 人群 | AudienceService；DecisionApplicationService 读取 AudienceMembershipProjection | 人群结果带版本/有效期/主体/租户；未知不能当满足 |
| 优惠 | 营销 DecisionApplicationService 引用定价引擎、生成报价及签名 claims | 优惠策略版本化，交易保存决策快照，后续接预算/券/分摊 |
| 权益 | benefit-domain/application/adapters；AcceptAwardIntentUseCase | 参考受理/履约/账本模型；资格与实际发放分离，保留幂等和恢复 |
| 旅程 | JourneyApplicationService、DispatchRelay、输出投影 | 版本化实例、等待/超时/取消、节点幂等，调度持久化 |
| 低代码 | lowcode-language-core、marketing-control、rule-compiler-worker、console | 优先受限 schema + 校验 + 预览 + 发布；禁止运营配置任意 Java/SQL |
| 通用平台 | auth-platform Casdoor/SpiceDB，workflow-platform Flowable | 适配现有权限/审批，避免重新实现；不把审批引擎充作订单状态机 |
| 基础设施 | dev-infra 声明 MySQL 8.4、Redis 7、Kafka、RabbitMQ | 数据库首次需要时申请隔离 schema；声明版本不代表实际运行/兼容已验证 |

新建部分不是重复造所有旧能力：先统一边界与契约，每个资产分别决定参考算法、受控移植或外部适配。旧前端和数据库不直接拼接进入新应用。
