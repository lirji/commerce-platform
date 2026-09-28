# P8-S3 验证结果

状态：PASS_WITH_LIMITATIONS。范围为共享journeys车道、查询、积压与本地容量；不是生产SLO认证。

- V45在既有Phase7+V44 schema上增量执行成功。50k未来WAIT保持steps0；旧OR扫描50000行，新范围索引同场景PK投影0行约0.06ms。完整Mapper和全局租户发现真实EXPLAIN见证据results目录。
- 实际EventWorker逐项提交100/1000/10000 due。无故障trigger干净复测100=6.127s、1000=48.759s；首轮10000=469.990s。所有实例完成且step/effect正确。
- hot5000/normal5：normal在0.726s清空，hot仍4991；同时真实payment后台对账、order PAID和fulfillment Inbox完成。开始延迟journeys15ms/payments61ms/events17ms。
- Runtime读到真实backlog/oldest/quarantine，supplier非null；5秒聚合缓存，无tenant高基数标签。
- 原1000首轮被故障探针DDL互扰，原始结果保留且不作为主容量；recovery库隔离后复测新tenant，不重置成功数据。

证据：[规模与限制](../../evidence/phase8-marketing-journey/13-scale-backlog.md)、results/scale.csv、SQL计划、backlog_scale.py/warm_recheck.py；private日志.local/phase8-backlog-scale.log、phase8-warm-recheck.log。
