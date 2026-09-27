# Java 包组织约定

模块先按业务能力划分：`member`、`catalog`、`payment` 等模块各自拥有应用服务、公开契约和数据访问。`commerce-app` 是 HTTP 与后台运行的装配入口，跨业务模块通过其 `api` 契约调用。`shared-kernel` 只放跨模块共用且稳定的值与异常类型。

| 包 | 职责 | 当前示例 |
|---|---|---|
| `<能力>.api` | 其他模块可依赖的端口、请求与结果契约 | `member.api.MemberPointsApi` |
| `<能力>.domain` | 不依赖 Spring、数据库的业务规则或状态迁移；有规则时才建立 | `order.domain.OrderLifecycle` |
| `<能力>.application` | 用例编排、事务边界与端口调用 | `payment.application.RefundService` |
| `<能力>.infrastructure.persistence` | MyBatis Mapper 与数据库记录 | `payment.infrastructure.persistence.RefundMapper` |
| `<能力>.infrastructure.adapter` | 外部渠道或沙箱实现 | `payment.infrastructure.adapter.SandboxRefundChannel` |
| `<能力>.infrastructure.security` | 基础设施侧加密实现 | `ordering.infrastructure.security.AddressCipher` |
| `app.http.<能力>` | HTTP 协议与参数转换，按会员、商品、营销等能力分组 | `app.http.member.MemberPointsController` |
| `app.runtime` | 事件处理、后台车道与运行监控编排 | `app.runtime.EventWorker` |
| `app.observability` | 指标导出 | `app.observability.EventRuntimeMetrics` |
| `app.configuration` | 安全和告警装配 | `app.configuration.SecurityConfiguration` |

`app.http.ApiErrors` 统一转换协议错误；`app.CommerceApplication` 保留为 Spring Boot 组合根。业务模块的 `api` 不是 HTTP Controller 包。简单 CRUD 保持现有应用服务，不为包名机械增加空领域对象、透传类或接口。新业务规则若能脱离外部依赖，再放入对应模块的 `domain`。

Mapper XML 的 `namespace` 和嵌套记录 `resultType` 必须与 Java 类全名同步；移动 Mapper 时要同时检查这些字符串。Flyway 已执行迁移不随格式化改写，以保持迁移校验值稳定。

本次排版使用 Spring Java Format 0.0.39、前端锁文件中的 Prettier 3.9.9、`xmllint --format` 和 Ruff。格式化范围是当前源码、POM、Mapper XML 与运行配置；历史证据和已执行 SQL 迁移保留原貌。
