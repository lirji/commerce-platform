# 动作集成

JourneyActions用显式Map<Kind,Action>注册GRANT/COUPON/NOTIFY，重复键启动失败、缺handler拒绝，不fallback。没有通用插件引擎。流程节点WAIT/DECIDE/END仍由Journey运行负责。

GRANT使用原EntitlementApi.grantFromJourney，固定benefit/version，sourceId=hash(instance/node)，真实额度预留/幂等/Outbox由权益owner执行；trace outcome BENEFIT_ACCEPTED关联grantId，明确REQUESTED不是最终AVAILABLE。随后事件消费台账一次GRANT且AVAILABLE才算纵向履约通过。

COUPON通过原CouponApi固定引用，记录couponId；NOTIFY为原本地数据库站内信及唯一tenant/instance/node约束，保留窗口频控与NOTIFY_SUPPRESSED。成功effect、cap、actionRef和检查点同事务；回滚不吞quota。不是外部消息发送成功保证。

支付链、并发权益、真实kill、生日券、通知/频控与原全额退款回归共同证明复用既有owner，未增加新的额度/积分/券台账权威。
