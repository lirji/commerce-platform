> **CE05-CAM1 当前状态（2026-10-01）**：CE05-CAM1本地DONE：9独立活动/预算能力、6实际正内容版本动作、独立目录/预算与真实预览已接入；V64版本审计与活动/预算/状态/原回执同事务。11真实MySQL专项（含81权限HTTP边界/实际SQL故障/锁等待期限）、完整489项484PASS/5既有skip、9契约/28工具及两仓hygiene无阻断。最终10源码/SDK摘要与17嵌套模块/661类/510资源/45前端文件/复制JAR一致。真实47a2d78d80c5/子网118已exit0，645检查点PASS（79活动标签），SQL再次核验13身份审计/实际内容版本1:3、7:6、8:4，v7 PAUSED/lock6、v8 PUBLISHED/lock3，客户订单在STOPPED后正常释放v8预算。自有进程/PG已停止，数据证据保留。Git/CI待交付；CAM2和其余CE05—08/auth接入资源展示未完成，原8602/OA及生产2HOLD保持。 完整证据见 [auth活动记录](https://github.com/lirji/auth-platform/blob/main/docs/implementation/oa-auth/commerce-readiness/CE05_CAMPAIGNS.md)。下方保留历史。

> **CE05-A2 当前状态（2026-10-01）**：CE05-A2本地DONE：固定人群目录/创建两Tab与独立创建提示；8项真实MySQL和完整478项（473PASS/5既有skip）、最终前端build/forceCreation package通过。最终真实a5fd3867e690（10.254.117.0/24）647检查点PASS，人群11条浏览器检查及全部既有员工页回归通过；恰5条实际身份审计，UI两个快照为1:2和1:0，原键不重复、导入不创建客户。1440/390表单/目录、409/未知/退出确认/成功/401/503共11张截图已实际查看，正文390且表格内部横滚。两仓源码摘要、17嵌套模块/661类/45资源及复制JAR一致；262入口/122能力/34角色、9契约/28工具及两仓hygiene无阻断。首轮594后401夹具覆盖凭据失败已修正并保留。自有进程已停止；无新迁移，V49—V63不可改、SDK固定4747ac49，原8602/OA不切换。Git已交付authaa11746/commerce51c771b，精确CI36954769355/36954777665 SUCCESS；下一CE05-CAM活动/审批/预算细化，其余CE05—08及auth资源展示未完成，生产2HOLD不变。 完整证据见 [auth 人群验收](https://github.com/lirji/auth-platform/blob/main/docs/implementation/oa-auth/commerce-readiness/CE05_AUDIENCES.md)。下方保留历史。

> **P6历史状态（2026-09-29）**：用户选择完整CATALOG能力，所选商城运营租户的本地隔离演练31项通过；原运行商城未切换。最终验证/Git/CI在auth规范PROGRESS_STATE，生产HOLD/P7未执行。下方为P5及更早历史。

> **当前实施状态（2026-09-29）**：以下为早期候选计划历史。权威计划在[auth 63节点DAG](https://github.com/lirji/auth-platform/tree/main/docs/design/oa-auth-unification)。P3已交付；本轮P5商品查询/受控资料修改及外部门店合作限时导出已实现，通过真实MySQL/OA/浏览器验收；P507真实运行验收已完成，产品提交c7384fe已正常合并推送main，完整CI 36596109216通过。Q-EXT已确认门店/商家协作，不实现供应商订单。最终状态见[auth进度](https://github.com/lirji/auth-platform/blob/main/docs/design/oa-auth-unification/PROGRESS_STATE.md)和本仓phase-5测试报告；P6前停止，无生产部署。下方历史TODO不覆盖本段。

# 企业 IAM 整体接入计划进度

- task_id：IAM-PLAN-20260927；version：1。
- 当前任务：交付整体接入与执行计划；没有实现／迁移／部署任务授权。
- 计划状态：PROPOSED；文档交付切片：DOC-IAM-PLAN-01。
- 源基线与现有改动：[证据](EVIDENCE_INDEX.md)；候选依赖：[DAG](EXECUTION_DAG.json)。

## 已完成

- 定向复核三仓身份、权限、对象协议、数据过滤、工作区、进度与依赖。
- 使用 Claude 入口后端设计、边界评估与演进规划技能；只读规划器现场源核对与 replay PASS。
- 形成用户／成员关系、应用与环境、治理／业务角色、外部邀请／消费者、动作范围模型与失败恢复方案。
- 建立 18 个候选切片、34 个 MUST 验收场景、7 项业务决策、12 项风险与标准项目接入流程。
- 本轮仅有设计文档变更，实施与业务验收 NOT_RUN；文档验证以 [TEST_RESULT](TEST_RESULT.md) 为准。

## 交付与实施状态

| 对象 | 状态 | 说明 |
|---|---|---|
| DOC-IAM-PLAN-01 文档编写与验证 | DONE | 九份方案文件的必要文档检查通过；Git 发布实际结果以交付观察为准 |
| IAM-00–IAM-16 | TODO | 候选工作；需本计划所列业务／契约确认与实施任务授权；没有伪标 READY/DONE |
| IAM-17 | OPTIONAL_DEFERRED | 完成两应用接入后依实际收益评估，不阻塞统一治理与协议 |
| 正式物理迁移 | REMEDIATION_PLAN | 原始运行／成本、边界批准和迁移前置未知；只有基线／边界／契约／所有权／事务治理候选 |
| 生产切换 | CUTOVER_BLOCKED / NOT_EXECUTED | 未配置或验证目标、预算、批准与回退；没有生产部署授权 |

## 未决与下一步

Q01–Q07 见 [整体方案第 10 节](README.md#10-实施前需确认的业务决策)。这些不阻塞本轮计划文档交付，但在对应切片／切换前必须确认；最大范围、授权与撤权目标、运行容量、保留和恢复目标当前 UNKNOWN。

下一业务工作为 IAM-00：确认企业隔离、成员退出规则、合作方政策和商城试点角色动作范围矩阵，再按 DAG 冻结一期正式契约；用户明确要求实施后继续。当前不自动接入真实账号、改授权、部署或合并整个 auth／OA 仓库。

## 恢复规则

根 `CODEX_PROGRESS.md` 是当前任务摘要。本文件随文档提交保存的状态是检查点；实际提交、main 包含关系、远程 CI 和最终交付以 Git 及 `.local/enterprise-iam-plan-delivery.json` 观察为准，恢复时先核对，避免重复已完成发布。
