package com.lrj.commerce.journey.api;

import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.campaign.rule.api.RuleNode;
import com.lrj.commerce.benefit.entitlement.api.EntitlementApi;
import java.time.Instant;
import java.util.List;

/** 旅程只执行已批准的有限节点，版本和持久检查点决定恢复行为。 */
public interface JourneyApi {

	enum Kind {

		WAIT, DECIDE, GRANT, COUPON, NOTIFY, END

	}

	enum Trigger {

		MANUAL, ORDER_PAID, MEMBER_REGISTERED, LEVEL_CHANGED, SEGMENT_ENTERED, BIRTHDAY, DORMANT, REPURCHASE,
		CART_ABANDONED

	}

	enum State {

		RUNNING, WAITING, ISOLATED, COMPLETED, CANCELLED, TIMED_OUT

	}

	/** 图错误是配置协议码，与业务规则不命中及运行故障分开。 */
	enum ValidationCode {
		INVALID_DEFINITION, MISSING_START_NODE, DUPLICATE_NODE, UNKNOWN_NODE_TYPE, INVALID_WAIT,
		INVALID_CONDITION, UNSUPPORTED_ACTION, INVALID_EDGE, MISSING_TARGET, UNREACHABLE_NODE, CYCLE_NOT_SUPPORTED
	}

	record ValidationIssue(ValidationCode code, String nodeId) {
	}
	record Validation(boolean valid, List<ValidationIssue> issues) {
	}
	record Preview(String memberId, String orderId, Instant at) {
	}
	record PreviewStep(String nodeId, Kind kind, String decision, String nextNode) {
	}
	record PreviewResult(String journeyId, long version, Instant evaluatedAt, List<PreviewStep> path, String stopReason) {
	}

	/** 结构有效后再核对本租户固定权益引用，无数据库写入。 */
	Validation validate(Actor actor, Definition definition);

	/** 只模拟当前事实，遇WAIT明确返回未来依赖，不执行任何真实动作。 */
	PreviewResult preview(Actor actor, String id, long version, Preview input);

	record CouponRef(String definitionId, long version) {
	}

	record Node(String id, Kind kind, Integer seconds, String next, RuleNode rule, String yesNext, String noNext,
			EntitlementApi.Ref benefit, String title, String body,
			@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) CouponRef coupon) {
		public Node(String id, Kind kind, Integer seconds, String next, RuleNode rule, String yesNext, String noNext,
				EntitlementApi.Ref benefit, String title, String body) {
			this(id, kind, seconds, next, rule, yesNext, noNext, benefit, title, body, null);
		}
	}

	record Controls(String segmentId, RuleNode entryRule, int maxEntries, int entryWindowSeconds, int notificationLimit,
			int notificationWindowSeconds) {
	}

	record Lifecycle(int thresholdDays, int cartDelaySeconds, int scanIntervalSeconds, int conversionWindowDays) {
	}

	record Definition(String journeyId, long version, String storeId, String name, Trigger trigger, Instant validFrom,
			Instant validTo, int maxDurationSeconds, String entry, List<Node> nodes,
			@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Controls controls,
			@com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Lifecycle lifecycle) {
		public Definition(String journeyId, long version, String storeId, String name, Trigger trigger,
				Instant validFrom, Instant validTo, int maxDurationSeconds, String entry, List<Node> nodes,
				Controls controls) {
			this(journeyId, version, storeId, name, trigger, validFrom, validTo, maxDurationSeconds, entry, nodes,
					controls, null);
		}

		public Definition(String journeyId, long version, String storeId, String name, Trigger trigger,
				Instant validFrom, Instant validTo, int maxDurationSeconds, String entry, List<Node> nodes) {
			this(journeyId, version, storeId, name, trigger, validFrom, validTo, maxDurationSeconds, entry, nodes,
					null);
		}
	}

	record Scan(String journeyId, long journeyVersion, String status, String memberCursor, Instant createdBefore,
			Instant nextDue, long scanned, long enrolled, int attempts, String errorCode, long version) {
	}

	record ScanRetry(long expectedVersion, String reason) {
	}

	/** 持久扫描状态与人工恢复均有界且受租户约束。 */
	List<Scan> scans(Actor actor, String after, int limit);

	Scan retryScan(Actor actor, String key, String id, long version, ScanRetry input);

	record EffectSummary(String journeyId, long enrolled, long completed, long notified, long entrySuppressed,
			long notificationSuppressed) {
	}

	record View(Definition content, String status, long lockVersion) {
	}

	/** 仅内部受控调用：准备在业务事务外判权，execute只进入原原子命令边界。 */
	@FunctionalInterface interface PreparedEnrollment { Instance execute(); }
	PreparedEnrollment prepareEnroll(Actor actor, String key, Start input);

	record Start(String journeyId, long version, String memberId, String eventKey) {
	}

	record Instance(String instanceId, String journeyId, long journeyVersion, String memberId, String orderId,
			String currentNode, State status, Instant dueAt, Instant deadline, int steps, int attempts, String result,
			long version) {
	}

	record Notification(String notificationId, String title, String body, Instant createdAt) {
	}

	/** 成功和等待为已提交证据，失败仍保留原逻辑序号，不重放已完成节点。 */
	enum StepState {
		EXECUTING, COMPLETED, WAITING, FAILED, DEFERRED, ISOLATED, STOPPED
	}

	record Step(long transitionVersion, int ordinal, String nodeId, Kind kind, StepState status, Instant startedAt,
			Instant completedAt, String nextNode, Instant wakeAt, String decision, String outcome, String actionRef,
			String failureClass) {
	}

	record History(Instance instance, Definition definition, String triggerKey, String traceCoverage, List<Step> steps) {
	}

	/** 固定历史版本与本人/租户范围；游标按执行前实例版本稳定排序。 */
	History history(Actor actor, String id, long afterVersion, int limit);

	/** 创建不可变草稿并检查DAG和权益引用。 */
	View create(Actor actor, String key, Definition input);

	/** 内容只能通过创建新版本修改。 */
	List<View> definitions(Actor actor, String after, int limit);

	/** 审批和发布包含预期版本与审计。 */
	View change(Actor actor, String key, String id, long version, long expected, String action);

	/** 手工入组只允许手工触发版本，eventKey在版本内唯一。 */
	Instance enroll(Actor actor, String key, Start input);

	/** 管理员与本人分别限定查询范围。 */
	List<Instance> instances(Actor actor, String after, int limit);

	/** 取消仅停止后续节点；已有业务效果不会被隐藏。 */
	Instance control(Actor actor, String key, String id, String action);

	/** 站内触达从数据库读取，不能指定他人的会员ID。 */
	List<Notification> notifications(Actor actor, String after, int limit);

	int pump(Actor actor);

	int tick();

	/** 全额退货先取消后续节点，再由权益模块冲正已产生效果。 */
	void cancelForOrder(String tenant, String order);

	/** 执行指标按事件发生时间聚合，不推断销售归因。 */
	List<EffectSummary> effects(Actor actor, String store, Instant from, Instant to, String after, int limit);

}
