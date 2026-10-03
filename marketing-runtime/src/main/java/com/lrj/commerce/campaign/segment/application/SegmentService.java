package com.lrj.commerce.campaign.segment.application;

import com.lrj.commerce.runtime.api.validation.ListFilter;
import com.lrj.commerce.campaign.segment.infrastructure.persistence.SegmentMapper;
import com.lrj.commerce.member.growth.api.MemberGrowthApi;
import com.lrj.commerce.marketing.api.*;
import com.lrj.commerce.kernel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import com.lrj.commerce.campaign.rule.api.MemberRuleFacts;
import com.lrj.commerce.campaign.rule.api.RuleNode;
import com.lrj.commerce.campaign.segment.api.SegmentApi;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.api.access.EmployeeAccess;
import static com.lrj.commerce.runtime.api.access.EmployeeAccess.Capability.*;
import com.lrj.commerce.runtime.api.validation.Inputs;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.event.Outbox;
import com.lrj.commerce.runtime.recovery.RecoveryAudit;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.work.FailureClass;
import com.lrj.commerce.runtime.work.RetryPolicy;
import com.lrj.commerce.runtime.work.TenantRotation;
import com.lrj.commerce.runtime.work.WorkLanes;

/** 单批数据与检查点同事务，最终仅插入快照头即可让完整成员集原子可见。 */
@Service
public class SegmentService implements SegmentApi {

	private final EmployeeAccess access;

	private final Outbox outbox;

	private final SegmentMapper mapper;

	private final MemberGrowthApi members;

	private final RuleDecisionPort rules;

	private final Commands commands;

	private final Clock clock;

	private final TransactionTemplate tx;

	private final TenantRotation segments;

	/** 人群车道：每次访问一个租户最多启动一个任务、处理一批100名会员、公告一批，每轮最多30步或500毫秒，租户轮转。 */
	public static final TenantRotation.Policy SEGMENTS = new TenantRotation.Policy(3, 50, Duration.ofMillis(500), 30);

	public SegmentService(SegmentMapper mapper, MemberGrowthApi members, RuleDecisionPort rules, Commands commands,
			Clock clock, PlatformTransactionManager manager, Outbox outbox, WorkLanes lanes, EmployeeAccess access) {
		this.access = access;
		this.outbox = outbox;
		this.mapper = mapper;
		this.members = members;
		this.rules = rules;
		this.commands = commands;
		this.clock = clock;
		tx = new TransactionTemplate(manager);
		tx.setTimeout(10);
		segments = lanes.rotation("segments", SEGMENTS, null);
	}

	/** 发布新定义会关闭周期刷新，运营确认新规则后再显式启用。 */
	public View create(Actor actor, String key, Definition input) {
		var permit = access.scope(actor, SEGMENT_CREATE);
		Inputs.require(input != null && input.version() > 0 && input.rule() != null, "人群定义无效");
		Identifiers.require(input.segmentId());
		Inputs.text(input.name(), 128);
		input.rule().requireTrustedFields();
		memberOnly(input.rule());
		Inputs.require(input.ttlSeconds() >= 300 && input.ttlSeconds() <= 86400
				&& (input.refreshSeconds() == 0
						|| (input.refreshSeconds() >= 60 && input.refreshSeconds() <= input.ttlSeconds()))
				&& input.maxMembers() >= 100 && input.maxMembers() <= 100000, "刷新周期或容量预算无效");
		return commands.runGuarded(actor, "segment.create", key, command(permit, input), View.class, () -> access.lock(permit), () -> {
			var current = mapper.lockRoot(actor.tenantId(), input.segmentId());
			String audience = "dyn-" + JsonCodec.hash(input.segmentId()).substring(0, 40);
			if (current == null)
				mapper.insertRoot(actor.tenantId(), input.segmentId(), audience, input.version(), now());
			else {
				Inputs.require(input.version() > current.currentVersion(), "新定义版本必须递增");
				mapper.advanceDefinition(actor.tenantId(), input.segmentId(), input.version(), now());
			}
			mapper.definition(actor.tenantId(), input, JsonCodec.write(input));
			access.lock(permit);
			access.auditVersion(actor, permit, "segment.create", key, input.segmentId(), input.version());
			return view(mapper.find(actor.tenantId(), input.segmentId()));
		});
	}

	/** 有界最新定义目录。 */
	public List<View> definitions(Actor actor, String after, int limit) {
		return definitions(actor, after, limit, ListFilter.none());
	}

	/** 只读条件先筛选再分页，保持原用例的权限复核。 */
	public List<View> definitions(Actor actor, String after, int limit, ListFilter filter) {
		filter.requireNoStatus();
		filter.requireNoTime();
		var permit = access.scope(actor, SEGMENT_READ);
		Inputs.page(after, limit);
		var rows = mapper.definitions(actor.tenantId(), after, limit, filter).stream().map(this::view).toList();
		EmployeeAccess.requireSame(permit, access.scope(actor, SEGMENT_READ));
		return rows;
	}

	/** 停止定时触发不会取消已经开始的任务，取消有独立命令。 */
	public View schedule(Actor actor, String key, String id, Schedule input) {
		var scope = access.scope(actor, SEGMENT_SCHEDULE);
		Identifiers.require(id);
		Inputs.require(input != null && input.expectedVersion() >= 0, "调度版本无效");
		var current = Inputs.found(mapper.find(actor.tenantId(), id));
		var permit = access.resource(actor, scope, fact(current));
		return commands.runGuarded(actor, "segment.schedule", key, command(scope, new Object[] { id, input }), View.class,
				() -> lockDefinition(actor, permit), () -> {
			var root = Inputs.found(mapper.lockRoot(actor.tenantId(), id));
			var d = definition(actor.tenantId(), id, root.currentVersion());
			Inputs.require(!input.enabled() || d.refreshSeconds() > 0, "手工定义不允许启用周期调度");
			// 调度开关的成功命令就是版本化政策批准；周期任务不继承批准人的临时Grant。
			var policy = input.enabled() ? new Policy(actor.tenantId(), id, root.currentVersion(), root.lockVersion() + 1,
					scope.identity() == null ? null : scope.route(), scope.identity(), now(), key) : null;
			if (mapper.schedule(actor.tenantId(), id, input, now(), policy == null ? null : JsonCodec.write(policy)) != 1)
				throw new DomainException(DomainException.Code.CONFLICT, "调度配置版本已变化");
			access.lock(permit.scope());
			access.auditVersion(actor, permit.scope(), "segment.schedule", key, id, root.currentVersion());
			return view(mapper.find(actor.tenantId(), id));
		});
	}

	/** 活动任务存在时返回同一任务，不额外堆积全库刷新。 */
	public Run refresh(Actor actor, String key, String id) {
		var scope = access.scope(actor, SEGMENT_REFRESH);
		Identifiers.require(id);
		var permit = access.resource(actor, scope, fact(Inputs.found(mapper.find(actor.tenantId(), id))));
		var execution = scope.identity() == null ? null : access.segmentExecution(actor);
		var source = new Source(execution == null ? Origin.LEGACY : Origin.MANUAL, actor, execution, null, key, now());
		return commands.runGuarded(actor, "segment.refresh", key, command(scope, id), Run.class,
				() -> lockDefinition(actor, permit), previous -> sourceGate(actor.tenantId(), previous).run(), () -> {
					var result = start(actor.tenantId(), Inputs.found(mapper.lockRoot(actor.tenantId(), id)), source);
					access.lock(permit.scope());
					access.auditVersion(actor, permit.scope(), "segment.refresh", key, result.segmentId(), result.definitionVersion());
					return result;
				});
	}

	/** 任务元信息不包含完整会员列表。 */
	public List<Run> runs(Actor actor, String id, String after, int limit) {
		var scope = access.scope(actor, SEGMENT_READ);
		Identifiers.require(id);
		Inputs.page(after, limit);
		var fact = fact(Inputs.found(mapper.find(actor.tenantId(), id)));
		access.resource(actor, scope, fact);
		var rows = mapper.runs(actor.tenantId(), id, after, limit);
		var afterPermit = access.scope(actor, SEGMENT_READ);
		EmployeeAccess.requireSame(scope, afterPermit);
		var current = fact(Inputs.found(mapper.find(actor.tenantId(), id)));
		if (!fact.equals(current)) throw denied();
		access.resource(actor, afterPermit, current);
		return rows;
	}

	/** 运维恢复审计：重试、取消等对停止工作的人工干预与状态变更同一事务记录。 */
	private com.lrj.commerce.runtime.recovery.RecoveryAudit audit;

	@org.springframework.beans.factory.annotation.Autowired
	void audit(com.lrj.commerce.runtime.recovery.RecoveryAudit audit) {
		this.audit = audit;
	}

	/** 取消保留已扫描但不可见的投影；重试从最后提交检查点继续。 */
	public Run control(Actor actor, String key, String id, String action) {
		var scope = access.scope(actor, SEGMENT_CONTROL);
		Identifiers.require(id);
		Inputs.require(Set.of("cancel", "retry", "retry-announcement").contains(action), "任务操作无效");
		var target = Inputs.found(mapper.runFind(actor.tenantId(), id));
		definition(actor.tenantId(), target.segmentId(), target.definitionVersion());
		var permit = access.resource(actor, scope, fact(target));
		var originGate = sourceGate(actor.tenantId(), target);
		return commands.runGuarded(actor, "segment." + action, key, command(scope, id), Run.class, () -> {
			access.lock(permit.scope());
			var actual = Inputs.found(mapper.lockRun(actor.tenantId(), id));
			if (!permit.fact().equals(fact(actual))) throw denied();
			originGate.run();
		}, () -> {
			var run = Inputs.found(mapper.lockRun(actor.tenantId(), id));
			if (action.equals("retry-announcement")) {
				if (mapper.retryAnnouncement(actor.tenantId(), id, now()) != 1)
					throw new DomainException(DomainException.Code.CONFLICT, "入组事件未处于隔离状态");
			}
			else if (action.equals("cancel")) {
				if (!Set.of("RUNNING", "ISOLATED").contains(run.status()))
					throw new DomainException(DomainException.Code.CONFLICT, "任务已终结");
				mapper.status(actor.tenantId(), id, "CANCELLED", null);
			}
			else {
				if (!run.status().equals("ISOLATED") || !clock.instant().isBefore(run.validUntil()))
					throw new DomainException(DomainException.Code.CONFLICT, "仅可重试未过期隔离任务，过期请取消后重新刷新");
				mapper.status(actor.tenantId(), id, "RUNNING", null);
			}
			originGate.run();
			access.lock(permit.scope());
			access.auditVersion(actor, permit.scope(), "segment." + action, key, run.segmentId(), run.definitionVersion());
			boolean announcement = action.equals("retry-announcement");
			audit.record(actor, "segment." + action, key, announcement ? "segment.announcement" : "segment.run", id,
					action.equals("cancel") ? "CANCEL" : "RETRY", announcement ? "ANNOUNCEMENT_ISOLATED" : run.status(),
					announcement ? "ANNOUNCEMENT_PENDING" : action.equals("cancel") ? "CANCELLED" : "RUNNING", null,
					null, com.lrj.commerce.runtime.recovery.RecoveryAudit.APPLIED, null);
			return mapper.runFind(actor.tenantId(), id);
		});
	}

	/** 每租户一轮最多新建一个任务并处理一批，避免手工泵绕过资源预算。 */
	public int pump(Actor actor) {
		var permit = access.scope(actor, SEGMENT_PUMP);
		// 手工推进资格与每个任务原来源分别校验，每个事务都重新获取当前推进资格。
		return pumpTenant(actor.tenantId(), segments.manual(), () -> {
			var current = access.scope(actor, SEGMENT_PUMP);
			EmployeeAccess.requireSame(permit, current);
			return () -> access.lock(current);
		});
	}

	/** 租户公平轮转，后台开关由既有EventWorker统一控制。 */
	public int tick() {
		return segments.run((after, limit) -> mapper.tenants(after, now(), limit), (tenant, run) -> pumpTenant(tenant, run, () -> () -> {}));
	}

	/** 瞬时失败（依赖不可用、锁冲突、超时）只延后不计次数，任务有效期终止重试；其他失败计次，5次隔离。 */
	private int pumpTenant(String tenant, TenantRotation.Run run, java.util.function.Supplier<Runnable> callerGate) {
		// 新建任务失败只影响该人群：依次尝试至多3个到期人群，首个成功即停止，一个坏人群不再挡住同租户其他人群。
		for (var id : mapper.due(tenant, now())) {
			run.attempted();
			try {
				boolean[] started = { false };
				var gate = callerGate.get();
				tx.executeWithoutResult(s -> {
					gate.run();
					var root = mapper.lockRoot(tenant, id);
					if (root != null && root.enabled() && !root.nextDue().isAfter(now())) {
						var policy = policy(tenant, root);
						start(tenant, root, new Source(Origin.SYSTEM, null, null, policy, policy == null ? null : policy.commandKey(), now()));
						gate.run();
						started[0] = true;
					}
				});
				run.succeeded();
				if (started[0])
					break;
			}
			catch (RuntimeException failure) {
				var type = FailureClass.of(failure);
				org.slf4j.LoggerFactory.getLogger(getClass())
					.warn("segment start retry segment={} failureClass={} type={}", id, type,
							failure.getClass().getSimpleName());
				if (run.failed(type))
					return 0;
			}
		}
		int count = 0;
		for (var id : mapper.pending(tenant, now())) {
			run.attempted();
			try {
				var gate = callerGate.get();
				Boolean done = tx.execute(s -> {
					gate.run();
					var run2 = mapper.lockRun(tenant, id);
					if (run2 == null || !run2.status().equals("RUNNING") || run2.availableAt().isAfter(now()))
						return false;
					batch(tenant, run2);
					gate.run();
					return true;
				});
				if (Boolean.TRUE.equals(done))
					count++;
				run.succeeded();
			}
			catch (RuntimeException failure) {
				var type = FailureClass.of(failure);
				boolean counted = !type.transientFailure();
				tx.executeWithoutResult(s -> {
					var row = mapper.lockRun(tenant, id);
					if (row != null)
						mapper.failed(tenant, id, counted, counted ? "RETRYABLE" : type.name(),
								now().plusMillis(counted
										? (1L << Math.min(row.attempts() + 1, 5)) * 1000
												+ java.util.concurrent.ThreadLocalRandom.current().nextInt(1000)
										: RetryPolicy.deferralMillis(
												java.util.concurrent.ThreadLocalRandom.current().nextDouble())));
				});
				org.slf4j.LoggerFactory.getLogger(getClass())
					.warn("segment batch retry run={} failureClass={} type={}", id, type,
							failure.getClass().getSimpleName());
				if (run.failed(type))
					return count;
			}
		}
		for (var id : mapper.announcements(tenant, now())) {
			run.attempted();
			try {
				var gate = callerGate.get();
				tx.executeWithoutResult(s -> {
					gate.run();
					var row = mapper.lockRun(tenant, id);
					if (row == null || row.entriesAnnounced() || row.entryAttempts() >= 5)
						return;
					var sourceGate = sourceGate(tenant, row);
					sourceGate.run();
					if (!clock.instant().isBefore(row.validUntil())) {
						mapper.announced(tenant, id, null, true);
						sourceGate.run();
						gate.run();
						return;
					}
					var entered = mapper.entered(tenant, row);
					for (var member : entered)
						outbox.append(tenant, "segment.member.entered.v1", JsonCodec.hash(row.runId() + "/" + member),
								1, new Entered(member, row.segmentId(), row.audienceId(), row.snapshotVersion(),
										row.definitionVersion()));
					mapper.announced(tenant, id, entered.isEmpty() ? null : entered.getLast(), entered.size() < 100);
					sourceGate.run();
					gate.run();
				});
				run.succeeded();
			}
			catch (RuntimeException failure) {
				var type = FailureClass.of(failure);
				boolean counted = !type.transientFailure();
				tx.executeWithoutResult(s -> mapper.announcementFailed(tenant, id, counted, now().plusMillis(counted
						? 30_000
						: RetryPolicy.deferralMillis(java.util.concurrent.ThreadLocalRandom.current().nextDouble()))));
				org.slf4j.LoggerFactory.getLogger(getClass())
					.warn("segment announcement retry run={} failureClass={} type={}", id, type,
							failure.getClass().getSimpleName());
				run.failed(type);
			}
		}
		return count;
	}

	private Run start(String tenant, SegmentMapper.Root root, Source source) {
		var active = mapper.active(tenant, root.segmentId());
		if (active != null) {
			sourceGate(tenant, active).run();
			return active;
		}
		var definition = definition(tenant, root.segmentId(), root.currentVersion());
		Instant started = now();
		var run = new Run(UUID.randomUUID().toString(), root.segmentId(), root.currentVersion(), root.audienceId(),
				root.snapshotSequence() + 1, "", 0, 0, "RUNNING", 0, null, started,
				started.plusSeconds(definition.ttlSeconds()), started, false, 0);
		var sourceGate = sourceGate(tenant, run, source);
		sourceGate.run();
		mapper.allocate(tenant, root.segmentId(), started.plusSeconds(Math.max(60, definition.refreshSeconds())));
		mapper.run(tenant, run, JsonCodec.write(source));
		sourceGate.run();
		return run;
	}

	private void batch(String tenant, Run run) {
		var sourceGate = sourceGate(tenant, run);
		sourceGate.run();
		if (!clock.instant().isBefore(run.validUntil())) {
			mapper.status(tenant, run.runId(), "FAILED", "EXPIRED");
			sourceGate.run();
			return;
		}
		var definition = definition(tenant, run.segmentId(), run.definitionVersion());
		var batch = members.scan(tenant, run.cursorMember(), 100, run.startedAt());
		if (run.processed() + batch.size() > definition.maxMembers()) {
			mapper.status(tenant, run.runId(), "FAILED", "MEMBER_LIMIT");
			sourceGate.run();
			return;
		}
		var condition = definition.rule().toCondition();
		var matched = batch.stream()
			.filter(m -> m.status().equals("ACTIVE")
					&& rules.evaluate(condition, MemberRuleFacts.from(m, null)) == Condition.Truth.MATCH)
			.map(MemberGrowthApi.Facts::memberId)
			.toList();
		if (!matched.isEmpty())
			mapper.matched(tenant, run, matched);
		int total = run.matched() + matched.size();
		mapper.checkpoint(tenant, run.runId(), batch.isEmpty() ? run.cursorMember() : batch.getLast().memberId(),
				run.processed() + batch.size(), total);
		sourceGate.run();
		if (batch.size() < 100) {
			mapper.publish(tenant, run, definition.name(), total);
			mapper.status(tenant, run.runId(), "COMPLETED", null);
		}
		sourceGate.run();
	}

	private enum Origin { MANUAL, SYSTEM, LEGACY }

	/** 周期政策由真实调度命令批准，之后固定定义与调度版本，不借用批准人的临时执行引用。 */
	private record Policy(String tenant, String segmentId, long definitionVersion, long scheduleVersion,
			EmployeeAccess.Route approvedRoute, EmployeeAccess.Identity approvedIdentity, Instant approvedAt, String commandKey) {}

	/** 原来源只在新任务创建时写一次，控制/推进和新定义均不得更换创建者。 */
	private record Source(Origin kind, Actor actor, EmployeeAccess.SegmentExecution execution, Policy policy,
			String commandKey, Instant createdAt) {}

	private Policy policy(String tenant, SegmentMapper.Root root) {
		if (root.schedulePolicyJson() == null) {
			// 旧周期任务仅在未接管的原权威下兼容，空来源不能自动成为中央系统政策。
			access.segmentPolicy(tenant, null);
			return null;
		}
		var policy = JsonCodec.read(root.schedulePolicyJson(), Policy.class);
		requirePolicy(tenant, root.segmentId(), root.currentVersion(), policy);
		if (policy.scheduleVersion() != root.lockVersion()) throw denied();
		return policy;
	}

	private void requirePolicy(String tenant, String segment, long version, Policy policy) {
		if (policy == null || !tenant.equals(policy.tenant()) || !segment.equals(policy.segmentId())
				|| policy.definitionVersion() != version || version <= 0 || policy.scheduleVersion() <= 0
				|| policy.approvedAt() == null || policy.commandKey() == null
				|| (policy.approvedRoute() == null) != (policy.approvedIdentity() == null)) throw denied();
		Identifiers.require(policy.commandKey());
	}

	private Runnable sourceGate(String tenant, Run run) {
		var json = mapper.source(tenant, run.runId());
		return sourceGate(tenant, run, json == null ? null : JsonCodec.read(json, Source.class));
	}

	/** 每次恢复从库读取原来源；原手工引用每批远程复核，周期政策只经过独立系统路由门禁。 */
	private Runnable sourceGate(String tenant, Run run, Source source) {
		definition(tenant, run.segmentId(), run.definitionVersion());
		if (source == null) {
			var permit = access.segmentPolicy(tenant, null);
			return () -> access.lock(permit);
		}
		if (source.kind() == null || source.createdAt() == null) throw denied();
		if (source.kind() == Origin.SYSTEM) {
			if (source.actor() != null || source.execution() != null) throw denied();
			if (source.policy() != null) requirePolicy(tenant, run.segmentId(), run.definitionVersion(), source.policy());
			var permit = access.segmentPolicy(tenant, source.policy() == null ? null : source.policy().approvedRoute());
			return () -> access.lock(permit);
		}
		if (source.actor() == null || !tenant.equals(source.actor().tenantId()) || source.policy() != null) throw denied();
		var original = access.scope(source.actor(), SEGMENT_REFRESH);
		if (source.kind() == Origin.MANUAL) {
			if (source.execution() == null || original.identity() == null
					|| !source.execution().equals(access.segmentExecution(source.actor()))
					|| !source.execution().identity().equals(original.identity())
					|| !source.execution().route().equals(original.route())) throw denied();
		} else if (source.execution() != null || original.identity() != null || source.actor().executionId() != null) throw denied();
		var permit = access.resource(source.actor(), original, fact(run));
		return () -> {
			if (source.execution() != null && !source.execution().expiresAt().isAfter(Instant.now())) throw denied();
			access.lock(permit.scope());
		};
	}

	/** 先锁定原权威及最新定义，旧回执也不能绕过权限或偷换目标内容版本。 */
	private void lockDefinition(Actor actor, EmployeeAccess.ResourcePermit permit) {
		access.lock(permit.scope());
		var root = Inputs.found(mapper.lockRoot(actor.tenantId(), permit.fact().id()));
		if (root.currentVersion() != permit.fact().version())
			throw new DomainException(DomainException.Code.CONFLICT, "动态人群定义版本已变化");
	}

	private EmployeeAccess.ResourceFact fact(SegmentMapper.Row row) {
		var definition = JsonCodec.read(row.definitionJson(), Definition.class);
		return new EmployeeAccess.ResourceFact(SEGMENT_READ.resourceType(), definition.segmentId(), definition.version());
	}
	private static EmployeeAccess.ResourceFact fact(Run run) {
		return new EmployeeAccess.ResourceFact(SEGMENT_READ.resourceType(), run.segmentId(), run.definitionVersion());
	}
	private static Object command(EmployeeAccess.ScopePermit permit, Object input) {
		// 执行nonce不参与意图摘要，重新认证仍用同一稳定主体和原业务输入重试。
		return permit.identity() == null ? input : new Object[] { input, permit.identity() };
	}
	private static DomainException denied() {
		return new DomainException(DomainException.Code.FORBIDDEN, "动态人群原执行来源不允许推进");
	}

	private Definition definition(String tenant, String id, long version) {
		return JsonCodec.read(Inputs.found(mapper.definitionJson(tenant, id, version)), Definition.class);
	}

	private View view(SegmentMapper.Row row) {
		return new View(JsonCodec.read(row.definitionJson(), Definition.class), row.audienceId(), row.enabled(),
				row.lockVersion());
	}

	private void memberOnly(RuleNode node) {
		if (node.kind().equals("COMPARE"))
			Inputs.require(!"orderAmount".equals(node.field()), "人群定义只能依赖会员事实");
		else
			node.children().forEach(this::memberOnly);
	}

	private Instant now() {
		return clock.instant().truncatedTo(ChronoUnit.MILLIS);
	}

}
