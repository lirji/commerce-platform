# Codex Progress

## 任务目标

按用户提供的 Phase 6 文档，在现有订单活动、规则、人群与内部 CREDIT 权益上构建可配置、可发布、可追溯、可恢复的营销纵向闭环，并用真实 MySQL、并发、版本与回归证据验收。

## 当前状态

本地分支 `feat/phase6-marketing-platform` 基于 `origin/main` 的 `df955f1`。用户回复“确认”通过设计 Gate A，Phase 6 已完成，严格状态为 `PHASE_6_COMPLETE_WITH_LIMITATIONS`。权威计划、状态和最终报告分别为 `docs/delivery/phase6-marketing-platform/DELIVERY_PLAN.md`、`DELIVERY_STATUS.md` 和 `docs/evidence/phase6-marketing-platform/PHASE6_REPORT.md`。原 Phase 6 文档禁止提交/推送；用户在后续回合明确要求“推送合并到远程main分支”，已授权本任务的正常提交、合并和推送。生产部署仍未授权。

## 已完成

- 阅读 Phase 5 最终报告并确认其 `PHASE_5_COMPLETE_WITH_LIMITATIONS` 边界。
- 盘点活动、规则、人群、权益、订单与事件链，形成 Phase 6 当前能力清单和差距图。
- 执行 `./scripts/verify.sh` 基线：退出码 0、`BUILD SUCCESS`；`commerce-app` 264 项无失败、5 项配置跳过，架构测试 3 项通过。
- 写出代表订单活动的交付设计、验收条件、实施切片和回退边界。
- 新增 V41 营销执行表及查询接口；订单事务写参与、支付/取消状态推进，权益可用事件通过原 Outbox/Inbox 投影结果，隔离失败通过原运行时证据展示与恢复。
- 发布时重新校验固定规则、人群、权益与有效期；规则树拒绝重复同级分支；活动/执行使用分别命名的能力检查。
- 新增真实 MySQL 用例：订单发放、租户隔离、版本回退、故障恢复、两实例发放、并发发布、草稿预览和发布负例。第二轮全量 `./scripts/verify.sh` 在追加最后两个负例前通过：269 项，0 失败，5 跳过；架构 3/3。
- 第一次干净 `./scripts/build.sh` 在 `publicationRechecksAudienceAndRejectsDuplicateRuleBranches` 的断言上失败：现有 API 对过期人群返回 409，测试误期望 400；已修正并干净重跑通过：应用 271 run/0 failure/5 skipped、架构 3/3，前端打包进 jar。
- 最终 jar 的受影响浏览器 2/2 通过；完整证据 00–14、Review、QA、运行手册和 Phase 6 报告已完成。

## 已修改文件

- `docs/delivery/phase6-marketing-platform/DELIVERY_PLAN.md`
- `docs/delivery/phase6-marketing-platform/DELIVERY_STATUS.md`
- `docs/evidence/phase6-marketing-platform/`（00–14、最终报告及浏览器复跑脚本）
- `docs/delivery/phase6-marketing-platform/REVIEW.md`、`QA.md`
- `commerce-app/src/main/resources/db/migration/V41__marketing_execution.sql`、`commerce-app/src/main/java/com/lrj/commerce/app/http/marketing/execution/` 与集成测试
- `marketing-runtime/src/main/java/com/lrj/commerce/campaign/execution/`、`marketing-runtime/src/main/resources/mappers/campaign/CampaignExecutionMapper.xml`、`CampaignService.java`、`RuleNode.java`
- `benefit` 的权益 API/服务/Mapper、`order-runtime` 的订单服务与模块依赖、`platform-runtime` 的 Actor/EventInspection/EventMapper
- `docs/doc-map.md`
- `CODEX_PROGRESS.md`

## 未完成

- 本轮批准的 Phase 6 工作无剩余实施项。生产混合版本部署验证、大租户容量、细分运营员授权、多权益礼包及外部提供者属于后续独立任务。

## 当前问题

- Git 交付正在执行；提交、任务分支推送和远程 main 合并尚待实际结果。Phase 5 全套浏览器四项原有失败本轮未重跑；只验受影响两项并通过。
- Phase 5 已知：外部支付/WMS 适配器及礼包原子性不在已认证范围；本次只选择单个内部 CREDIT 权益，不扩大承诺。

## 下一步建议

1. 按后续明确授权，审查暂存差异、分批提交 Phase 6，在不绕过保护的前提下合并并推送远程 main；核对远程结果及 CI 状态。
2. 后续独立任务优先测真实大租户分段延迟与新旧节点滚动事件兼容，再处理产品待定项。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md`、Phase 6 最终报告和当前 Git 状态。Phase 6 实现已完成；用户已明确授权本任务正常提交、合并并推送远程 main。若 Git 交付中断，从实际提交/远程状态恢复，不重做实现，不强推、不绕过保护，不部署。
