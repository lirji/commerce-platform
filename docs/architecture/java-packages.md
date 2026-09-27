# Java 包组织约定

本项目是模块化单体。Maven 模块仍按数据与业务所有权划分；拥有多条业务线的模块在模块内先按能力划分，再放契约、用例与基础设施。小模块只有一条主线时保留现有 `api`、`application`、`infrastructure` 层次，避免为目录数量制造空类。

## 能力优先的包结构

```text
<业务模块>/<能力>/api                         跨模块调用的稳定 Java 契约
<业务模块>/<能力>/application                 用例、事务与事件处理
<业务模块>/<能力>/application/port            该能力内部使用的外部系统端口
<业务模块>/<能力>/infrastructure/persistence  Mapper 与数据库记录
<业务模块>/<能力>/infrastructure/adapter      外部系统或沙箱实现
<业务模块>/<能力>/domain                      能脱离框架运行的规则；仅有规则时建立
```

`api` 不是 HTTP Controller 包。其他业务模块只能依赖目标模块的 `api`，不能直接依赖其 `application`、`domain` 或 `infrastructure`。支付渠道、退款渠道和 WMS 的内部调用端口放在各自能力的 `application.port`；真正跨模块使用的契约仍在 `api`。`architecture-tests` 对编译产物执行依赖检查，移动类时必须保持这个方向。

| Maven 模块 | 当前能力目录 | 说明 |
|---|---|---|
| `member` | `profile`、`tag`、`points`、`points.spend`、`growth`、`behavior`、`cycle`、`recovery`、`configuration` | 积分消费服务与积分主服务同在 `points.application`，保留同一账本内的协作边界；`points.spend.api` 单独标出消费契约 |
| `catalog` | `assortment`、`product`、`merchandising`、`pricing`、`job` | `CatalogApi` 同时提供目录与可信售价，故使用 `assortment`；现有 `CatalogMerchandisingApi` 的类目、模板、资料和检索仍是一个契约，未擅自拆接口 |
| `marketing-runtime`（Java 包 `campaign`） | `management`、`funding`、`segment`、`asset`、`rule` | 规则节点与可信会员事实保留可被其他模块依赖的 `rule.api` |
| `benefit` | `coupon`、`entitlement`、`pointoffer`、`memberbenefit` | 每条权益线拥有自己的服务与 Mapper |
| `store` | `management`、`access` | 门店资料与经营资源授权分开 |
| `payment` | `charge`、`refund` | 各自包含用例、持久化与渠道适配；内部渠道端口位于 `application.port` |
| `order-runtime`（Java 包 `ordering`） | `order`、`expiry`、`event`、`address` | 订单写模型留在 `order`；到期恢复、事件契约和地址加密各归其职 |
| `marketing-automation` | 原有 `journey`、`insight`、`ops`；新增 `journey.delivery` | 只细分已有旅程内的定向发券 |
| `platform-runtime` | `event`、`command`、`work`、`recovery`、`replay`、`retention`、`identity`、`validation`、`serialization` | 对外端口保留在 `runtime.api.<能力>`；Mapper 跟随所属运行能力 |
| `commerce-app` | `http.<业务>.<能力>`、`runtime.event`、`runtime.compensation`、`runtime.scheduling`、`runtime.monitoring`、`observability.<能力>`、`configuration.<能力>` | HTTP 路径不由 Java 包名决定；组合根仍为 `app.CommerceApplication` |

`merchant`、`trade`、`inventory`、`fulfillment`、`aftersales`、`marketing`、`order`、`shared-kernel` 和 `architecture-tests` 目前保持紧凑结构。`marketing` 与 `order` 是纯规则模块，`shared-kernel` 只放稳定共享值，不作为通用 Service 仓库。

## 迁移与维护约束

移动 Mapper 时同步 XML 的 `namespace`、`resultType`、`parameterType` 等全限定名；移动包级可见的协作者时优先让紧密协作者仍在同一包，仅在确需跨包调用时扩大可见性。Spring 组件扫描、HTTP 映射、MyBatis 映射和测试发现都须重新验证。历史 Flyway 迁移不可为排版或包名迁移而改写。

Java 排版沿用 Spring Java Format 0.0.39；Mapper XML 与 POM 沿用仓库既有 XML 排版。包细分属于源码兼容性变更，外部 Java 使用者升级时需要替换 import；本项目 HTTP、数据库及事件契约不随包移动改变。
