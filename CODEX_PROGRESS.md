# Codex Progress

## 任务目标

格式化当前项目源码与配置，并把 Java 的宽泛包进一步按业务入口、持久化、渠道适配、后台运行、指标和配置职责分类。保持 HTTP、数据库、事件与业务行为不变。

## 当前状态

实现和本地验证已完成。任务分支 `refactor/java-packages-formatting` 的 `c23f873`、`8e5c883` 已推送，远程 `main` 已快进包含这两个提交。远程 CI 状态待核验。

## 已完成

- 迁移 76 个生产类，分出 `app.http.<能力>`、`app.runtime`、`app.observability`、`app.configuration`、`infrastructure.persistence`、`infrastructure.adapter`、`infrastructure.security` 等包，并同步 40 个 MyBatis Mapper XML。
- 格式化 Java、前端 TS/TSX/CSS/HTML/JSON、POM、Mapper XML、Python、YAML 和当前 Shell 脚本；历史 Flyway SQL 与历史证据保留原样。
- `./scripts/verify.sh` 在显式隔离 MySQL 测试库通过：`commerce-app` 251 个测试，0 失败、5 个按配置跳过；架构测试 3 个通过。
- `npm run build --prefix frontend`、Prettier 检查、Ruff 检查、XML 格式稳定性、Python/Shell 语法检查与 `git diff --check` 通过。
- 包路径、设计约定与验证证据见 `docs/architecture/java-packages.md` 和 `docs/evidence/java-package-refactor/PROJECT_REFACTORING_REPORT.md`。

## 已修改文件

- 各 Maven 模块的 `src/main/java` 与部分 `src/test/java`，及 40 个 Mapper XML。
- 根及各模块 `pom.xml`；`frontend/src`、`frontend/tests` 与前端配置。
- `deploy/*.py`、`scripts/*.py`、`scripts/build.sh`、`scripts/verify.sh`、`compose.yaml`、`.github/workflows/verify.yml`。
- `README.md`、`docs/doc-map.md`、上述架构约定和重构报告。

## 未完成

- 核验本次远程 CI 结果；若失败，确认是否由本任务引入并在任务分支修复。

## 当前问题

- 前一轮 B 端修复已交付；C 端评审问题仍按用户此前要求暂缓，不在本任务范围。历史状态见 `docs/PROGRESS_STATE.json`。
- `code-hygiene.py gate` 将本次明确要求的全项目排版判为无关格式变动，对 POM 缩进发出新增依赖误报；详情见重构报告。实际验证已通过。
- 5 个需单独开启的基准/专项测试按仓库配置跳过。

## 下一步建议

1. 查看远程 `main` 对应的 Verify commerce platform CI 结果。
2. 若 CI 失败，先核对失败与本任务的因果关系，再做有界修复。

## 恢复 Prompt

请读取 `CODEX_PROGRESS.md` 与 `docs/evidence/java-package-refactor/PROJECT_REFACTORING_REPORT.md`，核对当前 Git 与 CI 状态，从未完成的远程验证步骤继续。保留用户已有改动，不重复格式化或改写历史迁移。
