# CE05/06/07 共享Provider

用户已授权完整实施，Auth依赖c834d38→ad09213。本片只登记闭集共享API/HTTP路由和迁移，不宣布Journey/CE06/07全部完成。

`EmployeeAccess.Capability`定义18个journey/report、14个门店、10个OpsPage、8个event/runtime能力；每项明确family/resourceType。有限Source仅journey_instance.create2592060s及runtime.replay.create86460s，实际POST持久保存精确原Actor/identity/应用/环境/调用方/版本/截止元数据，无Token/ALLOW，无更新续期。`execution(actor,cap)`读取原引用，Owner每步必须相交Auth原/current Grant，`scope/lock`以五秒提交许可锁路由；SYSTEM journey policy与批准同分区版本单调且非STOPPED，LEGACY曾接管后不可回退。

V67–68登记族及实际审计类型、正内容版本和有限来源表/旅程私有字段；不接管任何实际tenant。ORDER_EXPIRE集合作业审计`order_expiry_batch`以实际key为目标，无伪store；Owner明细由V71+提供。OpsPage create/已有动作审计正内容版本，未创建preview只使用集合不伪造对象。CE06/07迁移保留V71+。

验证：任务专用Mavenrepo完整reactor install成功；独占MySQL49309新schema utf8mb4_bin实际应用全部68迁移。CentralCouponDeliveryMySqlTest14项回归与正在实施的CentralJourneyMySqlTest5项均0失败/错误/跳过（来源/原子效果测试也约束sharedprovider）。固定测试URL安全检查增加同前缀唯一隔离schema，禁止共用D2。早期默认collation失败schema保留，无改历史迁移/清共享库。

不可变证据与源摘要：本工作树`.local/journeys/shared-evidence-v1/{TEST-*.xml,shared-mysql-final.log,journey-owner-build.log,shared-mysql-bin.log,source-sha.json}`。后台与真实Auth跨进程、UI/PKCE还由后续Owner片验证，不以此Provider结果声明完成。
