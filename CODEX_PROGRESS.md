# Codex Progress

## 任务目标

读取并使用Claude中的项目分析技能，基于阶段8后的真实仓库重新分析欠缺能力；按用户补充，明确区分页面/按钮/接口RBAC与接口业务数据范围权限。仅分析和更新报告，不实现候选功能。

用户AGENTS Git规则8持续授权本任务报告按独立分支提交、正常合并并推送远程main；没有生产部署授权。

## 已完成

- CE-20260927已交付初版报告提交afc5e940c1dd988759880ad9f03106e68170ad0e，origin/main包含该提交，任务分支feat/capability-gap-reassessment已发布。不要重复原交付。
- Claude入口project-capability-exploration及references/output-schema实际已读；discovery/deep-analysis/architecture-reviewer仅复用扫描方法。技能指向.cursor共享目录，没有调用Claude模型或修改全局技能。
- 原产品分析基线7dda31e；权限补充检查基于afc5e94开始，工作树干净，产品代码未改变。原静态清单19 Maven模块、205主Java、54测试Java、41 Mapper XML、45迁移。
- 复核App静态菜单和角色分支、运行开关接口、Actor固定角色/Capability、HTTP默认拒绝清单、credential单role、商品经营grant、会员/订单Service＋Mapper及负向测试源码。
- 已有基础角色/租户/会员本人过滤及商品商家/门店范围；可配置页面/按钮/接口RBAC和跨业务组织/门店/负责人范围未形成闭环。G05扩充RBAC，新增独立G16数据范围，未据此认定已发生越权漏洞。
- 同步五份探索报告：16项缺口、16个候选、41个证据索引；MF03权限、MF05数据范围、MF06奖励政策分别表达；权限在多岗位/同租户分权使用前为P1门槛。
- 初版报告同head CI：main36373088584 completed/success；分支36373067309 completed/failure，24项浏览器中1项标题等待失败、23项通过，原因未确认。均已保留记录，不继承为本次新ref的PASS。
- 本轮没有改生产代码、测试、配置、依赖、数据库，也没有重跑业务测试/压测或联调真实渠道；测试源码存在不等于本轮执行PASS。
- 文档必要检查PASS：五份产物、9维、16项缺口、16个完整候选、41个证据、本地链接/围栏/依赖图和git diff --check；只包含当前任务六份文档。

## 已修改文件

- .engineering/exploration/CAPABILITY_MAP.md
- .engineering/exploration/CAPABILITY_GAPS.md
- .engineering/exploration/OPPORTUNITIES.md
- .engineering/exploration/EVOLUTION_ROADMAP.md
- .engineering/exploration/EVIDENCE_INDEX.md
- CODEX_PROGRESS.md（update-progress-docs负责的当前摘要）

## 未完成

- 本文件为权限补充提交前检查点；本回合完成文档必要检查与正常Git交付。恢复先核对Git和.local/capability-gap-permissions-delivery.md的实际结果，远程main已包含补充提交则不要重复交付。
- 候选权限功能尚未实施，不属于本轮分析任务的未完工作。

## 当前问题

- 分工矩阵、资源归属与数据范围、撤权对已受理后台任务的影响尚待后续业务设计确认；不阻塞分析，也不允许自动选择策略。
- 原报告分支CI的浏览器标题等待失败原因未知，同head main通过；不是权限漏洞证据，不扩大本任务为无关产品修复。
- 第二应用/共享、配送/运费、奖励承诺、保留与SLO/RTO/RPO、值班告警目标仍待确认。

## 下一步建议

1. 检查仅上述六份文档变更、章节/候选模板/证据ID/本地链接/Mermaid与git diff --check，使用当前任务分支按完整逻辑提交。
2. 正常推分支，在干净.local/capability-gap-main-integration工作树合入最新main并推送；保留分支和工作树，不强推、不绕过保护。核实本次ref实际CI，运行中如实记录，不擅自重跑无关测试。
3. 若用户选定权限实施，先确认角色动作矩阵和逐资源归属/范围；选一条“动作＋数据范围”纵向链路验证页面、接口、列表/详情/写操作/任务及撤权。不仅做菜单隐藏，不预设技术框架，不要求先联调真实IdP。

## 重要上下文

本次是原能力缺口分析的补充，继续使用feat/capability-gap-reassessment。初版报告和Git交付已经完成；CODEX_PROGRESS是提交前检查点，最终提交/远程/CI以Git和.local交付观察为准。

Phase8已交付源码0b7d6f1、证据b389d65、独立CI修复bdc2af7、合并cac9810、checkpoint7dda31e；完整历史在docs/delivery/phase8-marketing-journey和docs/evidence/phase8-marketing-journey。不要恢复已完成的历史Git交付或重跑规模测试。

外部IdP/支付/权益/WMS按既有要求后置；旧规则迁移仍是独立BLOCKED工作。.local凭据不可打印/提交，不派子Agent。

## 恢复 Prompt

请读取CODEX_PROGRESS.md、Git实际状态及.local/capability-gap-permissions-delivery.md，从权限补充报告尚未完成的必要检查/Git交付继续。远程main已包含补充提交则记录实际CI后交付，不重复原afc5e94交付，不重新全面分析、不重跑产品测试、不开始RBAC或数据权限实施、不等待“继续”。只处理本任务六份文档，权限/不可确定冲突时记录具体阻塞。
