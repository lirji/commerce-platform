# 服务端能力包细分重构记录

## 目标、范围与基线

目标：将多能力 Maven 模块内宽泛的 `api`、`application`、`infrastructure` 包按真实业务或运行职责细分，同时保留 HTTP 路径、数据库结构与 SQL、事件类型、事务和业务规则。范围仅包含服务端 Java、受影响的 Mapper XML、少量测试包以及包约定文档；前端、Flyway 历史迁移与生产部署不在范围内。

基线提交为 `3743eb1`（当时的 `origin/main`），工作树无未提交改动。修改前 `mvn -q -DskipTests test` 与 `mvn -q -pl architecture-tests -am test` 通过。已有 `docs/design/unified-commerce/BACKEND_ARCHITECTURE.md` 明确模块数据所有权和跨模块只经 `api` 调用；现有真实 MySQL 集成测试、架构测试提供行为与依赖保护。

## 问题与目标结构

| 问题 | 证据 | 级别 | 处理 |
|---|---|---|---|
| 多条业务线共用笼统技术包 | 迁移前 `member` 的 7 个 API、8 个应用类和 7 个 Mapper 分别堆在三个平级包；`catalog`、`benefit`、`campaign` 情形相同 | P2 | 能力优先，再在能力内保留必要技术层 |
| 运行时根包混放调度、事件、恢复和重放 | 迁移前 `platform-runtime` 有 16 个根包类、6 个 `api` 类和 6 个持久化 Mapper | P2 | 运行能力分组，公开端口保留 `runtime.api.<能力>` |
| 应用壳同一 HTTP/运行目录承担多类职责 | `app.http.marketing` 同时含活动资产、人群、旅程、分析入口；`app.runtime` 同时有调度、事件和告警类 | P3 | HTTP 按入口能力细分，运行装配按职责细分 |
| 内部适配端口混在跨模块 API 包 | `PaymentChannel`、`RefundChannel`、`WmsPort` 由各自模块内部服务和适配器使用 | P3 | 移到本能力的 `application.port`，跨模块契约仍在 `api` |

目标结构和各模块具体目录见 `docs/architecture/java-packages.md`。小模块保持紧凑层次；不新增空领域对象、接口或 Maven 模块。`CatalogMerchandisingApi` 聚合多项商品经营能力，当前只移动包，不在结构重构中拆契约。

## 变更批次与测试保护

1. **业务能力包**：迁移会员、商品、活动、权益、门店、支付、订单运行和定向发券的 91 个类；同步被引用的 Java、测试与 26 个 Mapper XML。全模块编译和架构测试通过后进入下一批。
2. **公共运行时包**：迁移 28 个类到事件、命令、工作、恢复、重放、保留、身份等目录；同步 Java 和 9 个 Mapper XML。全模块编译和架构测试通过。
3. **应用装配包**：迁移 29 个 Controller、运行组件、指标及配置类；将 3 个需要包级可见性的测试移到对应包。全模块编译通过。最终将 `WmsPort` 移到 `fulfillment.application.port`。

改动前已有 `ModuleBoundaryTest`、业务单元测试以及显式隔离 MySQL schema 上的 `commerce-app` 集成测试。包级协作者仍尽量同包：积分消费服务与积分主服务都位于 `points.application`；跨能力确需调用的 `ItemRetries`、恢复入口与订单到期分类方法才扩大 Java 可见性，未更改其执行逻辑。

共迁移 149 个生产类。按迁移前后同名类逐一比较，去掉 `package`、`import`、全限定名变化与上述 Java 可见性调整后，197 个生产类均无其他代码 token 差异；这只证明源码层面的结构等价，运行行为仍以集成测试验证。

## 验证与兼容性

| 项目 | 结果 |
|---|---|
| Java 路径与 `package` 声明 | 197 个生产类全部一致 |
| Mapper XML 中的类全限定名 | 0 个悬空引用 |
| 格式与差异 | Spring Java Format 0.0.39 apply/validate 通过；`git diff --check` 通过 |
| 全量服务端验证 | 最终版再次运行 `./scripts/verify.sh`：`commerce-app` 251 个测试，0 失败、5 个按配置跳过；架构测试 3 个通过，Maven `BUILD SUCCESS`。此前迁移后的第一轮全量运行也通过 |

最后移动 `WmsPort` 后曾尝试只运行 `PersistedCommerceTest`。该仓库根 POM 固定 `failIfNoTests=true`，上游模块在测试筛选后先行停止，目标用例未执行；最终改用完整 `verify`，不把这些筛选命令当作产品失败或测试通过的证据。

`code-hygiene.py gate` 把移动后的文件按新文件逐行扫描（其 Git 输入使用 `--no-renames`），因 53 处迁移前已存在的状态字符串比较返回 `IMPLEMENTATION_INCOMPLETE`；还曾指出 1 个迁移前已有的未使用 import，已清理。该门禁无法识别本次纯包迁移，限制保留。上文逐类 token 对比表明这些状态比较和事务代码均未改变；格式器校验、编译、真实 MySQL 测试、架构测试和 XML 类引用检查均实际通过。

兼容性：HTTP 请求/响应/路径 **UNCHANGED**；数据库表、索引、SQL 逻辑与 Flyway 迁移 **UNCHANGED**；事件类型、载荷及消费语义 **UNCHANGED**；业务状态流转、金额计算、权限与事务 **UNCHANGED**；仓库内 Java 类型的全限定名 **CHANGED**，外部 Java 调用方若直接编译依赖这些类，需要更新 import。本项目内的引用、Mapper XML 与测试已同步。

回退方式：回退本任务的源码和文档提交；没有数据迁移，也无需数据补偿。已发布的外部 Java 客户端若引用旧全限定名，应按版本窗口单独评估，本仓库未发现此类构建依赖。

Git 交付：`7811dc4 refactor(backend): 按业务能力细分服务端包结构` 已位于任务分支 `refactor/backend-capability-packages` 及远程 `main`。

远程 CI：`main` 运行 [36318647988](https://github.com/lirji/commerce-platform/actions/runs/36318647988) 的真实 MySQL 构建验证、前端依赖审计、打包应用启动均通过，浏览器验收为 20 通过、4 失败。4 个失败分别在 `catalog-merchandising.spec.ts`、`coupon-deliveries.spec.ts`、`member-behavior.spec.ts` 和 `member-cycles.spec.ts`，与基线 `3743eb1` 的 [36306871849](https://github.com/lirji/commerce-platform/actions/runs/36306871849) 相同，且失败断言位置相同。任务分支运行 [36318625871](https://github.com/lirji/commerce-platform/actions/runs/36318625871) 同样在浏览器阶段失败；除了这 4 个，还有 `commerce.spec.ts:126` 等待“批准”按钮超时，该用例在同一提交的 `main` 运行通过。额外超时原因未确认，不能声称整条远程 CI 通过。

## 剩余关注

- 包结构不会自动收窄现有大型接口的方法集合；商品经营契约如需进一步拆分，应作为独立契约任务评估调用方与兼容窗口。
- 既有远程 `main` CI 在本任务前的 `3743eb1` 上因 4 个前端浏览器验收用例失败而标红；本任务不修改前端。新提交的 CI 状态须独立核验，不将历史红灯当成本次 Java 验证通过的证据。
