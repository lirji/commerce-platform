# 技术选择与工程基线

当前有效基线：Java 21、Maven 3.9.12、Spring Boot 4.1.1 BOM、MyBatis starter 4.0.1、MySQL 8.4.11、Flyway（BOM管理）、JUnit 6（BOM管理）、Surefire 3.5.4。纯 shared-kernel/marketing/order 无运行框架依赖；SQL 集中在所属模块 Mapper XML。具体依赖锁定以 pom.xml 和 frontend/package-lock.json 为准。

选择 Maven 多模块、单个 Spring 应用和同库本地事务，避免在没有独立扩容/发布证据时引入微服务。可靠异步采用数据库 Outbox/Inbox 与持久旅程，不引入 Kafka/RabbitMQ；规则采用受限 AST，不迁移旧 Drools。查询先使用 MySQL，不引入 Redis/ES。所有新增迁移含表/列中文注释，由真实 MySQL 测试验证。

## S4运行时选型（2026-09-23）

复用Java21，新增Spring Boot4.1.1 BOM（包含Spring7/Jackson3/Flyway/MySQL驱动），MyBatis starter4.0.1。JUnit随BOM统一，避免测试平台5/6混装；Surefire3.5.4。生产内核仍无框架依赖，新增runtime与持久化模块单独依赖Spring。实际MySQL通过dev-infra只读SELECT VERSION()确认8.4.11。

官方依据：[Boot系统要求](https://docs.spring.io/spring-boot/system-requirements.html)、[管理依赖](https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html)、[MyBatis兼容矩阵](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)、[Flyway MySQL支持](https://documentation.red-gate.com/fd/mysql-277579322.html)。MyBatis4.0声明支持Boot4.0+及Java17+；最终兼容以本项目真实启动/SQL测试为准。Spring/MyBatis/Flyway开源核心许可为Apache2.0，Connector/J为GPLv2+FOSS例外；当前本地应用使用，发布分发前保留依赖许可证清单。安全公告已查Spring官方页面，不将该查看声称为完整依赖漏洞扫描；上线扫描仍需单独证据。

## S6异步选择

选择数据库Outbox + 有界本地Worker，复用现有MySQL与Spring调度，不新增MQ。数据库行锁SKIP LOCKED防多实例重复领取；Inbox与本地副作用事务提交。此语义仅覆盖同库本地消费者，远程适配端口必须在业务事务外调用并持久化结果，不能推导端到端exactly-once。沙箱渠道独立账本持久化UNKNOWN/OPEN/PAID/CLOSED，使用配置开关显式启用；正式渠道后置。

## S10a前端基线

单React SPA，React/ReactDOM19.3.0、AntDesign6.6.5、Vite8.3.0、TypeScript7.0.2、Playwright1.63.0、Prettier3.9.9，精确解析见frontend/package-lock.json。Node24.12.0/npm11.6.2实测。参考仓已有React/Ant经验，不引入第二组件库或SSR。官方依据及界面/状态设计见FRONTEND_ARCHITECTURE.md；npm registry元数据确认React/Ant/Vite MIT、TypeScript/Playwright Apache2.0，npm audit未发现已知漏洞（证据s10a/npm-audit.json）。这不替代生产供应链持续扫描。

## S10b交付基线

构建顺序为 npm ci → 前端类型检查/构建 → Maven clean verify（with-ui profile）→ 同源可执行 jar。Docker 只封装已验证 jar，Temurin 21.0.12+8 UBI9 minimal 固定 digest，MySQL 复用 dev-infra 外部网络，镜像与参数见 Dockerfile/compose.yaml。前端静态资源不需独立 Node 运行进程。

GitHub Actions 使用临时 MySQL 8.4.11、Java21、Node24.12.0，官方 action 固定提交 SHA；构建、npm audit、真实浏览器验收均为实际执行步骤。actionlint1.7.12 在本地验证工作流语法；这不替代远程执行结果。没有生产自动部署。版本兼容与许可证核查记录不等同于完整后端 CVE/SBOM 审计，生产发布前需独立持续扫描。
