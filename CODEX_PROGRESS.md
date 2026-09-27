# Codex Progress

## 任务目标

按业务与运行能力细分服务端各 Maven 模块中的宽泛 Java 包，保持 HTTP、数据库、事件与业务行为不变；不修改前端，并完成验证与正常 Git 交付。

## 当前状态

服务端包迁移及本地验证完成，位于任务分支 `refactor/backend-capability-packages`。待提交、推送并核对远程 `main` 与 CI。权威目录约定见 `docs/architecture/java-packages.md`，证据见 `docs/evidence/capability-package-refactor/PROJECT_REFACTORING_REPORT.md`。

## 已完成

- 将 149 个服务端生产类按真实能力迁移到子包，并同步 Java 引用、35 个 Mapper XML 及 3 个测试包；小模块保持紧凑结构。
- Java 197 个生产类的包路径一致，Mapper XML 无悬空类引用；去掉包名、import、全限定名与必要可见性变化后，无其他生产代码 token 差异。
- Spring Java Format 0.0.39 排版并校验；最终版 `./scripts/verify.sh` 通过：`commerce-app` 251 个测试，0 失败、5 个按配置跳过；架构测试 3 个通过。
- 前端、历史 Flyway SQL、POM 和运行部署文件未修改。

## 已修改文件

- `member`、`catalog`、`marketing-runtime`、`benefit`、`store`、`payment`、`order-runtime`、`marketing-automation`、`platform-runtime`、`fulfillment`、`commerce-app` 的相关 Java 与 Mapper XML；其他业务模块中仅同步 Java import。
- `commerce-app/src/test/java` 的 3 个同包访问测试。
- `docs/architecture/java-packages.md`、`docs/doc-map.md`、`docs/evidence/capability-package-refactor/PROJECT_REFACTORING_REPORT.md`、`CODEX_PROGRESS.md`。

## 未完成

- 提交任务分支，按持续授权正常推送并合入远程 `main`，核对本次远程 CI。

## 当前问题

- 基线 `3743eb1` 的远程 CI 已因 4 个前端浏览器验收用例失败；本任务不改前端。新分支 CI 需区分已有失败与本次回归。
- `code-hygiene.py gate` 因不识别跨路径重命名，将迁移前已有的 53 处状态字符串比较判为新增阻断；源码 token 对比和真实数据库测试均确认本次未更改这些规则。门禁实际状态为 `IMPLEMENTATION_INCOMPLETE`，详情见重构报告。
- 5 个基准/专项测试依照仓库配置跳过，未视为已执行。

## 下一步建议

1. 提交并推送任务分支，正常集成至远程 `main`，核对远程 CI；若必需门禁失败，记录具体阻塞。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md` 和 `docs/evidence/capability-package-refactor/PROJECT_REFACTORING_REPORT.md`，从未完成的 Git 与 CI 步骤继续。保留用户已有改动；不要修改前端或历史 Flyway 迁移。
