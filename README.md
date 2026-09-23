# 统一电商业务平台

基于 DDD 的 Java 模块化单体，单个 Spring Boot 进程提供 API、后台任务及 React 管理台/会员端，MySQL 持久化业务数据。历史 S0–S10 基线见 [原始验收](docs/evidence/s10b/TEST_RESULT.md)。当前会员经营改造见 [计划](docs/delivery/member-commerce-operations/DELIVERY_PLAN.md)、[实施切片](docs/design/member-commerce-operations/IMPLEMENTATION_SLICES.md) 与 [交付进度](docs/PROGRESS_STATE.json)。

已实现会员、商家、店铺、SKU、库存、营销活动、人群快照、动态规则、优惠券、预算和权益、报价下单、支付、履约、退货退款及补偿。营销旅程支持持久化等待、条件决策、发权益和站内通知；低代码运营支持白名单组件、真实数据预览、审批发布和版本回退。

金额采用 CNY 精确分摊；支付未知结果不能释放库存或伪装成功；本地事务、条件更新、Outbox/Inbox、幂等命令及显式状态机保护业务不变量。真实 IdP、支付、外部权益及 WMS 联调按用户要求后置，当前支付/退款使用显式开启的持久化沙箱，权益为内部体验额度。

新增会员生命周期、成长等级/标签、动态人群、门店商品经营授权、SPU/规格/上下架调价、商品范围及阶梯促销预览、会员事件旅程频控、成交退款成本分析。操作顺序和统计口径见 [运营手册](docs/delivery/member-commerce-operations/OPERATIONS_GUIDE.md)。

## 本地使用

旧版已配置容器入口为 **http://127.0.0.1:8602**；本轮不更新此实例。新版隔离验收入口使用 **http://127.0.0.1:8603**、测试 schema 和 `.local/operations-access.json`，详见运营手册。旧版管理和会员访问凭据分别在 `.local/demo-access.json` 的 `adminToken`、`memberToken`，使用界面登录输入；文件不入库，令牌不写聊天或文档。

需要 Java 21、Maven 3.9、Node 24、Docker Compose，以及已授权的独立 MySQL schema。当前复用 `dev-infra`，不启动第二套公共组件。新机器需先按 [运行手册](deploy/README.md) 配置数据库和私密环境文件。

```sh
./scripts/build.sh               # 前端构建 + 真实数据库测试 + 同源 jar
./deploy/up.sh                   # 只构建/启动本项目应用
COMMERCE_BASE_URL=http://127.0.0.1:8602 python3 scripts/seed-local.py
python3 deploy/smoke.py
```

演示数据通过 API 写入数据库，同一 UTC 日期重复执行不会重复创建示例订单。停止本项目使用 `./deploy/down.sh`；不删除数据，也不管理共享 MySQL。

## 验证

```sh
./scripts/verify.sh              # 后端与架构约束测试
COMMERCE_E2E_BASE_URL=http://127.0.0.1:8602 python3 scripts/prepare-e2e.py
COMMERCE_E2E_BASE_URL=http://127.0.0.1:8602 python3 scripts/seed-operations.py --fresh
COMMERCE_UI_URL=http://127.0.0.1:8602 COMMERCE_EVIDENCE_DIR=../.local/operations-evidence npm run e2e --prefix frontend
```

上述浏览器命令要求目标运行本轮新版；当前旧 8602 不应直接用于本轮新增验收，可按运营手册改用 8603。浏览器测试使用独立租户和真实 API，覆盖下单、支付、履约、退款、权益冲正、规则发布、低代码、旅程和移动端。CI 配置在 `.github/workflows/verify.yml`，使用独立临时 MySQL，执行构建、数据库测试、依赖审计及 Chromium 验收。

## 文档

- [文档地图与契约入口](docs/doc-map.md)
- [后端架构与数据所有权](docs/design/unified-commerce/BACKEND_ARCHITECTURE.md)
- [营销设计](docs/design/unified-commerce/MARKETING_DESIGN.md)、[前端设计](docs/design/unified-commerce/FRONTEND_ARCHITECTURE.md)
- [技术基线](docs/design/unified-commerce/TECH_SELECTION.md)、[实施切片](docs/design/unified-commerce/IMPLEMENTATION_SLICES.md)
- [架构审查与生产前缺口](.cursor/project-analysis/architecture-risks.md)
- [恢复记录](CODEX_PROGRESS.md)

本地验收不代表生产容量、容灾或真实渠道认证通过。跨店合并支付、分账、多币种、真实仓储和外部身份平台没有冒充已实现；旧规则迁移仍是独立 BLOCKED 任务。
