# 多实例结果

Phase 6 的两个 Spring 应用实例共享 MySQL、重复泵送 CREDIT 事件，最终单条 grant、单条营销执行的测试仍在全量回归内。Phase 7 对 OLD `df955f1` + NEW 当前 jar 共享数据库做了真实运行：旧节点写订单和支付事实，新节点泵送到 CREDIT 可用；新事件被旧节点保留，再由新节点唯一消费。详情见 `05-rolling-compatibility.md`。

新增 `twoNewInstancesIssueOneCampaignCoupon` 启动两个真实 Spring 上下文共用 MySQL，同时泵送支付事件；最终订单对应营销执行 GRANTED、券来源只一条、issued=1。该用例包含在最终干净应用 278 项中。

另在两个独立 java -jar 进程 8623/8624 上，每种提供者 120 个独立用户并发 12、额度 30：CREDIT 与 COUPON 均 30 成功/90 409、reserved=0/issued=30/实际 grant=30。同步确认与异步投影分别保留其原 owner，未引入共享 JVM 锁替代数据库约束。最终 CREDIT/Coupon 独立批次死锁/锁超时增量均 0；全局累计等待分别 143 次。方法分段与负载 CSV 见性能证据。
