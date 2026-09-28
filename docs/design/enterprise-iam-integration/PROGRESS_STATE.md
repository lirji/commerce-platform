> **当前实施状态（2026-09-28）**：本文保留早期候选方案历史。企业IAM权威计划已迁入[auth 63节点DAG](https://github.com/lirji/auth-platform/tree/main/docs/design/oa-auth-unification)，P1完成、P2实现和本地验收完成，两仓已正常推送main；最终CI与暂停状态见auth阶段报告。商城P2-06真实只读接入见[验收记录](../../implementation/oa-auth/phase-2/P2-06_TEST_RESULT.md)。原IAM候选编号的TODO不代表当前P1/P2未完成；本轮按用户要求P2后暂停，不自动执行P3。

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
