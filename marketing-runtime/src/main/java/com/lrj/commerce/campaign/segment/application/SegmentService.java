package com.lrj.commerce.campaign.segment.application;

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
			Clock clock, PlatformTransactionManager manager, Outbox outbox, WorkLanes lanes) {
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
		actor.requireAdmin();
		Inputs.require(input != null && input.version() > 0 && input.rule() != null, "人群定义无效");
		Identifiers.require(input.segmentId());
		Inputs.text(input.name(), 128);
		input.rule().requireTrustedFields();
		memberOnly(input.rule());
		Inputs.require(input.ttlSeconds() >= 300 && input.ttlSeconds() <= 86400
				&& (input.refreshSeconds() == 0
						|| (input.refreshSeconds() >= 60 && input.refreshSeconds() <= input.ttlSeconds()))
				&& input.maxMembers() >= 100 && input.maxMembers() <= 100000, "刷新周期或容量预算无效");
		return commands.run(actor, "segment.create", key, input, View.class, () -> {
			var current = mapper.lockRoot(actor.tenantId(), input.segmentId());
			String audience = "dyn-" + JsonCodec.hash(input.segmentId()).substring(0, 40);
			if (current == null)
				mapper.insertRoot(actor.tenantId(), input.segmentId(), audience, input.version(), now());
			else {
				Inputs.require(input.version() > current.currentVersion(), "新定义版本必须递增");
				mapper.advanceDefinition(actor.tenantId(), input.segmentId(), input.version(), now());
			}
			mapper.definition(actor.tenantId(), input, JsonCodec.write(input));
			return view(mapper.find(actor.tenantId(), input.segmentId()));
		});
	}

	/** 有界最新定义目录。 */
	public List<View> definitions(Actor actor, String after, int limit) {
		actor.requireAdmin();
		Inputs.page(after, limit);
		return mapper.definitions(actor.tenantId(), after, limit).stream().map(this::view).toList();
	}

	/** 停止定时触发不会取消已经开始的任务，取消有独立命令。 */
	public View schedule(Actor actor, String key, String id, Schedule input) {
		actor.requireAdmin();
		Identifiers.require(id);
		Inputs.require(input != null && input.expectedVersion() >= 0, "调度版本无效");
		return commands.run(actor, "segment.schedule", key, new Object[] { id, input }, View.class, () -> {
			var root = Inputs.found(mapper.lockRoot(actor.tenantId(), id));
			var d = definition(actor.tenantId(), id, root.currentVersion());
			Inputs.require(!input.enabled() || d.refreshSeconds() > 0, "手工定义不允许启用周期调度");
			if (mapper.schedule(actor.tenantId(), id, input, now()) != 1)
				throw new DomainException(DomainException.Code.CONFLICT, "调度配置版本已变化");
			return view(mapper.find(actor.tenantId(), id));
		});
	}

	/** 活动任务存在时返回同一任务，不额外堆积全库刷新。 */
	public Run refresh(Actor actor, String key, String id) {
		actor.requireAdmin();
		Identifiers.require(id);
		return commands.run(actor, "segment.refresh", key, id, Run.class,
				() -> start(actor.tenantId(), Inputs.found(mapper.lockRoot(actor.tenantId(), id))));
	}

	/** 任务元信息不包含完整会员列表。 */
	public List<Run> runs(Actor actor, String id, String after, int limit) {
		actor.requireAdmin();
		Identifiers.require(id);
		Inputs.page(after, limit);
		Inputs.found(mapper.find(actor.tenantId(), id));
		return mapper.runs(actor.tenantId(), id, after, limit);
	}

	/** 运维恢复审计：重试、取消等对停止工作的人工干预与状态变更同一事务记录。 */
	private com.lrj.commerce.runtime.recovery.RecoveryAudit audit;

	@org.springframework.beans.factory.annotation.Autowired
	void audit(com.lrj.commerce.runtime.recovery.RecoveryAudit audit) {
		this.audit = audit;
	}

	/** 取消保留已扫描但不可见的投影；重试从最后提交检查点继续。 */
	public Run control(Actor actor, String key, String id, String action) {
		actor.requireAdmin();
		Identifiers.require(id);
		Inputs.require(Set.of("cancel", "retry", "retry-announcement").contains(action), "任务操作无效");
		return commands.run(actor, "segment." + action, key, id, Run.class, () -> {
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
		actor.requireAdmin();
		return pumpTenant(actor.tenantId(), segments.manual());
	}

	/** 租户公平轮转，后台开关由既有EventWorker统一控制。 */
	public int tick() {
		return segments.run((after, limit) -> mapper.tenants(after, now(), limit), this::pumpTenant);
	}

	/** 瞬时失败（依赖不可用、锁冲突、超时）只延后不计次数，任务有效期终止重试；其他失败计次，5次隔离。 */
	private int pumpTenant(String tenant, TenantRotation.Run run) {
		// 新建任务失败只影响该人群：依次尝试至多3个到期人群，首个成功即停止，一个坏人群不再挡住同租户其他人群。
		for (var id : mapper.due(tenant, now())) {
			run.attempted();
			try {
				boolean[] started = { false };
				tx.executeWithoutResult(s -> {
					var root = mapper.lockRoot(tenant, id);
					if (root != null && root.enabled() && !root.nextDue().isAfter(now())) {
						start(tenant, root);
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
				Boolean done = tx.execute(s -> {
					var run2 = mapper.lockRun(tenant, id);
					if (run2 == null || !run2.status().equals("RUNNING") || run2.availableAt().isAfter(now()))
						return false;
					batch(tenant, run2);
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
				tx.executeWithoutResult(s -> {
					var row = mapper.lockRun(tenant, id);
					if (row == null || row.entriesAnnounced() || row.entryAttempts() >= 5)
						return;
					if (!clock.instant().isBefore(row.validUntil())) {
						mapper.announced(tenant, id, null, true);
						return;
					}
					var entered = mapper.entered(tenant, row);
					for (var member : entered)
						outbox.append(tenant, "segment.member.entered.v1", JsonCodec.hash(row.runId() + "/" + member),
								1, new Entered(member, row.segmentId(), row.audienceId(), row.snapshotVersion(),
										row.definitionVersion()));
					mapper.announced(tenant, id, entered.isEmpty() ? null : entered.getLast(), entered.size() < 100);
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

	private Run start(String tenant, SegmentMapper.Root root) {
		var active = mapper.active(tenant, root.segmentId());
		if (active != null)
			return active;
		var definition = definition(tenant, root.segmentId(), root.currentVersion());
		Instant started = now();
		var run = new Run(UUID.randomUUID().toString(), root.segmentId(), root.currentVersion(), root.audienceId(),
				root.snapshotSequence() + 1, "", 0, 0, "RUNNING", 0, null, started,
				started.plusSeconds(definition.ttlSeconds()), started, false, 0);
		mapper.allocate(tenant, root.segmentId(), started.plusSeconds(Math.max(60, definition.refreshSeconds())));
		mapper.run(tenant, run);
		return run;
	}

	private void batch(String tenant, Run run) {
		if (!clock.instant().isBefore(run.validUntil())) {
			mapper.status(tenant, run.runId(), "FAILED", "EXPIRED");
			return;
		}
		var definition = definition(tenant, run.segmentId(), run.definitionVersion());
		var batch = members.scan(tenant, run.cursorMember(), 100, run.startedAt());
		if (run.processed() + batch.size() > definition.maxMembers()) {
			mapper.status(tenant, run.runId(), "FAILED", "MEMBER_LIMIT");
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
		if (batch.size() < 100) {
			mapper.publish(tenant, run, definition.name(), total);
			mapper.status(tenant, run.runId(), "COMPLETED", null);
		}
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
