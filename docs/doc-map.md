# 文档地图

同步基线：fb7adf6，2026-09-23 增量核对本轮全部已提交与未提交改动。S0–S10 为历史实现。历史切片证据保留当时计数和失败排查记录；最新验证入口为 `delivery/member-lifecycle-catalog-ui/LP11_TEST_RESULT.md`，规范状态为 `PROGRESS_STATE.json`。

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
