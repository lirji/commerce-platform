# 统一电商业务平台

目标：DDD + 模块化单体，覆盖会员、商家、店铺、商品、营销、交易、订单、支付、履约及售后。营销包含活动、人群、动态规则、权益、优惠、旅程和低代码运营。

当前已完成首批**领域内核**：版本化活动条件树、可解释优惠报价、精确分摊、订单生命周期。当前不是可部署电商应用：尚无 HTTP、数据库、身份接入、支付渠道、Outbox 或前端。

## 构建

需要 JDK 21、Maven。本机已有依赖缓存，可离线构建：

```sh
cd commerce-platform
mvn -o -B verify
```

首次在无缓存机器上需允许 Maven 下载依赖，再运行 `mvn -B verify`。业务库没有生产三方依赖。测试数据仅为 JUnit fixture，不是页面 Mock 或正式业务数据。

| 当前模块 | 职责 |
|---|---|
| shared-kernel | CNY 精确金额、稳定标识、业务错误 |
| marketing | api 契约、三值条件树求值、单活动固定优惠择优、行分摊 |
| order | 订单状态与事件、合法迁移和版本推进 |
| architecture-tests | 对真实编译产物执行 jdeps 模块/技术依赖检查 |

架构检查只覆盖当前编译类的静态依赖，不能证明未来数据库访问、反射和运行时隔离。

## 方案与进度

- [现有项目资产与缺口](docs/design/unified-commerce/CAPABILITY_MAP.md)
- [总体架构与数据所有权](docs/design/unified-commerce/BACKEND_ARCHITECTURE.md)
- [营销领域方案](docs/design/unified-commerce/MARKETING_DESIGN.md)
- [技术选择与基线](docs/design/unified-commerce/TECH_SELECTION.md)
- [首批精确契约](docs/design/unified-commerce/CONTRACTS.md)
- [分阶段实施路线](docs/design/unified-commerce/IMPLEMENTATION_SLICES.md)
- [风险与待决事项](docs/design/unified-commerce/RISKS.md)
- [验证结果](docs/evidence/TEST_RESULT.md)
- [恢复入口](CODEX_PROGRESS.md)

下一里程碑：隔离 MySQL 下的会员/商店/商品最小主数据、活动发布、报价持久化和真实 API。随后扩展预占下单、支付与事件、履约售后，再完成营销旅程及运营台。旧规则迁移计划独立保留，未被本项目建设解除。
