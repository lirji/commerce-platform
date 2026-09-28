# Codex Progress

## 任务目标

基于现有 auth-platform、oa-platform、commerce-platform，交付企业统一身份与权限的完整接入和执行计划，覆盖内部人员跨应用角色／数据范围、外部合作方、消费者、服务主体、接入协议、兼容迁移、验收与回退。本轮只设计，不实现、改数据库、操作真实账号或部署。

用户 AGENTS Git 规则 8 已授权当前文档按独立分支、必要验证、正常合并与推送 main；无生产部署授权。

## 已完成

- 读取并使用 Claude 入口后端架构、边界评估与架构演进规划技能；定向核对三仓当前源码／Mapper／设计，没有重复全仓扫描或调用 Claude 模型。
- 已交付的能力复核与权限补充保持：afc5e94、f170e31 已在 main，.engineering/exploration 五报告不改，不重复交付历史任务。
- 形成 docs/design/enterprise-iam-integration 完整方案：企业／应用／环境、身份绑定与成员关系、治理角色与业务角色、逐动作范围、外部邀请／到期／复核、消费者本人策略、统一协议与 SDK。
- 规划 18 个候选切片（17 必选、1 可选物理收拢）、34 个 MUST 场景、7 个待确认决策和 12 项风险；每切片明确依赖、责任、交付、验收和回退。
- 实际只读架构 CLI 核对源码并 plan/replay PASS；物理迁移结果 REMEDIATION_PLAN、CUTOVER_BLOCKED，成本／运行／批准未知没有补分，不伪造可执行切换。
- 原 commerce 工作区同时出现前端设计与根进度改动，已保留；本任务转入独立 .local/enterprise-iam-plan-worktree，分支 feat/enterprise-iam-plan-delivery。
- OA 已有脏进度／DEPLOYMENT_RESULT／tmp 全部保持，auth 与 OA 没有本轮改动。
- 文档检查 PASS：九份产物、34 个链接／锚点、18 切片依赖无环且与正文一致、7 决策、34 MUST、12 风险和 15 条事实；规范性文件指纹记录在 TEST_RESULT。显式暂存十份本任务文档及补丁检查 PASS；Git 实际发布待下述步骤核对。

## 已修改文件

- docs/design/enterprise-iam-integration/README.md
- docs/design/enterprise-iam-integration/BACKEND_ARCHITECTURE.md
- docs/design/enterprise-iam-integration/CONTRACT_REQUIREMENTS.md
- docs/design/enterprise-iam-integration/EXECUTION_PLAN.md
- docs/design/enterprise-iam-integration/EXECUTION_DAG.json
- docs/design/enterprise-iam-integration/MIGRATION_AND_VALIDATION.md
- docs/design/enterprise-iam-integration/EVIDENCE_INDEX.md
- docs/design/enterprise-iam-integration/PROGRESS_STATE.md
- docs/design/enterprise-iam-integration/TEST_RESULT.md
- CODEX_PROGRESS.md（仅隔离任务工作树，原工作区并行任务的进度不覆盖）

## 未完成

- 本文件是计划文档交付检查点，必要验证结果以 TEST_RESULT.md 为准，最终提交／main／CI 以真实 Git 和 .local/enterprise-iam-plan-delivery.json 为准；恢复先核对再行动。
- IAM-00–16 均未实施；IAM-17 可选。后续实施需要用户明确要求并先确认对应业务决策／正式契约，不属于本轮计划交付的剩余实现。
- 登录联调、数据库迁移、授权变更、业务验证、压力、恢复演练和生产部署 NOT_RUN。

## 当前问题

- Q01–Q07：企业／集团隔离、同人退出、外部准入期限、岗位动作、资源继承、撤权与在途、运行／隐私指标，见 README；计划可交付，依赖这些决策的实施与切换不能假定已批准。
- 当前 OA 仍有员工依赖；auth 对象水位为单实例；商城固定角色需兼容。都是明确实施前置，未认定已发生线上越权。
- 原 commerce 前端视觉设计是并行任务，不 stage／提交／覆盖其文件；main 如出现同一根进度并发更新，应保留两项任务事实，不能强制覆盖。

## 下一步建议

1. 验证本任务九份文档、DAG／锚点／引用／验收与范围，显式暂存本任务十文件，按独立分支正常提交／发布。
2. 在干净 integration 工作树合入最新 main，正常推送；如并发 main 更新，仅解决本任务文档的可确定冲突，保留其他任务。
3. 观察本次 ref 的远程 CI；运行中如实记录，不用历史 PASS 代替。若已交付，不重复提交或推送。
4. 用户要求开始接入后，从 IAM-00 确认试点矩阵，再按 DAG 冻结一期契约；先商城商品运营链路，再订单读、外部／消费者、OA 跨应用。

## 重要上下文

本次源基线 commerce f170e317f12006d8559ac41d1858b00c42998890、auth c07741a、OA f07c978。保留所有旧 subject／actor／member 与订单、权益、幂等回执；禁止新运营角色通用映射 ADMIN，禁止 oldAllow OR newAllow。

Phase8 与原能力报告已经完成，不恢复历史 Git 交付或重跑规模测试。真实 IdP／支付／权益／WMS 生产联调和旧规则迁移为独立范围；不打印 .local 凭据，不派子 Agent。

## 恢复 Prompt

请读取本工作树 CODEX_PROGRESS.md、docs/design/enterprise-iam-integration/PROGRESS_STATE.md、TEST_RESULT.md 与 Git 实际状态及 .local/enterprise-iam-plan-delivery.json，继续本次计划文档交付尚未完成的检查／提交／正常 main 发布。已发布则不重复。只处理本任务十份文档，保护原工作区前端改动和兄弟仓库脏文件；不自动实现 IAM 或部署，不等待重复“继续”。
