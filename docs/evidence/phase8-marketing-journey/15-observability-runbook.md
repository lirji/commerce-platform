# 观测与运维

复用/v1/platform/runtime和commerce.lanes.*；平台运维专用EVENT_RUNTIME_METRICS_READ能力，租户管理员不隐含平台权限。journeys登记Backlog supplier后不再是null/NaN，含到期实例+生命周期scan、最老资格年龄、未过deadline的隔离实例+隔离scan。共享健康缓存5秒，无tenant标签。

原告警：LANE_BACKLOG_AGE（最老>300s）、LANE_ROTATION_SLOW（整圈>300s）、LANE_STARVATION（开始等待>30s）、LANE_QUARANTINE_GROWTH/LANE_DEPENDENCY_UNAVAILABLE。规则名称和阈值以BackgroundRuntime源码为准，见最终回归。新增供应器未建立第二套监控平台。

排查顺序：平台聚合找到journeys backlog/rotation/schedule→tenant管理员列表与history确认fixedversion/currentNode/deadline/failureClass→查原actionRef owner钱包/台账→区分DEFERRED依赖恢复与FAILED配置/业务冲突→隔离仅在deadline内原节点retry，保留审计。不要全程replay、修改definition_json、直接清零steps或手动改action source。

- WAIT未到期属于正常；due_at资格不是精确执行SLA。到期积压看最老年龄和调度线程延迟。
- 大量DEFERRED：查MySQL连接/锁等待和共享breaker，依赖恢复后原节点自动重试；不能耗毒预算强制隔离。
- NODE_FAILED五次：修复既有业务约束后/admin/journey-instances/{id}/retry，Idempotency-Key明确；已发生效果不逆转。
- 历史PARTIAL/LEGACY_PARTIAL：核对OLD混跑/回退，不补写无法证明的事实。
- 代码回退兼容nullable列与索引，旧节点集合保留；已发权益由业务补偿。扩展表不DROP，未授权任何生产部署。

容量边界：每实例最长30天、DAG32节点、逐页50、每tick200次尝试，失败有deadline；trace增长含多次DEFERRED。保留期限需真实业务/法务决定，目前没有新Journey清理策略，不能伪造统一天数。关系库权威，索引与聚合开销由13的本地测量说明。
