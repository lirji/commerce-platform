# 积压、规模与容量

状态：PASS_WITH_LIMITATIONS（本地样本，非生产认证）。实际3线程EventWorker/8连接应用读取真实MySQL并逐项提交END节点、effect和step，不用内存Mock；入组/业务链另外用真实API验证，批量SQL只是容量夹具。

| due | 清空秒 | step/s | scheduling P50 ms | P95 ms | 样本 |
|---:|---:|---:|---:|---:|---|
| 100 | 6.127 | 16.32 | 2864 | 5756 | 新tenant干净复测 |
| 1000 | 48.759 | 20.51 | 27461 | 47098 | 新tenant干净复测 |
| 10000 | 469.990 | 21.28 | 235293 | 446891 | 首轮，无故障trigger阶段；同时有独立库全套回归 |

延迟为同一批释放UTC资格时间到每条step提交记录completed_at，吞吐包含真实1秒固定延迟和轮转预算。共享dev_infra、非专用硬件；全局MySQL计数包含其他测试负载，不当单进程DB成本。原1000首轮受并行故障trigger/DDL元数据锁互扰（74.945s），保留原始记录，不作为主容量数字；隔离recovery库并无trigger后以新tenant复测，不重置/replay任何成功实例。

50k合成WAITING未来记录最后均steps0、未领取。容量夹具直接入表以隔离查询与执行压力，不声称50k全部通过真实API完整入组；真实WAIT持久重启由25秒JVM测试证明。没有百万计时器/生产SLO承诺。

热点：hot5000、normal5同时释放，normal在0.726秒清空，hot尚余4991。期间真实sandbox渠道PAID由payment车道后台对账为order PAID，event车道生成fulfillment Inbox；journeys/payments/events最大开始延迟分别15/61/17ms（此进程测试期间观测）。正常mixed场景journeys一轮500ms、整圈92ms，backlog供给器非null（4975/oldest1s/quarantine0快照）。曾故障测试互扰的maxDuration30421ms和otherFailures1保留为测试噪声，不宣称500ms为硬上限。

查询：旧OR单tenant未来50k扫描全部50000行，28ms；同条件改分状态/资格范围索引后PK投影约0.06ms、实际完整Mapper投影计划返回0行，见results/due-query-plan.md。每分支只5个候选、最终去重选5；global due发现使用idx_journey_global_due，未来WAIT不进入候选扫描；deadline分支保留已过deadline即使due未来的终止。V45仅扩展索引，旧SQL仍可用。

容量模型：新实例E、平均成功N步、额外失败R次，则trace增长E*N+R；成功≤32（截止停止可33），额外DEFERRED受deadline但不人为伪造固定次数限额。现场ANALYZE估计instance66934行，data13156352B/index30949376B；step17225行，data2572288B/secondary index0。当前END密集样本约659B/instance与149B/trace（页分摊粗估，不是每种action的精确行大小），业务权益/命令/Outbox/审计增长另算。未来需要真实入组率/节点数/失败分布/保留期限才能给日增长与生产预算。

Backlog聚合缓存5秒但仍按active数据统计，并非免费O(1)指标。无已批准历史保留期，未新增Journey清理或删掉仍会恢复的历史；保留期限/归档属于后续产品治理。运行时正常资格依赖数据库、时区连接固定UTC；时间预算为合作检查和事务/语句超时配置，驱动/服务端取消不可当强制500ms抢占。

可复跑：scripts/backlog_scale.py、warm_recheck.py、probe_support.py。compact结果results/scale.csv、scale-results.md及SQL计划；private日志.local/phase8-backlog-scale.log和phase8-warm-recheck.log。
