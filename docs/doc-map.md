# 文档地图

历史同步基线：fb7adf6。2026-09-27 Java 包能力细分的当前约定见 `architecture/java-packages.md`，执行证据见 `evidence/capability-package-refactor/PROJECT_REFACTORING_REPORT.md`；上一轮排版证据仍在 `evidence/java-package-refactor/PROJECT_REFACTORING_REPORT.md`。S0–S10 为历史实现。历史切片证据保留当时计数和失败排查记录；最新验证入口为 `delivery/member-lifecycle-catalog-ui/LP11_TEST_RESULT.md`，规范状态为 `PROGRESS_STATE.json`。

| 实现所有者 | 权威设计/契约 |
|---|---|
| shared-kernel、marketing、order 纯领域 | design/unified-commerce/CONTRACTS.md |
| member、merchant、store、catalog、trade 报价 | design/unified-commerce/s4/CONTRACTS.md |
| inventory、order-runtime（包 ordering）、订单/地址 | design/unified-commerce/s5/CONTRACTS.md |
| payment、platform-runtime、Outbox/Inbox | design/unified-commerce/s6/CONTRACTS.md |
| fulfillment、aftersales、退款/退货 | design/unified-commerce/s7/CONTRACTS.md |
| marketing-runtime（包 campaign）、benefit | design/unified-commerce/s8/CONTRACTS.md |
| marketing-automation（包 journey、ops） | design/unified-commerce/s9/CONTRACTS.md |
| commerce-app、frontend、控制台读取接口 | design/unified-commerce/s10/CONTRACTS.md、FRONTEND_ARCHITECTURE.md |
| 构建/镜像/Compose/CI/私密配置位置 | ../deploy/README.md、design/unified-commerce/TECH_SELECTION.md |
| 数据所有权/事务及静态边界 | design/unified-commerce/BACKEND_ARCHITECTURE.md、architecture-tests |
| 当前 Java 包归属与格式化约定 | architecture/java-packages.md、evidence/capability-package-refactor/PROJECT_REFACTORING_REPORT.md、evidence/java-package-refactor/PROJECT_REFACTORING_REPORT.md |
| 风险/外部后置项 | design/unified-commerce/RISKS.md、../.cursor/project-analysis/architecture-risks.md |
| 计划/状态/恢复/Git | design/unified-commerce/IMPLEMENTATION_SLICES.md、PROGRESS_STATE.json、../CODEX_PROGRESS.md、evidence/DELIVERY_RESULT.md |

`CAPABILITY_MAP.md` 保留最初源仓扫描证据，不能把其中“待建设”当作当前完成状态。`previous-workspace-progress.md` 保留旧规则迁移门禁，不随本项目交付改变。当前连接/账号权限见 deploy/README.md；机密值只在忽略的本地文件中。

| 本轮增量 | 权威资料 |
|---|---|
| 生命周期/经营授权/SPU/成长标签/人群/促销/旅程/分析 | design/member-commerce-operations/CONTRACTS.md、BACKEND_ARCHITECTURE.md |
| 技术选择/前端/切片 | design/member-commerce-operations/TECH_SELECTION.md、FRONTEND_ARCHITECTURE.md、IMPLEMENTATION_SLICES.md |
| 运营与演示/报表口径/回退 | delivery/member-commerce-operations/OPERATIONS_GUIDE.md |
| 本轮计划/验收/审查/交付 | delivery/member-commerce-operations/DELIVERY_PLAN.md、OP01_TEST_RESULT.md 至 OP08_TEST_RESULT.md、REVIEW.md、DELIVERY_STATUS.md |

原 S0–S10 进度原样留存 evidence/s10b/PROGRESS_STATE_BASELINE.json，不将新能力倒填为历史交付。8602 旧容器与 8603 新版隔离验收是不同运行事实；源码变更不代表旧容器已更新。


| LP01–LP11 本轮权威入口 | 位置 |
|---|---|
| 业务范围、架构、技术复用、接口及各片契约 | design/member-lifecycle-catalog-ui/ |
| 11片进度与证据 | delivery/member-lifecycle-catalog-ui/STATUS.md、LP01–LP10_EVIDENCE.md、LP11_TEST_RESULT.md |
| 玩法操作、统计口径、种子和回退 | delivery/member-lifecycle-catalog-ui/OPERATIONS_GUIDE.md |
| 最终CI/Git/本地Docker事实 | delivery/member-lifecycle-catalog-ui/CI_RESULT.json、DELIVERY_RESULT.md、DEPLOYMENT_RESULT.md |

本轮总览由MemberApi/CatalogApi的Stats及MarketingEffectsApi.daily聚合组成；App只装配域API。可信渠道来自Actor.channel及CredentialMapper，不来自请求参数；Quote/Order保存渠道。V34为本轮最新迁移。源代码修改与实际Docker部署仍分别记录，旧证据不倒填。

2026-09-25 架构整改（异步消费者/后台任务隔离、自动到期、默认拒绝授权、锁顺序、应用壳边界）见 evidence/remediation-r1/REMEDIATION_REPORT.md，发现来源为 ../.project-analysis/project-architecture-business-gap-evolution-report.md。
2026-09-26 事件运行时第二阶段（租户公平调度、积压索引、隔离证据、历史报价兼容、积压诊断与告警契约、403/404契约）见 evidence/phase2-event-runtime/PHASE2_REPORT.md；调度契约写在 design/unified-commerce/BACKEND_ARCHITECTURE.md“一致性和恢复”。
2026-09-26 后台运行时第三阶段（车道拓扑与跨车道公平、共享租户轮转、失败分类与重试预算、依赖熔断、无消费者事件SKIPPED、平台运维跨租户指标授权、车道告警、保留策略提案、运维手册）见 evidence/phase3-background-runtime/PHASE3_REPORT.md 与 evidence/phase3-background-runtime/13-runbook.md；契约写在 design/unified-commerce/BACKEND_ARCHITECTURE.md“一致性和恢复”“积压诊断”。
2026-09-26 运行时第四阶段（逐项重试隔离、周期考核可索引到期与策略分批推进、统一恢复与恢复审计、重放安全分类与安全门、有界历史重放、可配置保留期清理、崩溃重启与多实例恢复证明、SLO与告警出口、运维手册）见 evidence/phase4-runtime-recovery/PHASE4_REPORT.md 与 evidence/phase4-runtime-recovery/13-runbook.md；契约写在 design/unified-commerce/BACKEND_ARCHITECTURE.md“一致性和恢复”。
2026-09-27 业务一致性第五阶段（支付/到期竞态、事件原子性、履约/权益/积分/券/退款/售后并发与故障验证）见 evidence/phase5-business-consistency/PHASE5_REPORT.md；细分矩阵、测试和限制见同目录 00–18 证据。产品源码、数据库迁移及 Phase 2–4 运行时实现未变更。
2026-09-27 营销规则与权益第六阶段的权威计划/状态见 delivery/phase6-marketing-platform/DELIVERY_PLAN.md、DELIVERY_STATUS.md；活动执行、版本、规则/人群/权益矩阵、纵向切片和运行手册见 evidence/phase6-marketing-platform/00-baseline.md 至 14-regression.md 及 PHASE6_REPORT.md。新订单活动执行持久化由 `V41__marketing_execution.sql` 定义；既有 S8/S9 契约仍是历史设计，不将 Phase 6 结果倒填到旧交付记录。

2026-09-27 Phase 7 增量同步基线为 `ddf55026bf026f96cec8793556b6559bdc749b72`。当前工作树的营销候选时间过滤/V42 索引、V43 活动券预留、CREDIT/COUPON 执行、竞争预览与滚动开关契约见 `delivery/phase7-marketing-production/CONTRACTS.md`；唯一计划/状态见同目录 `DELIVERY_PLAN.md`、`DELIVERY_STATUS.md`。隔离容量和旧/新二进制证据见 `evidence/phase7-marketing-production/`；本轮不提交、推送或生产部署，不能把本地 schema 验证当作生产生效事实。

2026-09-27 Phase 7 正常Git交付与远程main核对见 delivery/phase7-marketing-production/DELIVERY_RESULT.md。Phase 8 本地实施与必需验收完成，严格状态PHASE_8_COMPLETE_WITH_LIMITATIONS：计划/契约/切片、各片TEST_RESULT、Review/QA及Git/CI实际状态见 delivery/phase8-marketing-journey/；最终报告见 evidence/phase8-marketing-journey/PHASE8_REPORT.md，00–16证据、MATRICES、results和scripts映射固定版本、图校验、历史/恢复、真实进程、兼容、规模与回归。V44逐步历史、V45到期发现索引为扩展迁移，不倒填旧证据或宣称生产部署。

2026-09-30 B端改造及刷新修复：当前设计、真实HTTP端点清单、增量契约、切片见 `design/b-console-experience/`；验收、自查修订和交付见 `delivery/b-console-experience/`。旧S10仅内存凭据约束由本轮用户要求覆盖为标签页sessionStorage＋服务端复核；中央OIDC及独立授权边界继续保留。原中央员工任务进度完整保存为 `delivery/b-console-experience/PREVIOUS_PROGRESS.md`，不把本轮视觉改造当作新增中央授权能力。

2026-09-30 B端第二轮Craft：设计与C01–C05见 `design/b-console-craft/PLAN.md`；源码指纹、实施、跨浏览器/真实接口验证、自查修订和Git/CI见 `delivery/b-console-craft/`。上轮刷新/接口证据保留，当前状态以 `PROGRESS_STATE.json` 为准，无生产部署或外部获奖承诺。
