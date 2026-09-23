# 首批内核契约 v1

这是新平台的内部 Java 契约，不兼容声明旧 Drools DTO，也不接收来自浏览器的可信事实。Owner：public-engineering-workflow contracts。外部 HTTP、规则 JSON、数据库 schema 将在各自切片冻结。

## C1 值与作用域

标识为 1–64 个 ASCII 字母、数字、点、下划线、冒号或连字符；不自动 trim/大小写转换。Money 仅 CNY，BigDecimal 精确两位，范围 0..999999999999.99，禁止静默四舍五入；数值表示 precision≤18、scale 在 -12..6，避免极端指数造成资源消耗；整数数量 1..10000；每行总额及汇总均须不溢出。失败抛 DomainException，稳定 code：INVALID_INPUT / LIMIT_EXCEEDED / SCOPE_MISMATCH / ILLEGAL_TRANSITION。没有 HTTP 状态语义。

## C2 动态规则

Fact 是 Decimal 或 Text（最长 256 字符），事实 Map 最多 64 项；数字 precision≤18、scale 在 -6..6，防超大指数/长度。条件树为 Compare、All、Any、Not；比较运算 EQ、GT、GTE、LT、LTE，文本仅 EQ。每棵树最多 128 节点、深度 8，组合子项 1..16；规则集最多 100 条。构造及 evaluate 都要拒绝越界。条件字段使用 C1 标识规范。

三值：MATCH、NO_MATCH、UNKNOWN。缺失字段或事实类型不符为 UNKNOWN；NOT UNKNOWN=UNKNOWN；ALL 遇 NO_MATCH 为 NO_MATCH，否则有 UNKNOWN 为 UNKNOWN；ANY 遇 MATCH 为 MATCH，否则有 UNKNOWN 为 UNKNOWN。只有 MATCH 有资格。纯计算没有动态脚本、时钟读取、IO；时间/事实由调用方传入不可变快照。

CampaignOffer 包含 tenantId/merchantId/storeId、campaignId、version>0、有效期 [from,to)、minimumSpend、discount>0 和 Condition。DecisionRequest 包含同一作用域、memberId、Instant、cart lines、事实与候选活动。候选只能来自同一作用域，跨作用域整单拒绝。SKU 的价格由未来商品适配器提供；当前内核不会把调用方价格当经过权限验证的商品价格。

## C3 报价

仅单商家单店、CNY，1..100 行，lineId 唯一，输出按 lineId 排序。每行 lineId/skuId/unitPrice/quantity；原总额精确计算。每个候选 ID 唯一，版本随结果保留；开始时刻可用，结束时刻不可用。先校验候选数量和重复 ID，再逐项输出 reason：OUTSIDE_VALIDITY / BELOW_MINIMUM / CONDITION_NO_MATCH / CONDITION_UNKNOWN / ELIGIBLE。

本阶段优惠作用于整单：每个合格候选实际优惠=min(配置优惠,总额)；择实际优惠最大者，平局按 campaignId 字典序。仅应用一项，不声称全局叠加最优；未选中的合格项仍是 ELIGIBLE。总额零时不选择优惠。

报价包含 gross/discount/payable、selectedCampaignId/version、逐候选 reason、逐行 gross/discount/payable。优惠按各行原金额占比向下取整到分，余分按小数余数从大到小、lineId 破同分分配，保证行金额非负和分摊总和严格相等。集合深层不可变。相同输入（含事实/时间/版本）不受候选或购物车顺序影响。

## C4 订单生命周期

OrderLifecycle 是不可变领域状态值，不是持久订单聚合。初始 PENDING_PAYMENT；事件：START_PAYMENT→PAYMENT_IN_PROGRESS；无支付开始时 REQUEST_CANCEL→CANCELLED；已开始支付 REQUEST_CANCEL→CLOSING；PAYMENT_CONFIRMED 可使待付/支付中/CLOSING→PAID；仅 CLOSING+PAYMENT_ABSENCE_CONFIRMED→CANCELLED。支付事实来源须由未来支付用例验证。

PAID+START_FULFILLMENT→FULFILLING；FULFILLING+CONFIRM_DELIVERY→COMPLETED。其他迁移一律拒绝；PAID 收到重复 PAYMENT_CONFIRMED 也拒绝，重复消息应在 Inbox 幂等层去重。version 从 0 开始，每次成功迁移+1，溢出拒绝。超时本身不是 PAYMENT_ABSENCE_CONFIRMED。售后是独立聚合，不把已完成订单回退为待付。

## 后续契约族（尚未冻结）

会员/商店鉴权、商品版本、活动发布、报价持久化、预占、支付请求/回调、Outbox envelope、履约/售后。事件计划包含 eventId、schemaVersion、tenantId、aggregateId/version、occurredAt、correlationId 和最小载荷；该草案不授权发布真实事件。持久化并发条件更新及幂等键摘要不在纯状态值内伪造。
