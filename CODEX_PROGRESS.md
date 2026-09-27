# Codex Progress

## 任务目标

按业务与运行能力细分服务端各 Maven 模块中的宽泛 Java 包，保持 HTTP、数据库、事件与业务行为不变；不修改前端，并完成验证与正常 Git 交付。

## 当前状态

服务端包迁移及本地验证完成。任务分支 `refactor/backend-capability-packages` 的 `7811dc4` 已推送，远程 `main` 已快进包含该提交。远程 CI 的服务端、数据库和打包应用步骤通过，浏览器阶段失败；本任务前的基线也存在相同 4 个浏览器失败。权威目录约定见 `docs/architecture/java-packages.md`，证据见 `docs/evidence/capability-package-refactor/PROJECT_REFACTORING_REPORT.md`。

## 已完成

- 将 149 个服务端生产类按真实能力迁移到子包，并同步 Java 引用、35 个 Mapper XML 及 3 个测试包；小模块保持紧凑结构。
- Java 197 个生产类的包路径一致，Mapper XML 无悬空类引用；去掉包名、import、全限定名与必要可见性变化后，无其他生产代码 token 差异。
- Spring Java Format 0.0.39 排版并校验；最终版 `./scripts/verify.sh` 通过：`commerce-app` 251 个测试，0 失败、5 个按配置跳过；架构测试 3 个通过。
- 前端、历史 Flyway SQL、POM 和运行部署文件未修改。
- `7811dc4 refactor(backend): 按业务能力细分服务端包结构` 已推送至任务分支及远程 `main`。

## 已修改文件

- `member`、`catalog`、`marketing-runtime`、`benefit`、`store`、`payment`、`order-runtime`、`marketing-automation`、`platform-runtime`、`fulfillment`、`commerce-app` 的相关 Java 与 Mapper XML；其他业务模块中仅同步 Java import。
- `commerce-app/src/test/java` 的 3 个同包访问测试。
- `docs/architecture/java-packages.md`、`docs/doc-map.md`、`docs/evidence/capability-package-refactor/PROJECT_REFACTORING_REPORT.md`、`CODEX_PROGRESS.md`。

## 未完成

- 本次服务端包迁移没有待实现代码。远程浏览器验收仍为红色，属于基线前端问题及一例尚未确认原因的运行间超时。

## 当前问题

- 基线 `3743eb1`、本任务 `main` 的 CI 均因相同 4 个前端浏览器用例失败，断言位置相同；任务分支运行另有 `commerce.spec.ts:126` 售后审批按钮等待超时，但同一提交的 `main` 运行该用例通过。额外超时根因未确认；本任务不改前端。
- `code-hygiene.py gate` 因不识别跨路径重命名，将迁移前已有的 53 处状态字符串比较判为新增阻断；源码 token 对比和真实数据库测试均确认本次未更改这些规则。门禁实际状态为 `IMPLEMENTATION_INCOMPLETE`，详情见重构报告。
- 5 个基准/专项测试依照仓库配置跳过，未视为已执行。

## 下一步建议

1. 若要使整条远程 CI 变绿，另行调查和修复基线浏览器验收失败，再复核分支运行的额外超时；不混入本次服务端包迁移。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md` 和 `docs/evidence/capability-package-refactor/PROJECT_REFACTORING_REPORT.md`，核对最新 Git 与 CI 事实。前端基线失败属于独立任务，不要混入本次服务端包迁移。
