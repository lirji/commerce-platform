# Phase 8 契约

在已确认计划及架构内执行；既有Journey API与Node/Definition/Instance JSON保持。新增路由位于原/v1。UTC ISO时间、认证Actor tenant唯一来源。

## 校验/预览

- POST /admin/journeys/validate：body原Definition，admin，纯校验无Commands/idempotency。200返回 `{valid,issues:[{code,nodeId}]}`；结构错误不写库；最多有界issue。code为明确enum：INVALID_DEFINITION、MISSING_START_NODE、DUPLICATE_NODE、UNKNOWN_NODE_TYPE、INVALID_WAIT、INVALID_CONDITION、UNSUPPORTED_ACTION、INVALID_EDGE、MISSING_TARGET、UNREACHABLE_NODE、CYCLE_NOT_SUPPORTED。有效图再验证店铺与固定权益引用；tenant越权资源404。
- create/publish仍原接口，内部requireValid复用同图校验，400 INVALID_INPUT message携带稳定validation code；发布还重检店铺、固定权益有效窗，防止草稿审批后依赖失效。
- POST /admin/journeys/{id}/{version}/preview：admin，body `{memberId,orderId?,at?}`。member与order必须本tenant、同member/店铺；at仅是显式评估时间，会员事实依然当前值；不作未来预测。返回 `{journeyId,version,evaluatedAt,path:[{nodeId,kind,decision,nextNode}],stopReason}`。遇WAIT立即返回FUTURE_DEPENDENT；ACTION显示ACTION_NOT_EXECUTED并走后继；未知事实以RULE_UNKNOWN终止；结束COMPLETED。不得入组、扣quota或发通知。

## 执行历史

GET /admin/journey-instances/{id}/history 与 /journey-instances/{id}/history，cursor afterVersion默认-1，limit默认50且1..50。admin可读tenant内；member只读本人，跨tenant/他人404。返回 `{instance,definition,triggerKey,traceCoverage,steps}`。definition固定历史版本，traceCoverage明确COMPLETE/LEGACY_PARTIAL/PARTIAL。

Step字段：transitionVersion、ordinal、nodeId、kind（执行前配置损坏可空）、status（EXECUTING/COMPLETED/WAITING/FAILED/DEFERRED/ISOLATED/STOPPED）、startedAt、completedAt、nextNode、wakeAt、decision、outcome、actionRef、failureClass。成功step+效果+checkpoint同事务；回滚尝试不伪造成已执行；失败记录只能对应未推进原版本。新step全tenant-scoped。

## 生命周期/幂等/失败

定义DRAFT→IN_REVIEW→APPROVED→PUBLISHED；拒绝REJECTED，pause→PAUSED，publish恢复；新发布停旧入组。实例RUNNING/WAITING→RUNNING/WAITING/COMPLETED/CANCELLED/TIMED_OUT；非瞬时失败第五次ISOLATED；ISOLATED原deadline内retry→RUNNING，cancel→CANCELLED，过期→TIMED_OUT。取消不冲正历史效果，原订单退款权益能力仍由原owner负责。

entry：版本内ONCE_PER_TRIGGER；action：领域来源hash(instance/node)，DAG每node最多访问一次；禁止全程replay与inflight迁移。Branch MATCH→yes，NO_MATCH→no，UNKNOWN→终止。图/规则版本不被条件修改。

## 运维/测试

journeys backlog来自同lane登记，无新增高基数标签。due/oldest/quarantine包含实例与生命周期扫描；正常未来WAIT不告警。新增trace错误、重试、行动结果必须稳定码，无原异常payload入库。P8 J1–18必需，JRN1–20证据引用逐项记录；保留旧Phase2–7回归和已知限制。
