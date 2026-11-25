# LC-02 首店范围核验证据

核对日期 2026-11-25。商城源码基线 `5c70c83`，交易中心 `d85d392`，营销 `4f27744`。实际目录查询使用商城本机 `127.0.0.1:8602`；交易来源分析使用当前源码及既有隔离联调证据。原计划和各片状态见[实施切片](../../design/lc02-store-pilot/IMPLEMENTATION_SLICES.md)。

## S1 门店与商品

| 验收点 | 结果 | 证据 |
| --- | --- | --- |
| 本机目录可读 | PASS | 用现有本机凭据按每页一条逐页读取商户、门店和商品。初始化租户返回一个启用商户、一家启用门店、六个已发布 SKU，SKU 均属于该门店，版本可查。只执行 HTTP GET |
| 目录鉴权及缺失处理 | PASS | 匿名门店列表返回 401，已认证查询不存在的门店目录返回 404。另外两份历史本机测试凭据返回 401 |
| 两店与 SKU 数据隔离 | PASS | `StoreAccessTest.pilotDirectoryKeepsPublishedSkuInsideItsStoreAndTenant` 用真实 HTTP 和专用 MySQL 按游标读取同商户两家门店。两店各只返回自己的 SKU，门店版本和商品修订可查，错租户返回 404 |
| 真实商家、组织、参与店和对照店 | 待签收 | 当前可用本机目录只有一家初始化门店。本地项目的签收文档仍缺获授权环境、实际组织及商户、参与店和同组织对照店、指定 SKU 的确认记录 |

商城 `commerce-app` 全量回归 478 项通过，5 项可选基准或剖析用例按环境跳过。最终运行使用锁定的 Auth SDK `109b1ed`、独立 MySQL 8.4.11 和 CI 的 `utf8mb4_bin` 及独立迁移身份。早期执行遇到测试选择器的上游模块保护、共享 SDK 缓存版本和隔离库默认排序规则，调整验证环境后重跑成功，专用数据库已回收。

本机清单和日志见商城仓的 `.local/lc02-store-pilot/live-directory.json`、`.local/lc02-store-pilot/directory-verify.log`。精确提交的远端检查取 `.local/lc02-store-pilot/github-actions.json`。

## S2 原生订单的门店来源

| 核对点 | 结论 | 证据 |
| --- | --- | --- |
| 商城订单 | 已证实保存商城租户、商户、门店和商城报价引用 | [商城完整订单写入](../../../order-runtime/src/main/resources/mappers/ordering/OrderMapper.xml) |
| 原生交易订单 | 已证实保存交易租户、原生订单 ID、原生报价、逻辑收款商户和发货仓。当前标准下单参数提供交易报价、仓库、地址及可选裂变令牌 | [原生订单建表](../../../../transaction-center/transaction-server/src/main/resources/db/migration/V005__order_foundation.sql)、[交易下单接口](../../../../transaction-center/transaction-server/src/main/java/com/lrj/trade/server/TradeController.java) |
| 已有交易到营销的联调 | 有同一原生订单的支付、退款回执和资金绑定证据。营销报价声明中的门店来自联调脚本选定的测试范围。该证据覆盖绑定和回执机制 | `~/.local/share/marketing-delivery/platform-integration-20261124/trade-binding-e2e/` 下的 `native-evidence.json`、`binding-result.json` 和 `verify.py` |
| 逐单门店来源 | 本轮在商城、交易入口及现有联调证据内未找到把同一原生交易订单连到权威商城门店的受信持久引用。仍需核对目标环境的实际下单入口和部署契约 | 商城订单、交易订单和营销报价三处当前来源，及[营销 LC-02 签收清单](../../../../marketing-lowcode-platform/docs/delivery/lowcode-commercialization/READINESS.md#lc-02-试点签收单) |

下阶段用一个已授权商家范围和一笔按实际接入路径创建的订单核定 S2。若实际入口已有可复核的关联，记录原生交易订单与商城门店的查询路径和证据。若实际入口需要改造，先由掌握该关联的下单方与交易中心确定写入责任、受信身份和绑定时序，再进入 S3。真实支付、分次退款与出资分摊仍由交易与财务按原计划签收。

## S3 商城报价来源与交易引用

| 验收点 | 结果 | 证据 |
| --- | --- | --- |
| 商城报价读取 | PASS | `CheckoutPilotQuoteMySqlTest` 通过真实 HTTP 和独立 MySQL 验证旧报价所含的门店、人民币分值和商品修订，以及错凭据、错租户、重复授权头、对照店、非活动商品和冻结门店。商城应用 479 项通过，5 项按环境跳过 |
| 交易引用唯一 | PASS | `CheckoutPilotQuoteTest` 通过隔离 MySQL 与契约 HTTP 对端验证实际 V034、同一报价并发重放、主体冲突、交易租户隔离及依赖异常 |
| 两个打包进程联跑 | PASS | 商城和交易候选应用用独立 MySQL、真实签名 JWT 和本机 JWKS 联跑。参与店报价返回单价 5900 分，交易写入一条引用并返回稳定回执。对照店在商城返回 404、在交易返回 409。错误机器返回 403 |
| 冻结优惠和支付准入 | 待实施 | 接入带版本的营销签名、交易优惠落价、一次性关联原生订单及支付前资金预留。报价准备回执仅证明来源与引用 |

两侧候选应用和隔离数据库均已回收。商城仓 `.local/lc02-store-pilot/checkout-pair-result.json` 保留联跑结果，`checkout-verify.log` 保留商城回归。交易侧验证日志位于对应任务工作树的 `.local/lc02-store-pilot/quote-reference-verify.log`。[报价来源契约](../../design/lc02-store-pilot/CHECKOUT_CONTRACTS.md)记录配置、权限和后续接线。
