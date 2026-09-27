# Phase 7 Report

**Phase:** 7 — Marketing Platform Production Scale, Rolling Compatibility & Extensibility
**Final Status:** `PHASE_7_COMPLETE_WITH_LIMITATIONS`

## Executive Summary

五个工作流在明确的本地模型内完成：生产代码多层压测与查询优化、真实 OLD/NEW 共库、已有 COUPON 经同一营销流水线、确定性 BEST_OF、运行/回退手册。最终 clean 构建应用 279 项零失败、架构 3/3；最终 jar 受影响浏览器 2/2。没有扩大候选/公开人群上限，没有新增中间件、旅程或重复权益流水线。

该状态仅认证下述实测范围及门禁顺序，**不承诺生产容量或任意版本回退**。重要限制：OLD 会忽略 coupon 字段；必须先退出全部 OLD 再开启券配置或扩展 Trace，开启后默认回退目标仍需券能力。

## Starting Baseline

基线 main `ddf55026bf026f96cec8793556b6559bdc749b72`，工作树干净，任务分支 `feat/phase7-marketing-production`。Java 21.0.11、MySQL 8.4.11、Flyway V41；重跑 verify 应用 271/0/5 skips、架构 3/3。开始时浏览器基线引用 Phase 6 的 2/2，本轮最终实际复跑。详见 [00-baseline](00-baseline.md)。

## Phase 6 Compatibility

原活动不可变内容、固定规则/人群/权益版本、预留与发放分离、CREDIT 台账/事件、租户过滤和恢复入口保留。V41 原行不回填：历史非空 grant_id、空 benefit_type 解释为 CREDIT。原 G1–G8 成功/MISS/UNKNOWN/额度不足/重复/恢复/版本/回退用例在全套保留；新增券与冲突用例不替换旧用例。

## Production Scale Model

SMALL/MEDIUM/LARGE 分别 10/500/4,000 发布活动，其中 10/50/80 同时有效；公开快照 100/500/500、历史执行 1k/10k/50k。LARGE 另有 grant、event/Inbox 各 50k、50k 人群存储探针；规则 1/69 节点、人群引用 1/20。每层 quote 120 请求/8 并发、三租户 180/12；每提供者双进程 120 不同用户/12 并发争用 30 额度。这些是明确假设，仓库没有真实生产峰值。见 [01-scale-model](01-production-scale-model.md)。

## Performance Results

实测分开列出 SQL、quote 方法段、纯规则/选择、HTTP、额度/订单/执行持久化、付款发券和事件投影，含 P50/P95/P99。80 候选决策段 P95 1.388 ms；实际三租户 quote 约 228.2 请求/s。最终争用下单约 CREDIT 95.7、COUPON 94.7 请求/s（均含拒绝）。方法、队列等待与 HTTP 分位不可混加。完整数据见 [02-performance](02-performance-decomposition.md)、[results](results/)。

## Candidate Discovery

V42 增加有效窗口索引；候选 SQL 在 LIMIT 前过滤 tenant/store/status/有效期，4,000 历史活动只读 80 行。仍拒绝 >100 同时有效候选。新增 101 个过期发布活动回归防止历史数据挤占有效候选。

## Audience Scale

`KEEP_CURRENT_MODEL`：公开创建上限保留 500；数据库 CHECK 可至 100k，50k 存储点查 P95 0.653 ms 不证明公开导入/更新已支持该规模。批量去重人群版本查询一次，固定水位和有效期语义保持。见 [04-audience](04-audience-scale.md)。

## Query / Index Results

实际 EXPLAIN ANALYZE 覆盖候选、人群、执行、grant、钱包、event/Inbox、版本组和额度点查；额度 UPDATE 只 EXPLAIN，避免测量改变额度。除候选索引外未加推测索引。归一 MySQL digest 证明 10→81 候选、1→20 人群及 69 节点时每 quote SELECT 数保持 16；有人群时始终一次批量查询。版本组样本只有一版本，不夸称 50 版本容量。见 [03-query-analysis](03-query-scale-analysis.md)。

## Rolling Deployment Model

真实 OLD `df955f1`（pre-Phase6）与 NEW 共享 V43 MySQL 和事件表。OLD 产出订单/付款事件，NEW 推进订单 PAID、CREDIT AVAILABLE、GRANT 台账一条；新增 completion 事件 OLD 不领取，NEW 唯一投影。另以 OLD/最终 NEW 验证券配置字段被旧节点忽略的业务不兼容。最终 Trace 门禁关闭的 NEW 报价经 OLD 读取、OLD 下单、OLD 取消均 HTTP 200，赢家/金额保持；新枚举门禁开启后的历史回退限制单独记录。见 [05-rolling](05-rolling-compatibility.md)。

## Database Compatibility

OLD+V43 旧业务、NEW+V43 均实际运行。V42/V43 扩展 schema，旧代码仍可写原数据；NEW 必须先有新 migration。没有改已执行 migration、降级 Flyway、清理历史或虚构 schema 回退。见 [06-matrix](06-event-compatibility-matrix.md)。

## Event Compatibility

OLD→OLD 使用原 Phase 5 证据；OLD→NEW 实际 CREDIT 生产链；NEW completion→OLD 保留 PENDING/attempts=0；NEW→NEW 唯一 Inbox。无消费者保留是有期限的滚动窗口，必须有 NEW 负责排空，不以无限 PENDING 算成功。对象未知字段默认忽略、未知 enum/规则类型拒绝；未引入宽泛 catch 或更改 CREDIT 事件载荷。

## Deployment / Rollback Order

扩展 migration→NEW 消费者且券及扩展 Trace 开关关闭→OLD 全退→按需启用新能力生产→验证。实际门禁关闭创建券活动返回 409。券开启前原 CREDIT 路径可回退 OLD 保留 V43；开启后的默认回退目标仍支持券及已生产的扩展 Trace。跨激活点回退 OLD 未认证，不可仅等待 TTL/HELD 清零就接管券流量；必须隔离相关流量及管理入口另行验证。已发券不会随 jar 回退撤销。见 [07-deployment-order](07-deployment-order.md)。

## Second Benefit Provider

选择既有 SOURCE_ONLY COUPON：金额券、相对有效期、订单事务发放；CREDIT 是整数权益、异步事件发放。未强制通用提供者接口，复用能力特定 API 和既有 owner。见 [08-provider](08-second-benefit-provider.md)。

## Benefit Extension Results

同一活动→人群/规则→决定→固定引用→订单→执行，只新增券预留与固定钱包来源。配置互斥 CREDIT/COUPON；支付后的 gifted coupon 不隐式随退款撤销，后续补偿策略需独立决定。无外部提供者、第二 Controller、调度器、规则器或失败队列。

## Quota / Idempotency

券定义行锁+条件更新+CHECK，所有原发行来源同时尊重 reserved。订单 hold 主键和来源唯一键保证发券唯一。每种双进程 120 用户/30 额度：30 成功、90 冲突、最终 reserved=0/issued=30/grant=30。最后一份并发、取消释放、事务效果后回滚恢复通过。见 [09-consistency](09-second-benefit-consistency.md)。

## Campaign Conflict Model

仅支持既有单活动 BEST_OF：最高实际优惠、ID 升序破平局，纯决策/预览落选解释为 OUTRANKED_BEST_OF；持久 quote 扩展解释码需全节点升级后开启，默认兼容旧 ELIGIBLE。清晰赢家/平局/反序有测试；所有活动相互竞争以产生一个结果。EXCLUSIVE 分组、PRIORITY、STACKABLE 和礼包没有产品决策，不支持也不补算法。见 [10-conflicts](10-campaign-conflict-model.md)。

## Preview / Execution Consistency

可选 includePublishedCompetition 将草稿替换同 ID 发布版，与其余有效活动调用同一决策器；返回 selected 和完整 Trace。缺省/null 仍为旧单活动预览。实际竞争预览与 quote 同赢家，历史执行不重判、不改版本；预览无预算/库存/权益效果。

## Authorization

按仓库实际 MEMBER/租户 ADMIN/平台维护模型验证；服务层能力和租户条件不依赖 UI。403/404 负例在全套。没有杜撰运营细角色和作者禁止自批；组织职责分离政策待决。见 [11-authorization](11-authorization-model.md)。

## Multi-Instance Results

真实 OLD/NEW、NEW/NEW Spring 集成测试、两个独立 jar 进程持续额度争用均有证据；一条来源、一份授予、单个投影。最终独立负载两种都零新增死锁/锁超时。见 [12-multi-instance](12-multi-instance.md)。

## Failure Injection

在真实事务发券效果全部写入后抛异常，额度/钱包/hold 全回滚，正常付款恢复至唯一发放；原 CREDIT 隔离恢复及支付事件失败分类保留。未做 kill -9/真实网络分区/灾备，不将事务故障注入冒充这些认证。见 [13-failures](13-failure-injection.md)。

## Operational SLO / Alerts

复用既有 runtime 的待处理、年龄、隔离、无路由、近期完成指标；新增 profiling 默认 false，无业务 ID 标签。额度完整性违例立即视为正确性事故，长期 HELD/积压增长按运行手册检查与受控恢复。客户阈值/SLA 无业务基线，未编造。保留期不变，本轮不清理历史。见 [14-operations](14-capacity-slo.md)。

## Capacity Model

本地首个争用瓶颈是预算/券定义串行行锁（成功方法段 P95 305/388 ms）；quote 中数据库事实准备占主导。支付赠券方法 P95 19.548 ms。保留完整性优先，没有通过删约束、少记账或重复流水线提升数字。无长期 soak、硬件隔离或生产目标；这是一份本地容量结果，不是生产保证。

## Tests

最后 `./scripts/build.sh` clean：应用 279 run/0 failed/5 configured skips，架构 3/3，marketing 27/27、order 45/45、shared kernel 3/3；UI+API 同 jar。新增七个 MySQL 场景及一项旧枚举契约回归。详见 [15-regression](15-regression.md)、[QA](../../delivery/phase7-marketing-production/QA.md)。

## Mutation / Negative Tests

删除 ID 破平局：确定性断言准确失败一次；finally 恢复，clean marketing 验证和最终全套通过。配额、权限、未知规则、审批/失效资产、租户隔离等负例保留。不是完整 mutation 工具链认证。

## Regression Results

Phase 2–6 应用与架构回归保留并通过。首次实现的解箱/缺省 Boolean 问题已修复；第一次最终构建的两项旧旅程偶发失败记录保留，独立重跑及随后两次完整 clean 构建通过，根因未确认。没有隐藏失败或把 skip 变成 pass。

## Browser Results

最终 jar 受影响场景 2/2：真实会员订单→收款→履约→退款→CREDIT 冲正；可视规则/低代码预览审批发布及窄屏。首轮退款空列表时序失败后，测试等待真实异步退款，最终 17.3 s 通过。未重跑完整 Phase 5 浏览器四项历史失败集合。

## Known Limitations

- 生产峰值、硬件、长期负载、真实名单/历史分布未认证；固定 API 人群 500、同时有效候选 100。
- COUPON 开启后直接回退 pre-Phase6 OLD 接管新券流量不安全；安全回退目标必须保留能力。
- 原旅程首轮偶发失败未归因；两轮最终完整 clean 均通过。
- 细分角色、叠加、礼包、外部提供者、真实分区/灾备和完整浏览器集合不在已认证范围。

## Blocked / Deferred Product Decisions

没有阻止本次本地交付的未解实施阻塞。待独立决定：客户 SLO/真实容量分布、固定大名单导入治理、角色/职责分离、冲突叠加/优先组、礼包原子性、退款后 gifted coupon 补偿、外部权益契约和保留期。未提前编写这些功能。

## Changed Files

新增 V42/V43、纯冲突解析器；修改 benefit、marketing、marketing-runtime、order-runtime、trade、commerce-app 相关 API/Service/Mapper/XML、配置与测试；前端仅修复浏览器测试等待异步退款。交付文档/运行手册同步。完整清单：[changed-files](results/changed-files.md)。

## Evidence Location

`docs/evidence/phase7-marketing-production/`：00–15、结果 CSV/SQL 计划、可复跑脚本、本报告；`docs/delivery/phase7-marketing-production/`：计划、契约、状态、Review、QA；根 CODEX_PROGRESS.md 恢复信息。令牌/密码不在证据目录。

## Commit Boundary Recommendation

本任务原文明令不提交/推送，已遵守；无生产部署。将来明确授权时建议同分支分为：候选索引/规模回归；冲突/预览；券契约/迁移/同事务与测试；观测/浏览器时序/交付证据。券实现、V43、配置门禁和对应测试不可拆为不完整可运行提交。

## Next Recommended Phase

先用实际生产峰值/租户分布和目标环境做持续负载、滚动演练及回退/恢复验收，并补齐客户 SLO；随后按产品决定建设大名单治理、运营职责分离或叠加语义。当前证据尚不足以启动更大的旅程平台建设。

## 后续授权的 Git 交付

原实施轮次不提交/推送的记录保留为历史事实。用户随后明确要求先将 Phase 7 代码提交并推送远程 main，再开启 Phase 8；本次 Git 操作按此新授权执行。完整 clean 验证重新通过（应用 279/0/5、架构 3/3），分批暂存快照验证也通过。实际提交、远程结果与验收汇总见 [DELIVERY_RESULT](../../delivery/phase7-marketing-production/DELIVERY_RESULT.md)。无生产部署。
