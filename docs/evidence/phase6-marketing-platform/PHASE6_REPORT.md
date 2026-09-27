# Phase 6 — Marketing Rule Engine & Benefit Platform Vertical Slice

**Phase:** 6
**Final Status:** `PHASE_6_COMPLETE_WITH_LIMITATIONS`

## Executive Summary

在现有订单活动上完成可执行纵向闭环：固定人群与规则版本 → 审批/发布 → 真实报价决策 → 订单预留 → 支付后内部 CREDIT 发放 → 持久营销执行与故障详情 → 原运行时恢复 → v2 发布和旧版回退。真实 MySQL、并发、双实例、隔离恢复、全量构建和受影响浏览器场景通过。当前交付范围是单活动单内部 CREDIT；细分运营员授权、大租户性能、外部权益和滚动混版本发布仍需后续验证或产品决策。

## Starting Baseline

从 `origin/main` `df955f1` 的干净工作树建立本地 `feat/phase6-marketing-platform`。修改前 `verify.sh`：应用 264 项 0 失败、5 配置跳过，架构 3/3。Phase 5 最终状态是 `PHASE_5_COMPLETE_WITH_LIMITATIONS`，其权益额度/幂等、Outbox/Inbox、支付和双实例保证作为本次基础，未重做运行时。

## Phase 5 Compatibility

新增 V41 表，不改 Phase 5 权益、订单、支付迁移历史。全量 `build.sh` 运行应用 271 项 0 失败、5 跳过，架构 3/3，包含 Phase 2–5 原回归。旧无 policy 活动和旧订单无营销执行记录时仍走原路径；新消费者遇到旧订单权益完成事件会直接返回。未运行生产混合版本节点或外部支付/WMS。

## Current Marketing Inventory

已有活动、预算、规则资产、固定/动态人群、报价、券、积分、旅程、定向发券和经营效果；CPS 返现、赠品、红包、兑换码无本仓库可执行提供者。详细入口、规则位置、状态、版本和风险见 `01-current-marketing-inventory.md`、`02-capability-map.md`。

## Target Marketing Architecture

活动定义仍由 `marketing_campaign` 拥有；规则资产和人群快照各有版本；内部 CREDIT 定义/额度/授予由 benefit 拥有；订单服务只编排真实参与；`marketing_execution` 只存参与、固定版本与有限结果。纯 `marketing` 规则器不依赖订单、权益或 Web；架构编译依赖测试通过。同步活动决策与长运行旅程保持独立。

## Activity Model

受治理版本按 `DRAFT → IN_REVIEW → APPROVED → PUBLISHED → PAUSED`，驳回为 `REJECTED`；状态可变，业务内容版本不可原位更改。数据库唯一键与活动行锁保证每租户每活动至多一个当前发布版本。当前活动重叠使用已存在的“最高优惠、活动 ID 破平局”单选策略。

## Rule Model

有界 `RuleNode` AST 支持可信字段比较、ALL/ANY/NOT、金额门槛、固定额/比例/阶梯、SKU 范围和有效期；同级重复分支及未知字段/节点拒绝。规则矩阵见 `04-rule-model.md`。没有引入 Drools/DRL、无界表达式或额外基础设施。

## Rule Evaluation

可信会员/订单事实先准备，评价时间显式传入；`MarketingDecisionService` 返回稳定原因 Trace、唯一选中活动和精确金额。相同事实/版本/时间的确定性、金额分摊和不可变结果由领域测试证明；规则器不授予权益。缺失事实为 UNKNOWN，人群失效不被 NOT 变为命中。详见 `05-rule-evaluation.md`。

## Audience Model

代表活动绑定 `(tenant,audienceId,version)` 固定名单快照。发布检查新鲜度，报价以显式时间批量判定 HIT/MISS/UNKNOWN，所有查询带租户；旧活动继续引用旧快照，不暗换最新版本。当前快照上限 500 人，详见 `06-audience-model.md`。

## Benefit Model

本切片只使用内部 CREDIT v1。规则决定是否选中活动和权益引用；订单/权益服务预留，支付后现有事件消费者使 grant 可用并写账本，新增可用事实事件只更新营销结果投影。券和积分保留原业务服务，不声称已成为通用活动 grant provider。权益矩阵见 `07-benefit-model.md`。

## Quota / Idempotency

身份为 `(tenant,orderId,campaignId)`；营销表主键、订单命令幂等、`benefit_grant` 订单唯一键、数据库额度条件更新和 CHECK、Inbox 共同防重复与超发。最后一份额度双请求结果 200/409，只一条 grant 和营销参与。配额失败会回滚订单/库存/预算与执行，不把规则命中伪装成授予。

## Publication / Versioning

发布事务复核活动时间、审批状态、规则 AST 与固定规则资产、人群新鲜度、权益绑定、预算/价格参数；陈旧乐观版本失败。v1/v2/回退 v1 的执行记录分别保存 1/2/1 及原优惠金额。详见 `08-publication-versioning.md`。

## Preview / Simulation

草稿预览使用生产决策器和真实可信事实，不写报价、参与或权益预留；集成测试检查重复预览结果及执行/额度/发放均未写入。指定历史预览时间只控制规则时间，不回溯会员画像，接口消息已提示。

## Rollback Semantics

旧版重新发布只改变未来报价候选，历史报价、执行和已发 CREDIT 不重算或自动撤销。业务退款或权益补偿须走原状态机；代码回退保留 V41 数据，不等于业务恢复。

## Execution Model

V41 带完整表/字段注释和约束。订单事务写 `RESERVED`，支付写 `GRANT_REQUESTED`（纯优惠为 `APPLIED`），取消写 `RELEASED`，真实 CREDIT 可用事件经 Inbox 投影为 `GRANTED`。隔离时详情从原运行时派生 `GRANT_FAILED`，恢复后以 grant 权威状态显示 `GRANTED`；表不复制第二套失败队列。执行矩阵见 `09-execution-model.md`。

## Vertical Slice

`PersistedCommerceTest.marketingExecutionTracksRealOrderGrantAndTenantBoundary` 验证真实订单配置、报价、版本与人群来源、预留、付款、事件发放、执行查询和单条账本。故障/版本/回退证据见 `10-vertical-slice.md`，QA 的 AC01–AC09 映射见 `docs/delivery/phase6-marketing-platform/QA.md`。

## Migration / Compatibility

V41 向前追加，不改旧表内容及已执行 Flyway；测试库验证 41 个迁移。现有规则器未被替换，因此没有伪造“旧 evaluator 对新 evaluator”差分；用原营销领域和历史报价回归保持原语义。生产混合版本消费者处理新增事件的窗口未经实测，发布时须先核对全部节点/事件消费者与迁移顺序。详见 `12-migration-compatibility.md`。

## Concurrency Results

重复订单命令不产生第二条执行；最后一份额度竞争 200/409；同版并发发布 200/409 且只一个生效版本；陈旧审批 `expectedVersion` 拒绝。`CountDownLatch` 并发测试和数据库约束见 `11-concurrency.md`。

## Multi-Instance Results

两个真实 Spring 应用上下文共用隔离 MySQL 同时泵送，权益账本仅一条 `GRANT`、营销执行仅一条且最终 `GRANTED`。投影消费者声明不可历史重放；正常投递/隔离恢复仍具幂等边界。

## Performance Results

MySQL 8.4 `EXPLAIN ANALYZE` 显示活动候选走 `(tenant,store,status)` 索引，人群成员主键点查；单行样本候选约 0.004 ms、人群点查约 0.005 ms、执行列表约 0.025 ms，后者在小样本选 grant 索引并排序。事实准备批量加载人群，无逐规则数据库调用，候选超过 100 拒绝。测试库跨租户约 1,960 活动、单租户最多 2 条；未测大租户各阶段 p95、配额/发放延迟或峰值吞吐，不作容量认证。详见 `13-observability.md`。

## Security / Authorization

活动查看、草稿编辑、审批、发布、暂停、预览、执行查看分别命名为 `MARKETING_*` 能力，现有角色映射只给租户 `ADMIN`；运行时恢复另有独立能力。执行服务层校验能力，所有 Mapper 和事件诊断读取以可信 Actor 租户过滤；跨租户读 404、会员读 403。细分运营员角色需后续授权政策。

## Audit / Observability

原 `Commands` 审计发布/审批/暂停与操作者；执行行保留报价、活动、规则、人群、权益版本和有限原因，详情关联 grant 与事件失败分类，不返回内部错误全文。原事件积压/隔离低基数指标及恢复 API 用于故障运营；逐笔步骤见 `13-observability.md`。没有新增高基数活动/会员指标或未经真实流量校准的营销告警阈值。

## Mutation / Negative Tests

过期人群发布、重复规则分支、未发布规则引用、未知可信字段、跳审/陈旧版本、跨租户人群/执行、额度竞争、隔离与重复投递均有可执行负例。首轮新增消费者错误声明历史可重放，被 `ReplayTest` 拦下，修正后全量通过；没有临时改写生产源码做字节级变异。详见 `14-regression.md`。

## Regression Results

最终 `./scripts/build.sh` 退出码 0；应用 271 run / 0 failure / 5 configured skips，架构 3/3；UI 完整打包并检查 jar。第一次构建仅有测试预期 400/409 写错，修正后干净重跑通过。所有受影响旧业务和 Phase 2–5 回归在同一 Maven 套件。CI workflow 已覆盖构建与 Playwright，无需无行为改动。

## Browser Results

从最终干净 jar 启动隔离服务，新租户运行受影响两条 Playwright：真实订单/沙箱收款/履约/售后/权益冲正、规则与低代码预览审批发布，**2/2 通过**。Phase 5 全套基线四项其他页面失败属于 `KNOWN_EXISTING`，本次未重跑，不宣称修复。无新 Phase 6 浏览器回归。

## Known Limitations

当前只证明单活动、单内部 CREDIT；活动列表单租户小样本、执行列表排序与真实大租户性能未认证；运营能力虽分名但 ADMIN 仍同时拥有；新增完成事件在新旧节点混合滚动期间的路由未实测。详情按权益权威可显示授予/隔离结果，即使事件投影延迟；退款后的可用余额仍以权益服务为准。

## Blocked / Deferred Product Decisions

多权益礼包的全有或部分成功、现金样权益提供者及外部补偿、跨活动叠加/专属优先级、细分运营员角色、巨量动态人群与专属营销 SLO 阈值需独立决策；它们不阻塞现有单 CREDIT 闭环。

## Changed Files

生产实现集中于 `marketing-runtime` 的发布/执行、`benefit` 的订单 grant 读取与可用事件、`order-runtime` 的真实订单触发、`platform-runtime` 的受限事件诊断和能力、`commerce-app` 的 V41 与查询控制器。测试在 `PersistedCommerceTest`/`OrderExpiryLaneTest`；文档在本目录、`docs/delivery/phase6-marketing-platform/`、`docs/doc-map.md` 与根 `CODEX_PROGRESS.md`。未改前端业务源码或部署配置。

## Evidence Location

本目录 `00-baseline.md` 至 `14-regression.md`、三张矩阵、可复跑浏览器脚本及本报告；计划、Review、QA 和交付状态位于 `docs/delivery/phase6-marketing-platform/`。原始运行日志留在 `/tmp/phase6-final-build.log` 与忽略的 `.local/phase6-browser/`，不放入源码交付。

## Commit Boundary Recommendation

本报告形成时改动仍未提交、未推送。用户随后明确授权将本任务合并并推送到远程 `main`；按完整可构建单元审查生产实现与测试，再提交文档/证据。实际 Git 与远程 CI 结果以交付回合核对为准，不创建 tag 或部署。

## Next Recommended Phase

先在独立任务用代表性大租户数据测候选、事实准备、规则、额度与发放各段延迟，并验证滚动新旧节点事件兼容；再按产品决定是否扩展第二权益提供者、细分运营员和活动叠加/礼包策略。
