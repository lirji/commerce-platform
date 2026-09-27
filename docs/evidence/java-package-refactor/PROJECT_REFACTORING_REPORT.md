# Java 包分类与项目排版重构记录

## 目标与基线

目标是让 Java 包体现业务归属和技术职责，并统一当前项目源码排版。修改前有 197 个生产 Java 文件；`commerce-app` 的 39 个生产类全部位于同一 `app` 包，HTTP、配置、后台调度和指标混放。业务模块的 Mapper 与渠道适配器位于笼统的 `infrastructure` 包。根 POM、部分 XML 与前端源码缺少统一排版。

修改前 `mvn test -q` 因未指定 `COMMERCE_TEST_DB_URL` 的隔离测试库而失败；失败来自测试的显式环境门禁，不是本次重构引入。修改前已检查工作树干净。

## 问题、目标与执行批次

| 问题 | 证据与影响 | 优先级 | 调整 |
|---|---|---|---|
| 应用壳单包混放多种职责 | `commerce-app/src/main/java/.../app` 原有 39 个类，查找控制器和后台调度需要逐个辨别 | P2 | HTTP 按能力分组；运行、指标、配置分包 |
| 持久化与渠道实现同属宽泛基础设施包 | `payment/infrastructure` 原同时包含 Mapper 与沙箱渠道实现 | P2 | Mapper 进入 `persistence`，渠道实现进入 `adapter` |
| 排版方式不一致 | Java、前端、POM、XML 的原有缩进与空格不统一 | P3 | 使用对应格式化工具处理当前源码与配置 |

先迁移 Mapper 与渠道实现并同步 Java 引用及 40 个 Mapper XML，再整理应用壳及相关测试包，之后执行格式化。改动仅涉及包名、导入、包级可见性适配、空白与缩进。未更改 HTTP 路径、SQL 语句、Flyway 迁移、表结构或业务规则。回退方式是回退本任务 Git 提交；无需数据补偿。

## 验证与兼容性

| 项目 | 结果 |
|---|---|
| Java 生产文件包声明与路径、Mapper XML 类名 | 197 个路径与 40 个 XML 静态核对通过 |
| Maven 编译与测试源码编译 | `mvn -q -DskipTests test` 通过 |
| 前端构建、Prettier 检查 | 通过 |
| 完整后端验证 | `./scripts/verify.sh` 通过；隔离 MySQL schema 上的 `commerce-app` 251 个测试，0 失败、5 个按测试配置跳过；架构检查 3 个测试通过 |
| Python 与 Shell 语法 | Ruff 格式检查、`compileall` 和 `bash -n` 通过 |
| XML 格式稳定性 | 60 个 POM/Mapper XML 再格式化无变化 |

API、数据库、事件契约、外部调用和业务行为预期均为 `UNCHANGED`。真实数据库集成测试已在显式隔离 schema 上通过。保留的 5 个跳过项是需单独开启的基准或专项测试，不计作已执行。

`code-hygiene.py gate` 返回 `IMPLEMENTATION_INCOMPLETE`：它将本次用户明确要求的全项目排版标成“无关格式化”与“缩进风格变化”，将 XML 排版标成“新增依赖”，并对排版后触及的既有事务、状态字面量和数字发出检查要求。一条“未使用导入”经核对被类内请求类型实际引用。此门禁与本次任务范围冲突，作为限制保留；实际编译、真实数据库测试、架构测试、格式器复查与差异检查均通过。
