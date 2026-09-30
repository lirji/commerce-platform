# B 端匹配增量契约

本文件仅定义2026-09-30增量；既有域契约、CENTRAL_*_ACCESS 和端点源文件仍为原能力权威。

1. 新增 GET `/v1/platform/me`：仅 PLATFORM_OPERATOR；返回认证边界的 Actor（tenantId/actorId/role/channel/executionId），不附赠任何租户能力。ADMIN/MEMBER/OPERATOR 为403，匿名/无效凭据401。原 `/v1/me` 与 `/v1/runtime-capabilities` 授权不变。前端登录先读原 /me，仅403时尝试 /platform/me；平台页只请求 /platform/*。
2. 用户明确要求刷新不清缓存，覆盖旧 S10「凭据只内存、不进浏览器存储」限制：旧 bearer 仅当前 tab sessionStorage，刷新后服务端复核身份；不存角色/权限快照作为权威、不进 localStorage/URL/日志。失效/退出清除，网络故障可重新恢复；浏览器拒绝存储则说明无法恢复，仍允许内存登录。
3. Hash URL保存列表游标/主页签/非敏感筛选；不持久化表单、业务账本或自动重发命令。
4. 恢复 UI 复用 RuntimeRecoveryController：work-types 返回 `{workType,actions}[]`；stopped 返回 RecoverableWork.Stopped[]，workType必传、after字符串、limit默认50、failureClass可选；recoveries POST为 `{workType,action:RETRY|SKIP,workIds,expectedFailureClass,reason}`，1–50唯一显式ID、reason必填；回执 `{workType,action,applied,rejected,outcomes:[{workId,result,previousState,newState,rejection}]}`；history after为非负long，不是字符串workId。
5. 重放 UI 复用 EventReplay：classifications 的消费者/types/effects/evidence及 unprocessed/reprocess 安全门；dry-run只读POST为 `{consumer,eventTypes,from,to,mode,maxEvents}`，区间≤31天且to≤现在，类型1–10、maxEvents1–10000；响应 consumer/mode/gate/byType/events/alreadyProcessed/wouldExecute/capped/maxEvents。create相同范围加jobId/reason；创建必须与最后一次成功且 gate.allowed 的试运行范围一致，修改范围需重新试运行。服务端每项仍复核安全门。
6. 重放任务列表按 jobId 字符串游标；详情字段以 ReplayMapper.Job 为准，控制 `{action:PAUSE|RESUME|CANCEL,expectedVersion,reason}`。创建回执 RUNNING 是任务已受理，不是事件全部执行完成。取消不撤销已发生效果。
7. 新运行时写客户端冻结原路径/体/幂等键；网络/5xx/无法解析响应属于未知结果，保留原意图，可原样重试。401卸载页面/会话；403后不能据此断言此前未知命令未发生；409展示当前冲突，保留可核对输入。恢复逐项拒绝视为部分结果，不宣称全部成功。
8. 平台 /runtime DTO来自 BackgroundRuntime.View：observedAt/events/lanes/replay/retention/alerts，只读全局聚合，不展示租户ID/事件载荷；租户恢复页不使用此接口。

无需新增数据库迁移、后端授权中心 API 或刷新 token 协议；不自动延长凭据期限。所有演示业务数据通过数据库种子/真实API，接口fixture仅在显式测试边界。
