# 候选建设项与当前不建议的事项

- generated_at：2026-09-27；baseline：`7dda31ed017617f3701480f8a2d1620656c843cc`
- protocol：`engineering-baseline/v1`、`skill-contract/v1`、`capability-exploration-report/v1`
- 来源：Claude `project-capability-exploration`，遵循 Evidence → Observation → Gap → Opportunity → Recommendation。
- 目标边界：这里只回答 WHAT TO BUILD；下面不是已批准需求或技术选型。分类/priority 是建设优先级，不等同已发生事故严重度。

## Exploration Summary

最有价值的后续工作是验证多项目中台复用、补齐自营持续购物、收口运营及长期运行治理。业务入口继续增长前，应把已有机制接成可运营的流程。通用BPM、全量微服务、Kafka/Redis/ES或AI不因“企业级”自动成为建设项。

## Must Fix

这里指满足触发条件前必须补的已有边界；没有触发时不把条件风险判为当前生产事故。

### MF01 持续告警、容量目标与恢复验收（G06/G07）

- capability / currentState：PARTIAL；有指标、告警代码、平台视图、多进程恢复和本地压测。
- problem / evidence：默认告警仅写日志；1万到期节点排空469.990s；生产等待目标及DB/密钥恢复证据未齐，E24/E28/E29/E34/E35。
- whyNeeded：无人值守、持续运营或集中营销窗口要求可发现、可响应并可恢复。
- proposedCapability / expectedBenefit：持续采集与告警送达/清除/升级；真实峰值/等待目标、混合持续负载与隔离恢复验收；异常可及时处置，容量取舍有依据。
- businessValue / engineeringValue：降低营销延迟和业务中断成本；沿用已有告警/恢复端口，形成运行证据。
- complexity / risk：中；主要成本为真实负载、环境与负责人协调；不得把共享库故障操作当演练目标。
- dependency：运行负责人、真实峰值/租户分布、SLO/RTO/RPO、隔离恢复环境；地址密钥治理依赖MF02。
- class / priority / triggerCondition / status：MUST_FIX / P1 / 无人值守或承诺生产保障前 / RECOMMENDED。
- confidence：现状FACT；生产达标NEEDS_VERIFICATION。

### MF02 数据生命周期与密钥治理（G08）

- capability / currentState：PARTIAL；终态事件/命令清理机制完成，默认关闭；新Journey步骤/会员行为持续增长。
- problem / evidence：会员CLOSED不删除资料；地址密钥单一、写入版本1；保留依据未批准，E05/E16/E23/E35。
- whyNeeded：长期增长、注销/删除请求和密钥轮换要求可以解释与执行。
- proposedCapability / expectedBenefit：逐类保留/归档/删除与恢复传播；明确幂等及重放窗口；受控密钥版本与历史密文恢复。降低存储成本并保留必要账本证据。
- businessValue / engineeringValue：客户资料处理可执行；不破坏业务防重与恢复。
- complexity / risk：中至高；提前删账本/防重记录或丢密钥可能造成不可恢复影响。
- dependency：业务保留依据和权限、MF01的隔离恢复验证；不能自行设统一期限。
- class / priority / triggerCondition / status：MUST_FIX / P1 / 长期运行、敏感删除或密钥轮换前 / NEEDS_MORE_EVIDENCE（政策UNKNOWN）。
- confidence：机制FACT；目标政策UNKNOWN。

### MF03 运营分工与激励承诺（G05/G12）

- capability / currentState：PARTIAL；商品范围授权、审批状态和审计已具备；营销能力全由ADMIN取得；赠券和礼包有明确现行行为。
- problem / evidence：未建立编辑/审核/发布组织分工；赠券退款保留，多权益受理后分别到账，E08/E12/E14/E15/E23/E27/E30/E37。
- whyNeeded：多人经营、高成本奖励或退款撤奖励/全礼包承诺必须有对应权限和处置口径。
- proposedCapability / expectedBenefit：业务定义的权限/范围与职责分离；赠券/已消费奖励及礼包部分失败政策和可见处置；防止客户承诺与系统结果不一致。
- businessValue / engineeringValue：运营可分工，激励成本可解释；策略验收锁定具体失败场景。
- complexity / risk：中；未确认就禁止自批/回收资产会改变现有业务。
- dependency：组织职责、奖品成本、退款与礼包兑现政策；现有台账/钱包/状态机继续作为权威。
- class / priority / triggerCondition / status：MUST_FIX / P1 / 多运营团队或发布相关奖励承诺前 / NEEDS_MORE_EVIDENCE。
- confidence：当前实现FACT；目标政策UNKNOWN；不是既有越权/错账判定。

### MF04 真渠道明确拒绝与财务差错闭环（G13）

- capability / currentState：PARTIAL；持久UNKNOWN/SUCCEEDED退款、查单和总额约束已具备。
- problem / evidence：RefundService.reconcileInternal只将SUCCEEDED推进，其他proof保留UNKNOWN；尚无确定拒绝后的额度/售后处置及账单差错闭环，E21。
- whyNeeded：真实渠道可能明确拒绝；必须区分仍未知和权威确定失败。
- proposedCapability / expectedBenefit：按真实合同建模确定拒绝、金额额度处理、重发或人工处置及账单核对；保留UNKNOWN安全语义。避免真拒绝长期卡住且不发生重复退款。
- businessValue / engineeringValue：资金事实和客服处置可闭环；验证未知/拒绝/迟到结果的相容性。
- complexity / risk：高；失败终态和重发政策涉及资金不变量，不能只添加枚举。
- dependency：NE05真实渠道合同、售后政策、渠道账单和幂等范围。
- class / priority / triggerCondition / status：MUST_FIX / P1 / 按既定后置安排开展真实资金联调前 / DISCOVERED。
- confidence：当前分支FACT；真实渠道行为NEEDS_VERIFICATION。

## Natural Evolution

### NE01 接入应用、主体与商品共享边界（G01）

- capability / currentState：PARTIAL；已有租户/主体/角色/销售渠道与本地会员/店铺商品。
- problem / evidence：无调用应用和外部ID映射，商品主档随店铺，E05/E09/E30/E36。
- whyNeeded：这是“多个项目复用会员/商品/营销”的业务目标仍未兑现的部分。
- proposedCapability / expectedBenefit：明确租户/应用/组织、平台会员与外部主体、商品主档与可售关系、授权共享与退出政策；第二项目接入不复制权威业务。
- businessValue / engineeringValue：可真实复用数据/资产；契约和数据所有权清楚。
- complexity / risk：高；错误合并会员或隐式共享数据不可接受。
- dependency：第二接入项目、主数据权威、共享授权政策；不依赖先拆服务。
- class / priority / triggerCondition / status：NATURAL_EVOLUTION / P1 / 选定第二项目或同主档多渠道经营 / RECOMMENDED（先发现/明确边界）。
- confidence：现状FACT；完整目标INFERRED，具体接入范围UNKNOWN。

### NE02 合作方可信事实与结果交付（G02）

- capability / currentState：PARTIAL；内部可靠事件和本地订单API已有。
- problem / evidence：成长、旅程和效果仍依赖本地订单事实，无外部事实/交付完整链，E04/E06/E23/E26/E36。
- whyNeeded：接入方应可保留自己的交易系统。
- proposedCapability / expectedBenefit：应用范围下的外部订单/退款/行为事实、版本/幂等/乱序/核对和可靠结果交付；以第二应用完成积分或营销纵向复用验收。
- businessValue / engineeringValue：中台可供独立业务使用；内外契约的保障范围可测试。
- complexity / risk：高；不可信来源会污染账本/资格，不能接收任意自报金额。
- dependency：NE01；外部事实来源、接口/事件合同和授权；不预设Webhook、Broker或新服务为最终技术。
- class / priority / triggerCondition / status：NATURAL_EVOLUTION / P1 / 第二项目继续自持订单或其他权威事实 / RECOMMENDED。
- confidence：仓库缺口INFERRED。

### NE03 自营持久购物闭环（G03/G04）

- capability / currentState：BASIC/PARTIAL；已能搜索/加购物袋/报价/下单/支付/履约/售后。
- problem / evidence：购物袋仅React状态；每单重填地址；报价无配送费用，E19/E20/E22/E31。
- whyNeeded：自营商城是实际应用，跨会话续购和配送是自然购物需求。
- proposedCapability / expectedBenefit：数据库购物车、地址簿、配送范围/运费与快照、失效商品处理；物流/售后必要资料按实际政策加入。会员能持续购物，订单金额可核对。
- businessValue / engineeringValue：减少中断购物和手工客服；复用现有可信报价/事务预占。
- complexity / risk：中；新增运费影响退款分摊与历史兼容，需明确政策。
- dependency：商品类型、配送/运费/退款政策；地址资料保护；不要求立刻真实WMS联调。
- class / priority / triggerCondition / status：NATURAL_EVOLUTION / P1 / 自营商城继续建设为实际购物应用 / RECOMMENDED。
- confidence：现状FACT；目标INFERRED。

### NE04 运营导入、媒体与资料质量（G09/G10）

- capability / currentState：PARTIAL；定时经营、动态分群、批量发券可恢复；图片URL/固定名单单批500/商品任务100。
- problem / evidence：缺文件/合作方建档和媒体上传完整任务链，E09–E11/E13/E25。
- whyNeeded：实际运营需要持续上新或批量维护。
- proposedCapability / expectedBenefit：首个明确导入场景的校验/预览/逐项结果/检查点/取消恢复；运营媒体引用和资料质量检查；提高维护效率且可核对部分成功。
- businessValue / engineeringValue：减少人工重复操作；避免盲目扩大请求及事务边界。
- complexity / risk：中；导入不能默默覆盖运营版本或把脏输入当权威。
- dependency：真实文件样本、更新/冲突规则、输入版本和配额；任务查询复用PL01。
- class / priority / triggerCondition / status：NATURAL_EVOLUTION / P2 / 出现真实文件维护或超过单批的需求 / DISCOVERED。
- confidence：INFERRED。

### NE05 外部身份和渠道适配验收（G13）

- capability / currentState：PARTIAL；本地身份、沙箱和领域端口已经建设。
- problem / evidence：真实IdP、支付/退款、权益和WMS尚未验收，E21/E22/E30/E35/E36。
- whyNeeded：真实收款、发货和身份认证必须依赖可验证外部事实。
- proposedCapability / expectedBenefit：主体合同、验真/查单、幂等/重复乱序/超时、授权地址披露、对账和故障恢复；验证生产外部保障范围。
- businessValue / engineeringValue：允许真实业务经营；保持当前本地一致性设计的有效边界。
- complexity / risk：高；需真实权限/密钥/沙箱及业务政策。
- dependency：MF01–MF04相关门槛与外部合同；地址密钥轮换归MF02。
- class / priority / triggerCondition / status：NATURAL_EVOLUTION / P1 / 遵守用户“整体建设后联调”的既有安排 / DISCOVERED（明确后置）。
- confidence：现有端口FACT；外部认证NEEDS_VERIFICATION。

### NE06 规则判定证据与发布前样本比较（G11）

- capability / currentState：PARTIAL；纯预览、固定版本、Truth/actionRef/步骤历史已具备。
- problem / evidence：历史无完整Facts；重预览当前状态不能复现过去输入，E12/E23/E24。
- whyNeeded：高成本规则、决策申诉或运营需要评估版本差异。
- proposedCapability / expectedBenefit：按最小必要范围保留事实版本/判定摘要，治理样例和版本差异，支持历史样本验证；在隐私/存储成本可接受时提升解释能力。
- businessValue / engineeringValue：运营可解释误命中；规则演进可核对；不等同自动全程replay。
- complexity / risk：中；增加敏感快照和保留成本，不能默认全量复制个人数据。
- dependency：事实口径、MF02生命周期、明确的解释/仿真用例。
- class / priority / triggerCondition / status：NATURAL_EVOLUTION / P2 / 真实决策申诉或复杂高成本发布 / DISCOVERED。
- confidence：FACT＋INFERRED。

### NE07 后端供应链证据与有用的边界门禁（G14）

- capability / currentState：PARTIAL；真实DB/E2E/jdeps CI与npm audit已运行。
- problem / evidence：工作流未包含后端完整依赖风险/SBOM门禁；jdeps不检查SQL语义，E32/E33。
- whyNeeded：发布治理需要知道交付了什么依赖，并拦截真实所有权回归。
- proposedCapability / expectedBenefit：后端依赖风险和制品清单证据、必要例外治理；按实际越界风险补SQL写入/契约兼容检查。可评估发布风险，减少手工查漏。
- businessValue / engineeringValue：发布证据更完整；不依赖堆覆盖率或引入完整质量平台。
- complexity / risk：低至中；误报/失效数据库需要治理，不应静默忽略或顺手全仓升级。
- dependency：现有CI与依赖管理，实际边界/契约约束。
- class / priority / triggerCondition / status：NATURAL_EVOLUTION / P2 / 明确供应链发布要求或出现真实边界回归 / DISCOVERED。
- confidence：配置FACT；未认定具体漏洞。

## Platformization Opportunities

### PL01 统一运营任务查询与恢复入口（G09）

- capability / currentState：PARTIAL；商品job、人群run、发券batch、Journey scan都有持久进度/结果/控制，公共调度和恢复机制已有。
- problem / evidence：四类任务各有API/页面和结果字段，运营需在多个入口判断进度与部分失败，E10/E13/E23/E25/E18。
- whyNeeded：重复“查进度/定位失败/核对取消或恢复”需求已出现，适合复用操作体验。
- proposedCapability / expectedBenefit：统一有界任务查询、状态说明、来源链接和受权限约束的恢复入口；业务状态/重试仍由原owner处理，减少运营跳转。
- businessValue / engineeringValue：操作效率和恢复可见性提高；不重新发明共享调度器或统一所有业务状态机。
- complexity / risk：中；统一取消不能误称撤销已提交效果，不能让公共中心绕过领域权限。
- dependency：现有领域端口/WorkLanes/Recovery与组织权限MF03，明确运营使用场景。
- class / priority / triggerCondition / status：PLATFORMIZATION / P2 / 运营频繁跨任务排障，或首个新导入场景接入 / DISCOVERED。
- confidence：重复能力FACT；统一入口收益INFERRED。

## Exploration Opportunities

### X01 经营实验与完整成本评估（G11）

- capability / currentState：PARTIAL；已有描述性活动/旅程/发券队列、净收款和优惠承担。
- problem / evidence：接口明确不提供因果提升、全成本利润/ROI，E26。
- whyNeeded：只有业务真的要按增量收益选策略时需要。
- proposedCapability / expectedBenefit：经业务接受的分组/观察窗口、样本与指标口径、完整成本数据；能回答策略增量效果而不是只比较成交量。
- businessValue / engineeringValue：可能提高奖励预算效率；评估结果可解释。
- complexity / risk：高；样本偏差/跨组干扰及成本数据缺失会制造虚假结论。
- dependency：明确的经营问题、可接受实验方案、成本权威、可信事实和MF02。
- class / priority / triggerCondition / status：EXPLORATION / P3 / 确定经营优化目标且数据足够 / NEEDS_MORE_EVIDENCE。
- confidence：现接口边界FACT；收益INFERRED。

### X02 多仓、商家结算或特殊玩法（G15）

- capability / currentState：BASIC/ABSENT；当前库存为店铺SKU可售额度，商家为基础资料。
- problem / evidence：未发现仓内作业、应付结算或特殊玩法生命周期，E08/E20/E22。
- whyNeeded：仅在对应业务明确进入范围时。
- proposedCapability / expectedBenefit：一次选择一个已确认业务，定义权威、状态、资金/库存及退款恢复；支持该经营场景。
- businessValue / engineeringValue：潜在业务扩展；不会把营销分摊冒充商家财务。
- complexity / risk：高；新增资金/实物权威和多个失败边界。
- dependency：合同/收款主体/库存权威/具体玩法政策，可能依赖NE05。
- class / priority / triggerCondition / status：EXPLORATION / P3 / 第三方商家、多仓或玩法有明确业务目标 / NEEDS_MORE_EVIDENCE。
- confidence：仓库缺失INFERRED，业务需求UNKNOWN。

## Not Recommended Now

以下统一status=NOT_RECOMMENDED_NOW。

| 项目 | 现在不做的原因 | 重新评估触发 |
|---|---|---|
| 全量拆微服务/K8s | 当前同库事务承担库存/预算/积分/权益等不变量；没有独立扩容/团队边界实证 | 经容量/故障隔离/团队证据证明某边界抽取收益 |
| 通用BPM、fan-out/in、子流程、人工任务/任意脚本 | 当前32节点单路径模型满足已批准旅程；新增语义带来新的失败/补偿成本 | 有不可由当前节点表达的真实流程和验收政策 |
| 重建规则、权益或公共调度器 | RuleDecisionPort、领域API、WorkLanes和显式动作registry已使用 | 现有边界无法满足已确认场景，先证明缺口 |
| 默认加Redis或两级缓存 | 当前事实/库存/资金依赖权威库；没有本轮命中收益及一致性容忍度证据 | 实测热点读取且可定义陈旧窗口和故障回源预算 |
| 为替代可靠性直接上Kafka/RabbitMQ | 当前Outbox/Inbox及恢复有证据；Broker不会自动解决外部副作用 | 有独立消费/吞吐/保留/重放需求及端到端语义 |
| 直接上ES、分库分表、Flink/数仓 | 搜索和报表有明确SQL边界；缺真实规模/业务功能驱动 | 执行计划与负载证明最小充分方案需要新组件 |
| 无治理地提高人群500/任务100/候选100上限 | 输入大小不是唯一瓶颈；任务截止、版本、配额与同步扇出仍需验收 | 实际大名单/运营量，并完成有界导入及容量验证 |
| 全Facts复制、Journey全程replay、自动重发资金动作 | 敏感数据、存储和副作用边界尚未授权；已有原节点恢复/查单 | 明确历史证据需求和领域安全合同 |
| AI Agent/RAG/NL2SQL自动运营 | 无已确认场景；授权、事实口径、效果评估优先 | 有明确人工任务与可衡量价值，先从受权限的辅助场景评估 |
| 默认上付费会员/储值/拼团/商家结算 | 不在已确认业务范围；不能因为“企业级”造需求 | 对应用户价值、成本与生命周期政策确认 |
| 无门禁迁移旧规则 | README与既有记录仍为独立BLOCKED工作 | 原迁移门禁解除，规则语义和样本齐备 |

候选选择交给用户业务目标；选定后进入相应设计/实现技能。本轮不把分析通过当作功能建设已获批准。
