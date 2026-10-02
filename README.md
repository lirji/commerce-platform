# 统一电商业务平台

基于 DDD 的 Java 模块化单体，单个 Spring Boot 进程提供 API、后台任务及 React 管理台/会员端，MySQL 持久化业务数据。历史 S0–S10 基线见 [原始验收](docs/evidence/s10b/TEST_RESULT.md)。当前会员链路与商品经营改造见 [计划](docs/delivery/member-lifecycle-catalog-ui/DELIVERY_PLAN.md)、[实施切片](docs/design/member-lifecycle-catalog-ui/IMPLEMENTATION_SLICES.md) 与 [交付进度](docs/PROGRESS_STATE.json)。

已实现会员、商家、店铺、SKU、库存、营销活动、人群快照、动态规则、优惠券、预算和权益、报价下单、支付、履约、退货退款及补偿。营销旅程支持持久化等待、条件决策、发权益和站内通知；低代码运营支持白名单组件、真实数据预览、审批发布和版本回退。

金额采用 CNY 精确分摊；支付未知结果不能释放库存或伪装成功；本地事务、条件更新、Outbox/Inbox、幂等命令及显式状态机保护业务不变量。真实 IdP、支付、外部权益及 WMS 联调按用户要求后置，当前支付/退款使用显式开启的持久化沙箱，权益为内部体验额度。

会员能力覆盖周期等级与保级、周期权益、积分赚取/账本/过期/退款债务/兑换和订单抵扣、行为画像、人群、定向券、生日/沉睡/复购/加购旅程及效果比较。商品支持类目、固定规格模板、图文、条码、检索、批量定时上下架/调价、可信渠道价。前端提供浅色经营工作台、折叠搜索导航、图文商品卡片和手机布局；本轮视觉范围与验证见 [前端视觉改版](docs/design/frontend-visual-refresh/BRIEF.md)。操作顺序、统计口径与边界见 [本轮运营手册](docs/delivery/member-lifecycle-catalog-ui/OPERATIONS_GUIDE.md)。

## 本地使用

本地 Docker 同源前后端入口为 **http://127.0.0.1:8602**，实际部署版本和验收见 [部署记录](docs/delivery/member-lifecycle-catalog-ui/DEPLOYMENT_RESULT.md)。隔离验收使用 **http://127.0.0.1:8604** 与测试库。新版演示身份在 `.local/member-suite-access.json`：`adminToken` 为管理员、`memberToken` 为周期权益会员，其他专项身份见运营手册；文件不入库，令牌不写聊天或文档。原 `.local/demo-access.json` 对应另一隔离租户，数据不能混用。

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
COMMERCE_E2E_BASE_URL=http://127.0.0.1:8602 python3 scripts/seed-member-suite.py --fresh
COMMERCE_UI_URL=http://127.0.0.1:8602 COMMERCE_EVIDENCE_DIR=../.local/operations-evidence npm run e2e --prefix frontend
```

上述浏览器命令要求目标运行本轮新版；也可明确测试 schema 后改用 8604。浏览器测试使用独立租户和真实 API，覆盖下单、支付、履约、退款、权益冲正、规则发布、低代码、旅程和移动端。CI 配置在 `.github/workflows/verify.yml`，使用独立临时 MySQL，执行构建、数据库测试、依赖审计及 Chromium 验收。

## 文档

- [文档地图与契约入口](docs/doc-map.md)
- [后端架构与数据所有权](docs/design/unified-commerce/BACKEND_ARCHITECTURE.md)
- [Java 包组织约定](docs/architecture/java-packages.md)
- [营销设计](docs/design/unified-commerce/MARKETING_DESIGN.md)、[前端设计](docs/design/unified-commerce/FRONTEND_ARCHITECTURE.md)
- [技术基线](docs/design/unified-commerce/TECH_SELECTION.md)、[实施切片](docs/design/unified-commerce/IMPLEMENTATION_SLICES.md)
- [架构审查与生产前缺口](.cursor/project-analysis/architecture-risks.md)
- [恢复记录](CODEX_PROGRESS.md)

本地验收不代表生产容量、容灾或真实渠道认证通过。跨店合并支付、分账、多币种、真实仓储和外部身份平台没有冒充已实现；旧规则迁移仍是独立 BLOCKED 任务。


### P5 统一身份试点

商品经营入口 `/operations/products`，外部门店协作入口 `/collaboration/products`。前端构建变量见 [frontend/.env.example](frontend/.env.example)；后端需同时启用既有中央store-read/scope开关并提供私有配置。product.read、product.update、product.export独立授权，数据库Owner再次核对范围、版本和门店状态；外部限时导出由真实OA审批后生效。

[统一运行与恢复说明](https://github.com/lirji/auth-platform/blob/main/docs/implementation/oa-auth/phase-5/P5_RUNTIME.md)包含精确OIDC回调、同源打包及开关回退边界。共享旧IdP升级HOLD不因本地试点通过而解除；P5历史不包含生产部署；最新P6状态见下方。

### P6 本地隔离迁移

中央`commerce.catalog.operate`覆盖既有完整CATALOG经营API和后台任务。`commerce.iam.catalog.enabled`默认关闭，启用还需store-read私有配置、显式身份桥及数据库CENTRAL路由；旧ADMIN和后台历史身份不能绕过。执行引用不保存用户Token，每步实时复核，目录版本/授权变化按安全边界停止旧任务。

[本仓测试记录](docs/implementation/oa-auth/phase-6/P6_TEST_RESULT.md)和[统一运行说明](https://github.com/lirji/auth-platform/blob/main/docs/implementation/oa-auth/phase-6/P6_RUNTIME.md)记录V48迁移Owner、冻结/切换/回退和31项真实隔离检查。原8602及commerce_local未切换；生产候选仍HOLD，P7未执行。

### 活动与预算员工入口

`/operations/campaigns?tenant_id=<UUID>`提供活动目录、结构化草稿和版本操作；`/operations/campaign-budgets?tenant_id=<UUID>`独立读取各内容版本预算。八项活动权限和`budget.read`分别授权，写入或预览人员无需额外目录读取权限。页面显示实际内容版本与状态锁版本，预览使用真实会员/SKU及固定资产，未知结果保留原幂等键和输入；登录失效隐藏敏感视图，撤权和服务故障拒绝操作。

编译入口只匿名提供固定GET页面壳，业务API仍校验中央身份、租户与独立能力。使用现有前端SSO构建变量、中央store-read/employee配置、显式身份桥及CAMPAIGN族CENTRAL路由；没有自动迁移旧ADMIN或授予新角色。当前CE05-CAM2已通过最终真实编译页面本地验收，[契约与验证记录](https://github.com/lirji/auth-platform/blob/main/docs/implementation/oa-auth/commerce-readiness/CE05_CAMPAIGNS.md)维护最终状态；本地隔离验证不改变原8602或生产部署。

### 动态人群员工权限与执行来源

既有 `/v1/admin/segments` 目录与定义、调度、刷新、运行记录、任务控制和有界推进分别使用 `segment.read/create/schedule/refresh/control/pump` 六项中央能力；读取、控制和推进不隐含创建或刷新权限。SEGMENT路由按真实租户显式接管，对象判权采用实际正定义版本，调度锁版本与输出快照版本保持独立。

手工任务持久化原员工与 Auth 执行引用的准确期限，不保存Token。每批扫描、快照发布、公告、重启和控制都重新核验原来源；新授权不能替换已撤销或过期的原任务。成功启用的周期调度保存独立固定政策，停未来调度或发布新定义不取消已开始的固定版本任务；STOPPED路由仍阻断系统任务，历史空来源在CENTRAL下拒绝推进。V65只追加来源和版本审计约束，不自动接管租户或推定历史政策。

当前后台已通过真实跨进程本地验收，Git与精确CI交付状态见验证记录；专用动态人群SSO页面尚待CE05-S2交付。[技术契约](https://github.com/lirji/auth-platform/blob/main/docs/design/oa-auth-unification/CONTRACTS_COMMERCE_SEGMENTS.md)与[验证记录](https://github.com/lirji/auth-platform/blob/main/docs/implementation/oa-auth/commerce-readiness/CE05_SEGMENTS.md)分别记录能力、任务语义和实际验收范围。本地隔离演练不改变原8602环境或生产部署。

### B端工作台与刷新恢复

管理工作台补齐任务恢复、恢复审计和历史重放；平台运维通过独立 `/v1/platform/me` 进入只读运行健康页。中央员工页面统一导航，但每个业务入口仍独立授权。刷新保留当前标签页会话及适用URL筛选、页签、游标，重新读取真实数据；退出与401清除凭据，服务暂不可用时支持恢复重试。

[设计与接口映射](docs/design/b-console-experience/FRONTEND_ARCHITECTURE.md)、[增量契约](docs/design/b-console-experience/CONTRACTS.md)、[验收与品质复核](docs/delivery/b-console-experience/TEST_RESULT.md)记录本轮边界及证据；旧8602容器没有自动更新，源码预览和生产部署分别记录。

B端第二轮品质迭代：统一经营壳层、正负金额趋势探索、旅程版本关系图、SKU操作与中央按页加载。见[迭代方案](docs/design/b-console-craft/PLAN.md)、[验证与限制](docs/delivery/b-console-craft/TEST_RESULT.md)、[实际自查修订](docs/delivery/b-console-craft/REVIEW.md)。刷新会话和URL上下文恢复契约继续保留。
