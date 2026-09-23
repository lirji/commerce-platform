# 文档地图

同步范围：S0–S10 当前实现。历史切片证据保留当时计数和失败排查记录；最新验证入口为 `evidence/s10b/TEST_RESULT.md`，规范状态为 `PROGRESS_STATE.json`。

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
