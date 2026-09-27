# 第二权益：内部受控券

选择仓库已有 `CouponApi` 的 `SOURCE_ONLY` 定义。券是可核销金额优惠，有相对有效期；CREDIT 是整数权益台账，支付后通过事件转为可用。两者共享活动→规则→报价→订单→营销执行模型；只扩展权益引用、券额度预留与执行类型，没有第二套营销 Controller、规则器、调度器或发布流程。

| 能力 | CREDIT | COUPON |
| --- | --- | --- |
| 定义 | `benefit_definition` 整数单位 | `benefit_coupon_definition` 金额、相对有效期、受控来源 |
| 预留 | 下单事务 `benefit_grant=RESERVED` | 下单事务 `benefit_campaign_coupon_hold=HELD`，额度 `reserved+issued<=quota` |
| 发放 | 支付确认后 `benefit.grant.requested.v1` → AVAILABLE | 支付订单事务内 `reserved--, issued++`、写钱包 `source_type=CAMPAIGN`、状态 ISSUED |
| 幂等 | 订单 grant 唯一与 Inbox | `(tenant,orderId)` 预留主键及 `(tenant,CAMPAIGN,orderId)` 钱包来源唯一 |
| 额度 | CREDIT 已预留+已发 <= quota | 券 reserved+issued <= quota，其他来源发行同时检查 reserved |
| 失败恢复 | 原事件重试/隔离/恢复 | 支付事件原运行时重试；同事务失败回滚订单结算与发券；不能提供者本地无限重试 |
| 退款/撤销 | 原权益状态机 | 已发券不随订单退款隐式撤销；需要另行产品/补偿政策 |
| 历史重放 | 不允许扩大旧订单权益 | 来源唯一，当前不允许为了补发跨历史订单重放 |

活动只能配置一种权益，券模式要求相对有效期和受控来源。券新配置有明确滚动发布门禁；默认关闭。订单可以同时使用原有钱包券作为支付优惠，其占用与**活动送券**是不同业务含义和来源。
