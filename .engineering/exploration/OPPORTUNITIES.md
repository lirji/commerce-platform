# 候选建设项与暂缓项

- 日期：2026-09-23
- 项目：`/Users/liruijun/personal/LLM/commerce-platform`
- 协议：`engineering-baseline/v1`、`skill-contract/v1`、`capability-exploration-report/v1`
- 分析基线：HEAD `fb7adf6278ed98c49d8851102031b061bcbb68bf` 加当前工作树。开始时已有 `CommerceController.java`、`CampaignService.java` 两处未提交修改，属于既有内容。
- 范围：源码、接口、Mapper、V1–V15迁移、前端、测试源码、CI配置与既有验收证据；只生成本目录分析产物。不执行产品修改、数据库写入、真实外部联调或生产操作。
- 结论边界：源码已有能力标 `FACT`；“未发现完整能力”及建设建议标 `INFERRED`；运行可用性、生产规模和本次测试结果标 `NEEDS_VERIFICATION`。未查询实时数据库、未重跑测试或远程CI。
- 已确认产品方向（用户，2026-09-23）：长期建设可供多个项目复用的会员、商品、营销能力中台，同时包含品牌自营商城＋会员运营。自营商城是实际业务应用，不仅是演示验证壳；第二接入项目及具体业务政策仍待设计。

## Exploration Summary

当前是**具备本地交易闭环、部分营销治理和一致性机制的业务基础版本**，尚不能按“完整企业级经营平台”评价。此前S0–S10完成表示批准切片已交付，不表示所有企业业务能力都已覆盖。会员和商品主要处在BASIC，营销处在PARTIAL；工程机制的完整程度高于业务功能广度。

产品定位是“共享能力中台＋品牌自营商城应用”。会员、商品、营销负责可复用业务能力；自营商城承载消费者购物、订单、支付、配送、售后及品牌运营。建设同时补齐共享主数据与实际购物闭环，再深化会员营销，最终由第二独立应用验证复用。独立商家入驻、佣金结算和完整仓内作业仍按需建设。

## 中台方向下的候选调整

- **前移**：P03开放接入契约提升为P1，与S01应用/租户/资源权限、M01身份映射、C01主档与售卖信息边界一起进入第一阶段。先形成可验证契约，真实系统联调继续遵循后置安排。
- **核心建设**：M01/M02/M04、C01/C02/C03、K01/K02/K03/K04/K06形成会员、商品、营销主线；M03积分保留在长期能力图中，具体积分政策确认后实施。
- **自营商城主线**：T01购物车/地址/配送计价、T02基础履约售后与T05支付退款/对账共同支撑实际经营。基础能力建设前移，真实联调依旧后置；T02的多包裹/换货等扩展按场景选择。
- **继续按需**：T03完整仓内作业、T04独立商家入驻和结算不因自营多店自动纳入；保留已有库存和交易闭环。
- **治理贯穿**：S02审计审批、S03数据治理、K07奖励防滥用、O01可观测与恢复；不因选择中台而降低真实副作用门槛。
- **复用验收**：P03必须包含第二独立应用接入、共享授权/隔离、外部标识不误合并、事件重复/乱序和版本兼容。

## 优先级和估算口径

P0（条件）表示在进入真实业务/多商家开放/生产运行前必须补齐，不表示当前演示已发生P0事故。P1是本定位下优先的业务基础；P2是需求明确后的增强；P3是证据不足的探索。复杂度为相对规模且有不确定性，未给人日、费用或交付承诺。

共28个能力候选，**不是28个可直接编码的小任务**。跨子系统项已注明子成果，选定后仍需需求和实施切片。候选状态默认RECOMMENDED（条件成立时）；EXPLORATION为NEEDS_MORE_EVIDENCE。不把建议写成已批准计划。

## Must Fix

### K07 领券/发奖滥用防护

- **分类/优先级/状态**：MUST_FIX / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：已有每版本单券、配额、幂等、租户校验；未发现跨账号业务风控和操作频率限制。幂等只防重复命令，不能阻止不同身份或不同键合法重复薅取奖励。
- **Evidence / Why Needed**：[E11](EVIDENCE_INDEX.md)、[E12](EVIDENCE_INDEX.md)、[E16](EVIDENCE_INDEX.md)、[E27](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：按风险加入账号/活动频率、参与限额、异常处置、业务黑名单及申诉审计。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中；误杀真实会员；不得仅凭IP判定身份；不宣称已有确定漏洞。
- **Dependency**：S01、明确奖励规则及损失容忍；设备信号按权限必要性取用。
- **Trigger Condition**：公开领券/激励或发放有真实成本的权益之前。
- **Observable Acceptance**：不同幂等键仍受业务限额；拒绝原因和人工复核可见；正常用户不被全局配额误伤。

### S01 真实身份与商家/门店数据权限

- **分类/优先级/状态**：MUST_FIX / P0（条件） / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：可信租户+ADMIN/MEMBER；无商家/门店身份范围；演示摘要Bearer。当前租户隔离不能替代同租户多商家隔离，不能直接分发ADMIN给商家。
- **Evidence / Why Needed**：[E16](EVIDENCE_INDEX.md)、[E17](EVIDENCE_INDEX.md)、[E18](EVIDENCE_INDEX.md)、[E19](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：真实身份生命周期、权限角色、商家/门店数据范围、客服/运营/财务职责；租户开通及成员管理按平台定位。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大；权限扩大和撤销不及时；不可把前端菜单控制当授权。
- **Dependency**：确认租户与商家的关系、身份平台和职责矩阵；外部联调仍后置。
- **Trigger Condition**：对外开放真实账号或同租户多个商家独立运营之前。
- **Observable Acceptance**：商家A不能访问B的订单/商品/权益；客服不能审批资金操作；撤权后服务端拒绝。

### S03 数据保留、敏感信息访问与密钥恢复

- **分类/优先级/状态**：MUST_FIX / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：地址加密已有；保留/删除传播、密钥轮换和授权解密未形成完整闭环。长期运营和真实发货缺少可执行的数据治理与恢复边界。
- **Evidence / Why Needed**：[E05](EVIDENCE_INDEX.md)、[E21](EVIDENCE_INDEX.md)、[E24](EVIDENCE_INDEX.md)、[E34](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：明确各类数据保留依据、注销处理、授权解密审计、密钥版本轮换与隔离恢复验证。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中至大；删除破坏账本或密钥丢失导致历史地址不可恢复。
- **Dependency**：业务/安全负责人确认期限与访问用途；不编造统一期限。
- **Trigger Condition**：存储真实个人资料、WMS取得地址或开始长期运营之前。
- **Observable Acceptance**：授权范围可验证；旧密文轮换后可读；恢复不复活应删除的在线数据。

### T05 真实渠道接入与差异对账

- **分类/优先级/状态**：MUST_FIX / P0（条件） / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：沙箱支付退款、未知状态查单和后台核对已有；无真实渠道账单导入与差错工单。本地订单支付成功不能作为真实收款证据；逐单查单不等于财务账单对账。
- **Evidence / Why Needed**：[E24](EVIDENCE_INDEX.md)、[E28](EVIDENCE_INDEX.md)、[E32](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：保留现有状态机，分步完成支付退款适配、验签查单；再做日账单核对、差异处理与修复审计。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大，拆渠道适配验收、账单核对两个子成果；回调验签/金额不匹配、超时重复扣款、渠道账和业务账不一致。
- **Dependency**：渠道、商户主体、凭据、S01及资金操作权限；不含实际付款授权。
- **Trigger Condition**：真实收款/退款前；依用户此前要求，联调仍保持后置。
- **Observable Acceptance**：重复/乱序/未知结果联调通过；每笔差异有状态、责任人和不可重复的处置。

### O01 业务指标、告警与故障恢复验收

- **分类/优先级/状态**：MUST_FIX / P0（条件） / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：日志/traceId/health、事件隔离列表已有；无本次生产指标和恢复实测证据。异常可能长时间无人发现；不能承诺生产可用性或恢复时间。
- **Evidence / Why Needed**：[E28](EVIDENCE_INDEX.md)、[E29](EVIDENCE_INDEX.md)、[E30](EVIDENCE_INDEX.md)、[E31](EVIDENCE_INDEX.md)、[E34](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：订单/支付未知/权益补偿/旅程积压告警和处置入口；明确SLO/RTO/RPO后做容量及隔离恢复演练。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中至大，拆业务监测、隔离恢复和容量验收；监控高基数、演练污染共享数据、只恢复DB却丢地址密钥。
- **Dependency**：环境授权、业务目标、真实负载分布；不触碰共享/生产恢复目标。
- **Trigger Condition**：无人值守真实运行或承诺生产服务之前。
- **Observable Acceptance**：故障注入触发可处置告警；隔离恢复可读业务数据并续跑任务；记录实测而非估算。

## Natural Evolution

### M01 会员档案与生命周期

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：BASIC：管理员创建、主体绑定、查询；状态有ACTIVE/FROZEN，但无对应管理迁移入口。运营无法正常维护会员；冻结仅有数据模型，不能形成受控业务操作。
- **Evidence / Why Needed**：[E01](EVIDENCE_INDEX.md)、[E02](EVIDENCE_INDEX.md)、[E05](EVIDENCE_INDEX.md)、[E26](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：可维护档案、联系方式验证、冻结/解冻、注销受理、状态历史；明确注销后的订单和账本保留边界。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中；冻结不能破坏历史查询；注销不能抹除仍需保留的业务事实。
- **Dependency**：身份归属和资料所有权确认；与S01协同。
- **Trigger Condition**：真实会员持续入驻、资料维护或客服介入。
- **Observable Acceptance**：修改可审计；冻结后拒绝新交易；本人能查询允许保留的历史；注销流程有明确终态。

### M02 等级、成长值与等级权益

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：BASIC：memberLevel是创建时传入的字符串；券/权益钱包已有，但不是等级成长体系。无法根据消费持续运营会员，等级事实依赖人工输入。
- **Evidence / Why Needed**：[E01](EVIDENCE_INDEX.md)、[E02](EVIDENCE_INDEX.md)、[E12](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：等级定义、成长规则、评定周期、升级/保级/降级、变更历史、等级权益绑定。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中至大；退款冲正和跨周期重评不能重复计入；等级权益不等同现金。
- **Dependency**：M01；确认支付、完成订单或售后期后的成长口径。
- **Trigger Condition**：业务确认要按消费或行为做等级运营。
- **Observable Acceptance**：同一交易重复到达只计一次成长；退款与跨期规则有例子；等级变化及权益可追溯。

### M03 积分账户与积分生命周期

- **分类/优先级/状态**：NATURAL_EVOLUTION / P2 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：ABSENT：未发现积分账户、积分来源批次和到期作业；现有内部权益units不能直接当积分。无法开展可核算的积分奖励和积分消费。
- **Evidence / Why Needed**：[E05](EVIDENCE_INDEX.md)、[E12](EVIDENCE_INDEX.md)、[E23](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：积分获取、冻结/解冻、抵扣/兑换、来源账本、到期、退款返还/冲正和人工调整审批。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大；重复奖励、过期积分返还和消费后退款产生损失；不引入储值现金语义。
- **Dependency**：M01、S03；确认有效期、退款和负余额政策。
- **Trigger Condition**：有明确积分计划、兑换政策和成本承担方。
- **Observable Acceptance**：余额与流水守恒；并发兑换不超额；到期与退款重试不重记。

### M04 会员标签、行为摘要与360视图

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：ABSENT：未发现标签定义、行为采集或累计消费特征；目前只读取等级与本单金额。运营难以识别新客、复购客、沉睡客和高价值会员。
- **Evidence / Why Needed**：[E01](EVIDENCE_INDEX.md)、[E06](EVIDENCE_INDEX.md)、[E10](EVIDENCE_INDEX.md)、[E13](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：先形成受治理标签和订单行为摘要，再汇总会员交易、券、权益、旅程、售后视图。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中至大；退款后指标口径错误；过时画像不能承担强一致资格判断。
- **Dependency**：M01；标签口径、更新时间、来源权限明确。
- **Trigger Condition**：需要按历史行为做分群或客服需要会员全景。
- **Observable Acceptance**：标签能回溯来源和时间；订单退款后累计指标按口径修正；只展示授权范围。

### C01 商品主数据与SPU/SKU规格体系

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：BASIC：SKU只有店铺、标题、单价、版本和状态。无法完整表达多规格、分类检索和可展示的商品内容。
- **Evidence / Why Needed**：[E03](EVIDENCE_INDEX.md)、[E04](EVIDENCE_INDEX.md)、[E05](EVIDENCE_INDEX.md)、[E26](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：类目、品牌、属性、规格组合、商品与SKU关系、条码、图文媒体、销售单位和必要扩展属性。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大；规格变更不能重写历史SKU含义；避免无约束属性JSON。
- **Dependency**：业务确认商品类型、类目所有权和销售单位；媒体治理边界。
- **Trigger Condition**：真实商品目录上线或出现规格/类目管理需求。
- **Observable Acceptance**：同商品多规格独立定价与库存；类目属性可验证；历史订单名称与规格快照保留。

### C02 商品编辑、上下架和价格版本治理

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：BASIC：创建即ACTIVE且revision=1；未发现更新、审核、上架/下架及调价用例。运营无法正常修订价格、纠错商品或停止新销售。
- **Evidence / Why Needed**：[E03](EVIDENCE_INDEX.md)、[E04](EVIDENCE_INDEX.md)、[E10](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：草稿编辑、审核、发布/下架、变更历史、基础价格生效时间；明确在途报价兼容规则。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中；下架后旧报价是否可成交需明确，不能随意改历史价格。
- **Dependency**：C01边界；复用现有报价/订单快照。
- **Trigger Condition**：商品和价格需要日常维护。
- **Observable Acceptance**：新报价读取当前发布价；旧成交快照不变；并发发布只有一个合法版本。

### C03 商品查找、筛选与有界批量维护

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：BASIC：按店铺与SKU游标列表；未发现商品搜索、类目筛选和批量导入结果管理。目录增长后运营难以找到和批量维护商品。
- **Evidence / Why Needed**：[E03](EVIDENCE_INDEX.md)、[E04](EVIDENCE_INDEX.md)、[E25](EVIDENCE_INDEX.md)、[E26](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：关键词/类目/状态筛选、商品详情、批量导入校验/错误报告/重试；多价格表另按需求启动。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中；重复导入和部分成功造成脏数据；搜索结果不可作为库存权威。
- **Dependency**：C01、C02；大批次时依赖P01。
- **Trigger Condition**：已有目录规模使逐条创建/首批列表不能满足工作。
- **Observable Acceptance**：筛选稳定分页；同文件重试不重复建SKU；逐行失败可修复且可追踪。

### K01 动态分群与人群刷新

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：上传固定memberIds，单批最多500；最长24小时的新鲜度窗口；非动态人群计算。人工名单难以持续执行新客、复购、沉睡唤醒等运营。
- **Evidence / Why Needed**：[E06](EVIDENCE_INDEX.md)、[E07](EVIDENCE_INDEX.md)、[E26](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：基于受治理标签/交易行为定义分群，预估规模、周期/事件刷新、版本血缘和过期策略。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大；重复/迟到事件与标签过期使人群不准确；不能直接取消现有限额。
- **Dependency**：M04；稳定事件/特征口径；明确快照与实时资格语义。
- **Trigger Condition**：活动需要持续按行为识别会员，或名单维护超过人工能力。
- **Observable Acceptance**：相同输入版本可解释同一名单；历史活动可回溯人群；刷新失败有可见陈旧状态。

### K02 促销范围与组合策略

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：单活动择优；满额固定减免/百分比封顶；一张券可叠加或择优。无法覆盖常见指定商品促销和多种优惠共存政策。
- **Evidence / Why Needed**：[E08](EVIDENCE_INDEX.md)、[E09](EVIDENCE_INDEX.md)、[E10](EVIDENCE_INDEX.md)、[E11](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：SKU/类目适用范围、排除品、阶梯门槛、活动互斥组/优先级；按确认需求扩展组合，先明确退款分摊。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大；组合爆炸、最优价解释困难、退款分摊不守恒。
- **Dependency**：C01；优惠口径、资方和售后规则。
- **Trigger Condition**：出现现有单活动加单券无法表达的真实活动。
- **Observable Acceptance**：给定活动组合有确定报价及排除原因；订单和部分退款每分金额可核对。

### K03 优惠券运营生命周期

- **分类/优先级/状态**：NATURAL_EVOLUTION / P2 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：店铺满减券、固定有效期、配额、每会员每版本一张及预占/退回已有。无法方便执行生日券、补偿券、批量召回和多次奖励等运营。
- **Evidence / Why Needed**：[E11](EVIDENCE_INDEX.md)、[E07](EVIDENCE_INDEX.md)、[E23](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：按人群定向发券、发券批次、领取窗口与使用窗口分离、相对有效期、领取次数策略、停发/撤销及影响审计。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中至大；已使用/预占券不能强制回收；批量失败不能重复发券。
- **Dependency**：M01、K01或明确名单、S03；大批量依赖P01。
- **Trigger Condition**：活动确认需要当前领取模式之外的发券方式。
- **Observable Acceptance**：可追踪每批每会员结果；取消批次不抹去已使用事实；重试不超配额。

### K04 事件触发与可恢复旅程扩展

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：已有持久DAG、等待/判断/权益/站内通知/结束；仅手工和支付触发。现有旅程无法直接覆盖常规获客、留存和召回场景。
- **Evidence / Why Needed**：[E13](EVIDENCE_INDEX.md)、[E14](EVIDENCE_INDEX.md)、[E28](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：按场景增加注册、生日、复购、弃购触发；重复入组/再入组、等待事件、退出条件和业务频控。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大；同会员多旅程竞争、重复事件重复激励、退款后继续触达。
- **Dependency**：M01/M04/K01中所需事实；T01支持弃购时。
- **Trigger Condition**：至少一个具体生命周期运营旅程获得业务确认。
- **Observable Acceptance**：事件重复仅产生允许的实例；全局退出与活动频控生效；暂停/恢复可解释。

### K05 多渠道触达与偏好治理

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：NOTIFY仅写journey_notification；未发现短信/邮件/Push渠道、退订和跨旅程频控。站内消息无法覆盖离站触达，也无法证明外部发送效果。
- **Evidence / Why Needed**：[E13](EVIDENCE_INDEX.md)、[E14](EVIDENCE_INDEX.md)、[E17](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：先选择一种真实渠道，建立模板、发送任务、回执、失败恢复、会员偏好/退订、静默时段和统一频控。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大；退订后仍发送、重试重复发送、已受理结果未知；外部发送需另行授权。
- **Dependency**：会员有效联系方式、业务确认的触达依据/偏好；S01；渠道合同。
- **Trigger Condition**：需要真实站外触达；此前只维护站内体验。
- **Observable Acceptance**：发送前执行偏好/频控；回执重复不重记；退订后拦截后续发送；未知结果可查询。

### K06 营销经营指标与效果归因

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：ABSENT：已有报价trace、预算用量和旅程状态，未发现曝光点击转化漏斗、ROI或实验归因模型。能执行活动但难以判断净收益、复购改善及浪费。
- **Evidence / Why Needed**：[E09](EVIDENCE_INDEX.md)、[E10](EVIDENCE_INDEX.md)、[E11](EVIDENCE_INDEX.md)、[E13](EVIDENCE_INDEX.md)、[E15](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：先定义活动参与/领用券/支付/退款/成本口径及汇总；再按采集条件建设触达转化链和归因窗口。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中至大；把相关交易都归因活动、漏扣退款、重复事件夸大效果。
- **Dependency**：稳定活动版本、交易/退款/成本事实；渠道指标依赖K05。
- **Trigger Condition**：运营需要比较活动或管理营销投入。
- **Observable Acceptance**：仪表盘指标可追溯订单和成本；退款回冲一致；缺采集数据明确展示未知。

### T01 购物车、地址簿与配送计价

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：前端内存购物袋、单店报价和下单地址；无持久购物车/地址簿/运费模型。刷新丢购物袋，重复填地址，实物订单难以完整计算应付。
- **Evidence / Why Needed**：[E10](EVIDENCE_INDEX.md)、[E21](EVIDENCE_INDEX.md)、[E25](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：持久购物车、地址簿、配送地区/方式、运费模板及报价分项；税费/发票按经营地区确认。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大；运费与优惠顺序、部分退款退运费规则不明确。
- **Dependency**：C01/C02、地址治理、配送政策；与当前支付/退款金额契约兼容。
- **Trigger Condition**：需要可持续使用的消费者购物流程或真实实物配送。
- **Observable Acceptance**：跨会话恢复购物车；运费计入应付与退款快照；不可配送地址在支付前拒绝。

### T02 包裹、逆向物流和售后运营

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：单订单物流号、发货/签收、按行部分退货退款已有。真实履约异常和复杂售后仍需系统外处理。
- **Evidence / Why Needed**：[E22](EVIDENCE_INDEX.md)、[E23](EVIDENCE_INDEX.md)、[E24](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：按实际需求补包裹拆分、物流轨迹/异常、退货单号、售后证据、处理时限和客服沟通；换货另立完整流程。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大；发货退款竞争、重复收货和包裹汇总状态冲突。
- **Dependency**：S01/S03、WMS/物流契约；不要遗漏已有部分退款能力。
- **Trigger Condition**：真实物流接入；部分发货或售后争议成为实际需求。
- **Observable Acceptance**：多包裹状态可追溯；重复物流回执幂等；退款与真实退货数量一致。

### T03 多仓库存与作业协同

- **分类/优先级/状态**：NATURAL_EVOLUTION / P2 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：店铺SKU可售额度已有；非实物仓储系统。仅可售额度不能承担仓内作业和多仓分配。
- **Evidence / Why Needed**：[E20](EVIDENCE_INDEX.md)、[E22](EVIDENCE_INDEX.md)、[E24](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：明确库存权威归属；必要时增加仓库维度、调拨/盘点/调整、库存流水、预警及WMS差异对账。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大；平台和WMS双重扣减、调拨途中的可售计算错误。
- **Dependency**：C01、库存所有权与单位；T02。
- **Trigger Condition**：确实有多仓/实物作业；若由WMS承担则优先只接契约和投影。
- **Observable Acceptance**：每笔调整有原因和流水；同一实物只有一个写入权威；差异可定位。

### T04 商家入驻、合同与结算

- **分类/优先级/状态**：NATURAL_EVOLUTION / P1（条件） / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：BASIC：商家名称、店铺归属；营销资方分摊已有，但无商家应付/结算账单。不能仅凭营销分摊快照给商家出具应收应付及结算。
- **Evidence / Why Needed**：[E18](EVIDENCE_INDEX.md)、[E19](EVIDENCE_INDEX.md)、[E10](EVIDENCE_INDEX.md)、[E23](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：多商家场景增加入驻审核、合同/费率/账户、佣金、结算周期、冻结款、账单及退款调整；分账按收款模式单独确认。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大，至少拆入驻、账单、出款三个子成果；退款跨账期、重复出款、把平台补贴等同结算。
- **Dependency**：S01、真实收款主体与合同、T05；依次入驻→账单→出款。
- **Trigger Condition**：平台实际管理独立商家和代收/结算；自营场景可延后。
- **Observable Acceptance**：账单可追溯交易/佣金/退款；跨期调整独立记账；出款重试不重复。

## Platformization Opportunities

### S02 审计查询与审批职责分离

- **分类/优先级/状态**：PLATFORMIZATION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：命令审计和活动/旅程/页面审批状态已有；审计字段少，角色统一ADMIN。能记录命令不等于运营可追责，也不等于真正的复核控制。
- **Evidence / Why Needed**：[E05](EVIDENCE_INDEX.md)、[E08](EVIDENCE_INDEX.md)、[E13](EVIDENCE_INDEX.md)、[E15](EVIDENCE_INDEX.md)、[E16](EVIDENCE_INDEX.md)、[E27](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：审计查询/导出、资源与变更摘要、敏感操作理由、审批人约束及必要的创建/审批职责分离。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中；记录敏感原文泄漏；公共审批改造误改现有状态机。
- **Dependency**：S01；先统一权限/审计契约，不预设通用BPM引擎。
- **Trigger Condition**：多运营人员协作、需要敏感变更复核；相似审批已存在三个模块。
- **Observable Acceptance**：可查谁在何时改了哪个资源；按政策阻止自审；审批记录对应发布版本。

### P01 可恢复批量运营任务

- **分类/优先级/状态**：PLATFORMIZATION / P2 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：旅程/事件有检查点；商品导入、人群刷新、批量发券尚无统一运营任务结果。大批运营操作没有可追踪部分失败与安全重试入口。
- **Evidence / Why Needed**：[E07](EVIDENCE_INDEX.md)、[E13](EVIDENCE_INDEX.md)、[E28](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：按首个真实批量场景抽取任务状态、进度、逐项结果、暂停/取消、重试、输入版本和租户配额。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中至大；取消被误解为撤销已提交效果；重试重复发奖。
- **Dependency**：C03/K01/K03中被选中的实际需求。
- **Trigger Condition**：至少两个已确认批量用例出现共性；首个用例先有界实现。
- **Observable Acceptance**：任务中断恢复无遗漏/重复效果；可查逐项结果；单租户任务不饿死其他租户。

### P02 规则模拟、解释与版本治理

- **分类/优先级/状态**：PLATFORMIZATION / P2 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：AST、规则资产版本、可视编辑、报价命中trace已有；未发现运营批量仿真与新旧版本比较。扩展规则前难以量化误命中与优惠成本变化。
- **Evidence / Why Needed**：[E06](EVIDENCE_INDEX.md)、[E09](EVIDENCE_INDEX.md)、[E35](EVIDENCE_INDEX.md)、[E37](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：可信事实目录、测试样例、历史样本回放、发布前效果差异、规则审批与回退适用边界。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：中；模拟误发权益、把历史回放当真实实验效果。
- **Dependency**：M04/C01中真正所需事实；脱敏样本和稳定版本。
- **Trigger Condition**：规则被营销/旅程共同维护，且变更风险超出人工样例检查。
- **Observable Acceptance**：仿真无业务副作用；输入版本和命中解释可重现；版本差异可审查。

### P03 外部业务接入契约与交付事件

- **分类/优先级/状态**：PLATFORMIZATION / P1 / RECOMMENDED（条件成立时）；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：PARTIAL：内部Java边界和登录态HTTP接口已有；未发现面向合作方的应用凭据、Webhook订阅和交付记录。当前接口不能直接等同可供第三方集成的开放平台。
- **Evidence / Why Needed**：[E16](EVIDENCE_INDEX.md)、[E17](EVIDENCE_INDEX.md)、[E24](EVIDENCE_INDEX.md)、[E28](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：按中台核心场景定义稳定接口/事件版本、接入应用授权、外部主体/商品/业务单据标识映射、幂等、配额和交付追踪；Webhook在消费方需要推送时补签名/重放。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：大；把内部事件直接外发泄漏数据；对方失败造成无限重试。
- **Dependency**：S01/S02；消费方合同；保留外部联调后置安排。
- **Trigger Condition**：长期多项目复用目标已由用户确认，契约与应用隔离规划现在前移；真实第二项目接入仍需选定项目与接口合同。
- **Observable Acceptance**：第二独立应用无需复制核心代码或直连表；共享能力按授权生效、非共享数据隔离；外部编号不误合并；重复/乱序不重发奖励；契约版本可共存。

## Exploration Opportunities

### X01 拼团、秒杀、邀请裂变、订阅/付费会员

- **分类/优先级/状态**：EXPLORATION / P3 / NEEDS_MORE_EVIDENCE；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：ABSENT：当前无相关业务状态机与退款/奖励规则。可能带来增长，也显著增加库存、资金和奖励的失败场景。
- **Evidence / Why Needed**：[E01](EVIDENCE_INDEX.md)、[E08](EVIDENCE_INDEX.md)、[E09](EVIDENCE_INDEX.md)、[E21](EVIDENCE_INDEX.md)、[E23](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：从一个有获客/留存目标的玩法开始，每种独立确认成团、超时、资格、成本与退款政策。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：每种中至大，不能算一个小功能；无需求堆玩法、补贴滥用、取消后奖励难追回。
- **Dependency**：C02、K02、K07、T05、K06中相关能力。
- **Trigger Condition**：明确玩法负责人、目标指标、投入上限和试点样本。
- **Observable Acceptance**：试点有基线、停用条件与成本核算；非法状态、超时退款、重复奖励有验收。

### X02 Drools接入或旧规则迁移

- **分类/优先级/状态**：EXPLORATION / P3 / NEEDS_MORE_EVIDENCE；confidence：INFERRED（建议），现状证据FACT。
- **Current State / Problem**：ABSENT：当前明确使用受限AST；旧规则未迁入，另有历史迁移门禁。更换引擎不自动获得会员画像、优惠组合、触达或归因能力。
- **Evidence / Why Needed**：[E06](EVIDENCE_INDEX.md)、[E09](EVIDENCE_INDEX.md)、[E35](EVIDENCE_INDEX.md)；上述边界使本项触发场景无法形成完整工作闭环。
- **Proposed Capability**：仅在现有规则表达受阻或必须兼容旧规则时评估引擎接入和规则语义验证。
- **Value**：业务价值是消除上述运营/接入阻碍；工程价值是把该场景的状态、来源、失败和恢复转为可验证契约。
- **Complexity / Risk**：未知至大，取决于存量规则语义；金额舍入、执行顺序和副作用语义变化；双引擎并存增加维护成本。
- **Dependency**：P02；旧规则证据/授权；不继承本次分析为迁移许可。
- **Trigger Condition**：有具体AST无法表达的规则清单，或批准的旧规则兼容任务。
- **Observable Acceptance**：新旧规则样本差异可解释；资源有界；引擎失效不默认放行。

## Not Recommended Now

| 项目 | 状态 | 当前不做的理由 | 重启条件 |
|---|---|---|---|
| 因“企业级”直接拆微服务/Kubernetes | NOT_RECOMMENDED_NOW | 已有模块化单体与同库事务；当前主要缺业务功能 | 独立团队/发布/扩容/故障隔离有真实收益证据 |
| 立即替换AST为Drools或复制旧引擎 | NOT_RECOMMENDED_NOW | 缺字段、人群、玩法和经营闭环，换引擎不自动补功能；旧迁移独立受控 | X02触发且语义回归证据充分 |
| 同时建设Kafka、RabbitMQ、Redis、ES | NOT_RECOMMENDED_NOW | 当前Outbox与MySQL可支撑已知流程，没有规模测量支持全套引入 | 具体查询/吞吐/重放/延迟问题被证实 |
| 先做CDP、数据湖、数仓、实时推荐 | NOT_RECOMMENDED_NOW | 行为口径、身份和基础数据尚不完整 | 稳定多源数据、分析需求、规模及延迟目标明确 |
| 一次补齐所有促销玩法 | NOT_RECOMMENDED_NOW | 每种有独立资金库存与退款状态，不是几个前端开关 | X01选择具体玩法且可衡量收益 |
| 先建万能低代码/BPM/规则平台 | NOT_RECOMMENDED_NOW | 已有受控页面与有限审批，不需要任意代码执行和全行业流程 | 多个真实业务确实需要共同扩展能力 |
| 直接建AI运营自动决策 | NOT_RECOMMENDED_NOW | 缺经营反馈和数据口径；资金/资格错误成本高 | 数据/权限/评测成熟，选可审阅且有业务收益的场景 |
| 自建完整WMS/ERP替代外部系统 | NOT_RECOMMENDED_NOW | 可售额度不等于仓内管理；同工作区其他仓库不代表已集成 | 所有权确认后仍有明确缺口且复用/接入不能满足 |
| 默认加入储值、多币种、跨境税务 | NOT_RECOMMENDED_NOW | 当前CNY且无这些业务要求；显著扩大责任与复杂度 | 明确经营范围、政策和支付/财务合同 |

评估结论：先选实际工作流补齐，保留现有幂等、快照、状态机、预算和补偿，避免通过替换架构重做已有能力。
