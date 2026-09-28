# Phase 8 执行计划

## 目标与授权

从已推送远程 main `aa8bef17343c9722b3d2f19a4f9d9a006d694ae1` 开始，强化既有 durable Journey，以真实支付事件→固定发布版本→持久WAIT→当前事实DECIDE→单路径分支→既有权益→持久完成，证明 JRN1–20。阶段报告不得仅因模型/API存在写完成。

用户已明确授权 Phase 7 提交/推送并开启 Phase 8；Phase 7 已交付。本次 Phase 8 新分支建立、基线与能力盘点已经执行。用户 AGENTS.md 要求「先输出简短执行计划」「计划确认后，连续执行所有步骤」；用户已回复「确认」，本计划进入执行，之后持续执行，不逐阶段等待继续。Phase 8 原文禁止未经明确指示的commit/push：阶段8 Git交付单独核对：用户最新AGENTS.md第8条明确持续授权开发任务验证后正常合并推送main，该用户授权满足原文explicitly instructed条件。Phase 7推送授权不自动混入其他改动。无生产部署。

## 事实、方案和边界

- F1：Journey已经存在，有32节点DAG、最长30天、固定版本、短事务、同库权益API、共享公平车道和恢复审计。详见能力盘点。
- F2：历史只保留当前节点和聚合effect，不能可靠还原每次分支/重试。
- F3：journeys车道backlog supplier为null；大未来等待群、热点租户和跨车道新组合缺最新专项证据。
- D1（已按确认计划固化）：扩展既有Journey，不新建调度器、规则器、权益子系统或新服务/中间件。
- D2（已按确认计划固化）：沿用版本内业务触发唯一入组和已有可选频控；新版本只影响新入组，pause定义只停新入组，publish恢复；不自动迁移既有实例。
- D3（已按确认计划固化）：CREDIT固定版本作为首个真实动作，券/站内信回归保留；逐步历史仅记录标识、类型、时间、决策、分类、结果引用，不存完整敏感事实快照。
- D4（已按确认计划固化）：效果与transition仍同库短事务；失败证据在回滚后独立受版本保护事务保存；不引入无必要租约。
- D5（已按确认计划固化）：不支持 full replay、in-flight migration、fan-out、事件等待、任意脚本/webhook、补偿反转。
- A1：仓库无真实生产峰值/SLO，本地容量数据只认证实测样本；100/1000/10000 due及有界未来等待样本先行，不宣称百万计时器已验证。
- R1：schema扩展与逐步trace期间旧节点可能继续执行旧逻辑，必须验证兼容并明确trace能力门禁/观察边界，不能将旧节点产生的历史伪造为完整trace。
- R2：原旅程历史偶发失败仍需专项稳定性验证；不把重新全套通过当根因已定位。

## 有序阶段与验收

| 阶段 | 工作/Owner | 前置 | 可观察验收 | 当前 |
|---|---|---|---|---|
| P8.0 | 基线 / public-engineering-workflow | Phase7远程完成 | main SHA一致、clean回归、最终jar/受影响浏览器、旧证据 | DONE |
| P8.1 | 有限能力盘点 / public-engineering-workflow | P8.0 | 定义/版本/图/触发/实例/WAIT/规则/动作/车道/恢复/安全/UI矩阵；真实场景 | DONE |
| P8.2 | 模型/架构/契约 / backend-architecture-design | 一次计划确认 | StepExecution所有权、状态机、版本/重入语义、兼容及失败矩阵；架构一致性审查 | DONE |
| P8.3 | 图校验/发布/无副作用预览 / backend-implementation | P8.2门禁与正式切片 | 图负例有稳定码；发布重检；预览复用决策，WAIT后标未来依赖，不发权益 | DONE |
| P8.4–8.7 | trace/WAIT/分支/动作纵向切片 / backend-implementation | P8.3 | 增量迁移；每步持久证据；真实订单支付true/false完整链、历史版本可解释 | DONE |
| P8.8 | Runtime/恢复治理 / backend-implementation | 有界模型 | 原车道积压统计、FailureClass/RetryPolicy/审计、旧检查点重试、授权/取消 | DONE |
| P8.9–8.10 | 崩溃/重启/双实例 / implementation-validation | 纵向路径 | J3/4/8/9/10/11/12/13/14/15；真实进程WAIT重启和kill；效果唯一 | DONE |
| P8.11–8.12 | 积压/容量/运维 / implementation-validation | P8.8–10 | EXPLAIN、future等待群、100/1000/10000 due、热点/小租户及混合车道、低基数指标/手册 | DONE |
| P8.13 | 回归/收口 / implementation-validation + project-documentation | 必需验收全通过 | 全后端/架构/安全、J1–18、负例/mutation恢复、受影响浏览器、clean包、证据/最终报告 | DONE |

正式 IMPLEMENTATION_SLICES 已在架构、契约与一致性门禁通过后生成；以上为执行顺序，不冒充已批准接口或已完成验证。无并行Agent安排，不擅自复制第二套工程流程。

## 执行与停止

一次确认计划后逐片完成实现→验证→审查→进度，不请求重复继续。真实业务/安全决策或外部环境阻塞才暂停受影响片，独立工作继续。用户已确认，本地全部实施与验收已完成，现进入已授权Git/远程CI交付；没有产品/技术阻塞。

证据目录 `docs/evidence/phase8-marketing-journey/`，当前进度 `DELIVERY_STATUS.md` + 根 CODEX_PROGRESS.md。最终严格使用原文完成标准与报告章节；未实测不得填通过。
