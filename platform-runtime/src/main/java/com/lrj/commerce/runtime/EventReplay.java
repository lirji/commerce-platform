package com.lrj.commerce.runtime;

import com.lrj.commerce.kernel.*;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.runtime.persistence.ReplayMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * 有界历史重放：对本租户、单一消费者、限定事件类型与时间区间内的DELIVERED事件刻意重新执行该消费者。
 * 重放区别于重试（同一执行的自动继续）与恢复（把停止的工作放回）：事件状态不变，只在安全门证明安全时执行消费者。
 * 执行边界：每项一个短事务，锁任务行（多实例串行推进同一任务）与事件主键（共享锁，与保留期清理互斥），消费者效果与游标推进一起提交；
 * 独立车道、小预算，实时事件积压达到阈值时本轮让路，因此重放不会挤占实时工作。任务可暂停、恢复、取消，失败项计数并跳过，失败过多则任务失败。
 */
@Component
public class EventReplay {

	/** 单个任务最多检查的事件数上限，与时间区间上限共同限定范围。 */
	public static final int MAX_EVENTS = 10_000;

	public static final Duration MAX_RANGE = Duration.ofDays(31);

	/** 每个租户同时存在的运行中或暂停任务上限。 */
	public static final int MAX_ACTIVE = 3;

	/** 单任务消费者非瞬时失败达到此数即任务失败，避免在系统性错误上继续扫描。 */
	public static final int MAX_FAILURES = 10;

	/** 默认让路阈值：有消费者的实时到期事件达到此数时重放本轮让路（commerce.replay.live-yield）。 */
	public static final int LIVE_YIELD = 200;

	/** 重放车道：每次访问一个租户最多20个事件，每轮最多100个或200毫秒。 */
	public static final TenantRotation.Policy POLICY = new TenantRotation.Policy(20, 50, Duration.ofMillis(200), 100);

	public record Scope(String consumer, List<String> eventTypes, Instant from, Instant to, ReplayGate.Mode mode,
			Integer maxEvents) {
	}

	public record Create(String jobId, String consumer, List<String> eventTypes, Instant from, Instant to,
			ReplayGate.Mode mode, Integer maxEvents, String reason) {
	}

	public record Control(String action, long expectedVersion, String reason) {
	}

	public record DryRun(String consumer, String mode, ReplayGate.Decision gate, List<ReplayMapper.TypeCount> byType,
			long events, long alreadyProcessed, long wouldExecute, boolean capped, int maxEvents) {
	}

	public record Stats(long executed, long alreadyProcessed, long failed, long blocked, long yielded) {
	}

	private static final Logger log = LoggerFactory.getLogger(EventReplay.class);

	private final ReplayMapper mapper;

	private final Map<String, EventHandler> handlers = new HashMap<>();

	private final Commands commands;

	private final RecoveryAudit audit;

	private final TransactionTemplate tx;

	private final Clock clock;

	private final TenantRotation rotation;

	private final int liveYield;

	private final Set<String> liveTypes = new TreeSet<>();

	private final LongAdder executed = new LongAdder(), processed = new LongAdder(), failures = new LongAdder(),
			blocked = new LongAdder(), yielded = new LongAdder();

	public EventReplay(ReplayMapper mapper, List<EventHandler> handlers, Commands commands, RecoveryAudit audit,
			PlatformTransactionManager manager, Clock clock, WorkLanes lanes,
			@org.springframework.beans.factory.annotation.Value("${commerce.replay.live-yield:200}") int liveYield) {
		this.mapper = mapper;
		this.commands = commands;
		this.audit = audit;
		this.clock = clock;
		tx = new TransactionTemplate(manager);
		tx.setTimeout(10);
		if (liveYield < 1)
			throw new IllegalArgumentException("重放让路阈值必须为正");
		this.liveYield = liveYield;
		for (var h : handlers) {
			this.handlers.put(h.consumer(), h);
			liveTypes.addAll(h.types());
		}
		rotation = lanes.rotation("replay", POLICY, () -> {
			var b = mapper.backlog();
			var now = clock.instant();
			return new WorkLanes.Backlog(b.running(), b.oldestRunning() == null ? null
					: Math.max(0, Duration.between(b.oldestRunning(), now).getSeconds()), b.failed());
		});
	}

	/** 试运行只读：给出安全门结论、各类型事件数（至多上限+1条）与该消费者已处理数，不改变任何状态。 */
	public DryRun dryRun(Actor actor, Scope scope) {
		actor.require(Actor.Capability.RUNTIME_RECOVERY_READ);
		var s = validate(scope.consumer(), scope.eventTypes(), scope.from(), scope.to(), scope.mode(),
				scope.maxEvents());
		var gate = ReplayGate.check(handlers.get(s.consumer()), s.mode());
		var counts = mapper.dryRun(actor.tenantId(), s.consumer(), s.eventTypes(), s.from(), s.to(), s.maxEvents() + 1);
		long events = counts.stream().mapToLong(ReplayMapper.TypeCount::events).sum(),
				done = counts.stream().mapToLong(ReplayMapper.TypeCount::processed).sum();
		boolean capped = events > s.maxEvents();
		long scoped = Math.min(events, s.maxEvents());
		long would = !gate.allowed() ? 0 : s.mode() == ReplayGate.Mode.REPROCESS ? scoped : Math.max(0, scoped - done);
		return new DryRun(s.consumer(), s.mode().name(), gate, counts, events, done, would, capped, s.maxEvents());
	}

	public ReplayMapper.Job create(Actor actor, String key, Create input) {
		actor.require(Actor.Capability.RUNTIME_REPLAY_EXECUTE);
		Inputs.require(input != null, "重放参数缺失");
		Identifiers.require(input.jobId());
		Inputs.text(input.reason(), 256);
		var s = validate(input.consumer(), input.eventTypes(), input.from(), input.to(), input.mode(),
				input.maxEvents());
		var gate = ReplayGate.check(handlers.get(s.consumer()), s.mode());
		if (!gate.allowed()) {
			blocked.increment();
			log.warn("REPLAY_BLOCKED consumer={} mode={} code={}", s.consumer(), s.mode(), gate.code());
			throw new DomainException(DomainException.Code.CONFLICT, gate.code() + "：" + gate.detail());
		}
		var normalized = new Create(input.jobId(), s.consumer(), s.eventTypes(), s.from(), s.to(), s.mode(),
				s.maxEvents(), input.reason());
		return commands.run(actor, "runtime.replay.create", key, normalized, ReplayMapper.Job.class, () -> {
			if (mapper.active(actor.tenantId()) >= MAX_ACTIVE)
				throw new DomainException(DomainException.Code.LIMIT_EXCEEDED, "同时进行的重放任务已达上限");
			if (mapper.find(actor.tenantId(), input.jobId()) != null)
				throw new DomainException(DomainException.Code.CONFLICT, "重放任务标识已存在");
			mapper.insert(actor.tenantId(),
					new ReplayMapper.Job(input.jobId(), s.consumer(), String.join(",", s.eventTypes()), s.mode().name(),
							s.from(), s.to(), s.maxEvents(), "RUNNING", null, null, 0, 0, 0, 0, null, input.reason(),
							actor.actorId(), 0, null, null));
			audit.record(actor, "runtime.replay.create", key, "event.replay", input.jobId(), "REPLAY_CREATE", null,
					"RUNNING", null, input.reason(), RecoveryAudit.APPLIED, null);
			return mapper.find(actor.tenantId(), input.jobId());
		});
	}

	/** 暂停、恢复（重新校验安全门）与取消；版本不符拒绝，已终结任务不可控制。 */
	public ReplayMapper.Job control(Actor actor, String key, String id, Control input) {
		actor.require(Actor.Capability.RUNTIME_REPLAY_EXECUTE);
		Identifiers.require(id);
		Inputs.require(
				input != null && input.expectedVersion() >= 0
						&& Set.of("PAUSE", "RESUME", "CANCEL").contains(Objects.toString(input.action(), "")),
				"重放控制参数无效");
		Inputs.text(input.reason(), 256);
		return commands.run(actor, "runtime.replay.control", key, new Object[] { id, input }, ReplayMapper.Job.class,
				() -> {
					var job = Inputs.found(mapper.lock(actor.tenantId(), id));
					if (job.version() != input.expectedVersion())
						throw new DomainException(DomainException.Code.CONFLICT, "重放任务版本已变化");
					String target = switch (input.action()) {
						case "PAUSE" -> {
							if (!job.status().equals("RUNNING"))
								throw new DomainException(DomainException.Code.CONFLICT, "只有运行中的任务可以暂停");
							yield "PAUSED";
						}
						case "RESUME" -> {
							if (!job.status().equals("PAUSED"))
								throw new DomainException(DomainException.Code.CONFLICT, "只有暂停的任务可以恢复");
							var gate = ReplayGate.check(handlers.get(job.consumerId()),
									ReplayGate.Mode.valueOf(job.mode()));
							if (!gate.allowed())
								throw new DomainException(DomainException.Code.CONFLICT,
										gate.code() + "：" + gate.detail());
							yield "RUNNING";
						}
						default -> {
							if (!Set.of("RUNNING", "PAUSED").contains(job.status()))
								throw new DomainException(DomainException.Code.CONFLICT, "任务已终结");
							yield "CANCELLED";
						}
					};
					if (mapper.status(actor.tenantId(), id, job.version(), target) != 1)
						throw new DomainException(DomainException.Code.CONFLICT, "重放任务并发修改");
					audit.record(actor, "runtime.replay.control", key, "event.replay", id, "REPLAY_" + input.action(),
							job.status(), target, null, input.reason(), RecoveryAudit.APPLIED, null);
					return mapper.find(actor.tenantId(), id);
				});
	}

	public List<ReplayMapper.Job> list(Actor actor, String after, int limit) {
		actor.require(Actor.Capability.RUNTIME_RECOVERY_READ);
		Inputs.page(after, limit);
		return mapper.list(actor.tenantId(), after, limit);
	}

	public ReplayMapper.Job find(Actor actor, String id) {
		actor.require(Actor.Capability.RUNTIME_RECOVERY_READ);
		Identifiers.require(id);
		return Inputs.found(mapper.find(actor.tenantId(), id));
	}

	/** 后台车道：实时事件积压高时整轮让路；否则按租户轮转推进每个租户最早的运行中任务。 */
	public int tick() {
		if (liveTypes.isEmpty())
			return 0;
		if (mapper.liveDue(liveTypes, liveYield) >= liveYield) {
			yielded.increment();
			return 0;
		}
		return rotation.run(mapper::tenants, this::advance);
	}

	private int advance(String tenant, TenantRotation.Run run) {
		String id = mapper.oldestRunning(tenant);
		if (id == null)
			return 0;
		int count = 0;
		for (int i = 0, limit = run.limit(); i < limit && !run.exhausted(); i++) {
			var attempted = new String[1];
			run.attempted();
			try {
				Boolean more = tx.execute(s -> step(tenant, id, attempted));
				count++;
				run.succeeded();
				if (!Boolean.TRUE.equals(more))
					break;
			}
			catch (RuntimeException failure) {
				var type = FailureClass.of(failure);
				// 瞬时失败不跳过事件也不计入任务失败，结束本次访问；连续发生时由车道熔断。
				if (type.transientFailure()) {
					log.warn("replay transient failure job={} failureClass={} errorType={}", id, type,
							failure.getClass().getSimpleName());
					run.failed(type);
					break;
				}
				run.failed(type);
				failed(tenant, id, attempted[0], type, failure);
			}
		}
		return count;
	}

	/** 一项：锁任务行，复核状态与安全门，取游标后的下一条事件并按模式执行，最后推进游标；返回任务是否仍在运行。 */
	private boolean step(String tenant, String id, String[] attempted) {
		var job = mapper.lock(tenant, id);
		if (job == null || !job.status().equals("RUNNING"))
			return false;
		var handler = handlers.get(job.consumerId());
		var mode = ReplayGate.Mode.valueOf(job.mode());
		var gate = ReplayGate.check(handler, mode);
		if (!gate.allowed()) {
			blocked.increment();
			log.warn("REPLAY_BLOCKED job={} consumer={} code={}", id, job.consumerId(), gate.code());
			finish(tenant, job, "FAILED", "GATE:" + gate.code());
			return false;
		}
		if (job.examined() >= job.maxEvents()) {
			finish(tenant, job, "COMPLETED", job.lastError());
			return false;
		}
		var next = mapper.next(tenant, job, List.of(job.eventTypes().split(",")));
		if (next == null) {
			finish(tenant, job, "COMPLETED", job.lastError());
			return false;
		}
		attempted[0] = next.eventId();
		var event = mapper.lockDelivered(tenant, next.eventId());
		int executed = job.executed(), already = job.alreadyProcessed();
		// 事件已被保留期清理删除时只推进游标；Inbox是去重边界：UNPROCESSED只在插入成功时执行，REPROCESS先保证Inbox再重新执行纯投影。
		if (event != null) {
			boolean fresh = mapper.inbox(handler.consumer(), event) == 1;
			if (fresh || mode == ReplayGate.Mode.REPROCESS) {
				handler.handle(event);
				executed++;
				this.executed.increment();
			}
			else {
				already++;
				processed.increment();
			}
		}
		var advanced = new ReplayMapper.Job(job.jobId(), job.consumerId(), job.eventTypes(), job.mode(), job.fromAt(),
				job.toAt(), job.maxEvents(), job.status(), next.createdAt(), next.eventId(), job.examined() + 1,
				executed, already, job.failed(), job.lastError(), job.reason(), job.createdBy(), job.version(),
				job.createdAt(), job.updatedAt());
		if (mapper.progress(tenant, advanced) != 1)
			throw new DomainException(DomainException.Code.CONFLICT, "重放任务并发推进");
		return true;
	}

	/**
	 * 消费者非瞬时失败：回滚该项后在独立事务跳过该事件并计数，失败过多则任务失败；证据只含事件标识、分类与异常类型。
	 * 失败事件已不是游标后的下一条（例如另一实例已推进）时只计数不跳过，下一次重新尝试，累计失败同样受上限约束。
	 */
	private void failed(String tenant, String id, String eventId, FailureClass type, RuntimeException failure) {
		failures.increment();
		String error = ((eventId == null ? "-" : eventId) + ":" + type + ":" + (failure instanceof DomainException d
				? "DomainException/" + d.code() : failure.getClass().getSimpleName()));
		String evidence = error.length() > 160 ? error.substring(0, 160) : error;
		log.warn("replay item failed job={} event={} failureClass={} errorType={}", id, eventId, type,
				failure.getClass().getSimpleName());
		try {
			tx.executeWithoutResult(s -> {
				var job = mapper.lock(tenant, id);
				if (job == null || !job.status().equals("RUNNING"))
					return;
				var cursor = eventId == null ? null : mapper.next(tenant, job, List.of(job.eventTypes().split(",")));
				boolean skip = cursor != null && cursor.eventId().equals(eventId);
				int failed = job.failed() + 1;
				mapper.progress(tenant,
						new ReplayMapper.Job(job.jobId(), job.consumerId(), job.eventTypes(), job.mode(), job.fromAt(),
								job.toAt(), job.maxEvents(), failed >= MAX_FAILURES ? "FAILED" : "RUNNING",
								skip ? cursor.createdAt() : job.cursorCreatedAt(),
								skip ? cursor.eventId() : job.cursorEventId(), job.examined() + (skip ? 1 : 0),
								job.executed(), job.alreadyProcessed(), failed, evidence, job.reason(), job.createdBy(),
								job.version(), job.createdAt(), job.updatedAt()));
			});
		}
		catch (RuntimeException unrecorded) {
			log.warn("replay failure not recorded job={} errorType={}", id, unrecorded.getClass().getSimpleName());
		}
	}

	private void finish(String tenant, ReplayMapper.Job job, String status, String error) {
		mapper.progress(tenant,
				new ReplayMapper.Job(job.jobId(), job.consumerId(), job.eventTypes(), job.mode(), job.fromAt(),
						job.toAt(), job.maxEvents(), status, job.cursorCreatedAt(), job.cursorEventId(), job.examined(),
						job.executed(), job.alreadyProcessed(), job.failed(), error, job.reason(), job.createdBy(),
						job.version(), job.createdAt(), job.updatedAt()));
	}

	private record Valid(String consumer, List<String> eventTypes, Instant from, Instant to, ReplayGate.Mode mode,
			int maxEvents) {
	}

	/** 范围校验：消费者存在且处理全部所列类型；区间不超过31天、不晚于现在；上限不超过MAX_EVENTS。 */
	private Valid validate(String consumer, List<String> types, Instant from, Instant to, ReplayGate.Mode mode,
			Integer maxEvents) {
		Identifiers.require(consumer);
		var handler = handlers.get(consumer);
		if (handler == null)
			throw new DomainException(DomainException.Code.NOT_FOUND, "消费者不存在");
		Inputs.require(mode != null, "重放模式缺失");
		Inputs.require(types != null && !types.isEmpty() && types.size() <= 10, "事件类型需为1至10个");
		var sorted = new TreeSet<String>();
		for (var t : types) {
			Inputs.require(t != null && handler.types().contains(t), "事件类型不由该消费者处理");
			sorted.add(t);
		}
		Inputs
			.require(
					from != null && to != null && to.isAfter(from)
							&& Duration.between(from, to).compareTo(MAX_RANGE) <= 0 && !to.isAfter(clock.instant()),
					"重放区间需在31天内且不晚于当前时间");
		int max = maxEvents == null ? MAX_EVENTS : maxEvents;
		Inputs.require(max >= 1 && max <= MAX_EVENTS, "事件上限需为1至" + MAX_EVENTS);
		return new Valid(consumer, List.copyOf(sorted), from.truncatedTo(java.time.temporal.ChronoUnit.MILLIS),
				to.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), mode, max);
	}

	public Stats stats() {
		return new Stats(executed.sum(), processed.sum(), failures.sum(), blocked.sum(), yielded.sum());
	}

	/** 安全矩阵：每个已登记消费者的分类与两种模式的安全门结论，供运维与证据使用。 */
	public record Classification(String consumer, Set<String> types, Set<EventHandler.SideEffect> effects,
			String evidence, ReplayGate.Decision unprocessed, ReplayGate.Decision reprocess) {
	}

	public List<Classification> classifications(Actor actor) {
		actor.require(Actor.Capability.RUNTIME_RECOVERY_READ);
		return handlers.values().stream().sorted(Comparator.comparing(EventHandler::consumer)).map(h -> {
			var s = h.replaySafety();
			return new Classification(h.consumer(), new TreeSet<>(h.types()), s == null ? Set.of() : s.effects(),
					s == null ? null : s.evidence(), ReplayGate.check(h, ReplayGate.Mode.UNPROCESSED),
					ReplayGate.check(h, ReplayGate.Mode.REPROCESS));
		}).toList();
	}

}
