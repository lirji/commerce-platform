package com.lrj.commerce.runtime;

import com.lrj.commerce.runtime.persistence.RetentionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * 保留期清理车道。保留时长是产品/法务决定，仓库内没有依据，因此默认关闭；开启时每一类保留时长都必须显式配置且不低于安全下限，不提供零保留默认值。
 * 只清理可证明安全的数据：DELIVERED与SKIPPED事件连同它们的Inbox行在同一事务删除（先Inbox后事件），事件行消失后不可能再被投递或重放，
 * 因此不存在“Inbox已删而事件仍可执行”的窗口；PENDING与ISOLATED事件、审计、恢复审计、重放任务永不在此删除；运行中或暂停的重放区间内的事件不删除。
 * 命令幂等记录只在单独配置时按更长下限清理。每批一个READ COMMITTED短事务，SKIP LOCKED跳过正在投递或重放的行；每轮受行数与时间预算约束，
 * 实时事件积压高时整轮让路，失败只结束本轮并计数，下一轮从最老的数据继续（删除天然幂等）。
 */
@Component
public class RetentionLane {

	/** 事件保留下限：覆盖常见的月末对账与排障窗口，防止误配置为分钟级。 */
	public static final Duration EVENT_FLOOR = Duration.ofDays(7);

	/** 命令幂等记录下限：远长于任何客户端重试窗口，清理后相同幂等键会重新执行。 */
	public static final Duration COMMAND_FLOOR = Duration.ofDays(30);

	public static final int BATCH = 500, MAX_ROWS = 2000;

	public static final Duration BUDGET = Duration.ofMillis(300);

	/** 保留配置；为null的类别不清理。 */
	public record Policy(boolean enabled, Duration deliveredEvents, Duration skippedEvents, Duration commands) {
		public Policy {
			if (enabled) {
				if (deliveredEvents == null && skippedEvents == null && commands == null)
					throw new IllegalArgumentException("开启保留期清理时至少配置一类保留时长");
				for (var d : new Duration[] { deliveredEvents, skippedEvents })
					if (d != null && d.compareTo(EVENT_FLOOR) < 0)
						throw new IllegalArgumentException("事件保留时长不得低于" + EVENT_FLOOR.toDays() + "天");
				if (commands != null && commands.compareTo(COMMAND_FLOOR) < 0)
					throw new IllegalArgumentException("命令保留时长不得低于" + COMMAND_FLOOR.toDays() + "天");
			}
		}
	}

	public record ClassLag(String dataClass, Duration retention, Long oldestAgeSeconds, Long lagSeconds) {
	}

	public record Stats(boolean enabled, long runs, long yielded, long failures, int consecutiveFailures,
			long deliveredPurged, long skippedPurged, long inboxPurged, long commandsPurged, Instant lastRunAt,
			long lastRunMillis, String lastFailureClass) {
	}

	private static final Logger log = LoggerFactory.getLogger(RetentionLane.class);

	private final RetentionMapper mapper;

	private final TransactionTemplate tx;

	private final Clock clock;

	private final Policy policy;

	private final int liveYield;

	private final Set<String> liveTypes = new TreeSet<>();

	private final LongAdder runs = new LongAdder(), yielded = new LongAdder(), failures = new LongAdder(),
			delivered = new LongAdder(), skipped = new LongAdder(), inbox = new LongAdder(), commands = new LongAdder();

	private volatile int consecutiveFailures;

	private volatile Instant lastRunAt;

	private volatile long lastRunMillis = -1;

	private volatile String lastFailureClass;

	@org.springframework.beans.factory.annotation.Autowired
	public RetentionLane(RetentionMapper mapper, PlatformTransactionManager manager, Clock clock,
			List<com.lrj.commerce.runtime.api.EventHandler> handlers,
			@Value("${commerce.retention.enabled:false}") boolean enabled,
			@Value("${commerce.retention.delivered-events:}") String deliveredEvents,
			@Value("${commerce.retention.skipped-events:}") String skippedEvents,
			@Value("${commerce.retention.commands:}") String commandRetention,
			@Value("${commerce.retention.live-yield:200}") int liveYield) {
		this(mapper, manager, clock,
				new Policy(enabled, duration(deliveredEvents), duration(skippedEvents), duration(commandRetention)),
				liveYield, handlers.stream().flatMap(h -> h.types().stream()).toList());
	}

	/** liveYield为有消费者的实时到期事件让路阈值，liveTypes为有消费者的事件类型。 */
	public RetentionLane(RetentionMapper mapper, PlatformTransactionManager manager, Clock clock, Policy policy,
			int liveYield, Collection<String> liveTypes) {
		this.mapper = mapper;
		this.clock = clock;
		this.policy = policy;
		this.liveTypes.addAll(liveTypes);
		if (liveYield < 1)
			throw new IllegalArgumentException("保留期清理让路阈值必须为正");
		this.liveYield = liveYield;
		tx = new TransactionTemplate(manager);
		tx.setTimeout(10);
		tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		if (policy.enabled())
			log.info("retention enabled deliveredEvents={} skippedEvents={} commands={}", policy.deliveredEvents(),
					policy.skippedEvents(), policy.commands());
	}

	/** 支持ISO-8601（P30D）或“天数d”（30d）；空表示不清理该类。 */
	public static Duration duration(String value) {
		if (value == null || value.isBlank())
			return null;
		String v = value.trim();
		if (v.matches("\\d+d"))
			return Duration.ofDays(Long.parseLong(v.substring(0, v.length() - 1)));
		return Duration.parse(v);
	}

	/** 一轮：依次清理DELIVERED事件、SKIPPED事件与命令，受行数与时间预算约束；返回删除的主行数。 */
	public int tick() {
		if (!policy.enabled())
			return 0;
		long started = System.nanoTime();
		runs.increment();
		lastRunAt = clock.instant();
		int purged = 0;
		try {
			if (!liveTypes.isEmpty() && mapper.liveDue(liveTypes, liveYield) >= liveYield) {
				yielded.increment();
				return 0;
			}
			var now = clock.instant();
			var replay = mapper.earliestActiveReplay();
			purged += events("DELIVERED", policy.deliveredEvents(), now, replay, started, purged, delivered);
			purged += events("SKIPPED", policy.skippedEvents(), now, replay, started, purged, skipped);
			if (policy.commands() != null)
				while (purged < MAX_ROWS && !spent(started)) {
					int n = tx.execute(s -> mapper.deleteCommands(now.minus(policy.commands()), BATCH));
					commands.add(n);
					purged += n;
					if (n < BATCH)
						break;
				}
			consecutiveFailures = 0;
			return purged;
		}
		catch (RuntimeException failure) {
			var type = FailureClass.of(failure);
			failures.increment();
			consecutiveFailures++;
			lastFailureClass = type.name();
			log.warn("retention run failed failureClass={} errorType={} consecutiveFailures={}", type,
					failure.getClass().getSimpleName(), consecutiveFailures);
			return purged;
		}
		finally {
			lastRunMillis = (System.nanoTime() - started) / 1_000_000;
		}
	}

	/** 截止时间取保留截止与运行中重放区间起点中较早者；每批在一个事务内先删Inbox再删事件。 */
	private int events(String status, Duration retention, Instant now, Instant replayFrom, long started, int already,
			LongAdder counter) {
		if (retention == null)
			return 0;
		var cutoff = now.minus(retention);
		if (replayFrom != null && replayFrom.isBefore(cutoff))
			cutoff = replayFrom;
		var bound = cutoff;
		int purged = 0;
		while (already + purged < MAX_ROWS && !spent(started)) {
			int take = Math.min(BATCH, MAX_ROWS - already - purged);
			int n = Objects.requireNonNull(tx.execute(s -> {
				var ids = mapper.terminalEvents(status, bound, take);
				if (ids.isEmpty())
					return 0;
				inbox.add(mapper.deleteInbox(ids));
				int deleted = mapper.deleteEvents(ids);
				if (deleted != ids.size())
					throw new IllegalStateException("保留期清理删除数与锁定数不一致");
				return deleted;
			}));
			counter.add(n);
			purged += n;
			if (n < take)
				break;
		}
		return purged;
	}

	private static boolean spent(long started) {
		return System.nanoTime() - started >= BUDGET.toNanos();
	}

	/** 各类数据最老可清理行的年龄与超出保留期的滞后（秒）；关闭时只报告年龄。 */
	private volatile List<ClassLag> cachedLag;

	private volatile long cachedLagAt;

	/** 结果缓存5秒：指标与平台视图频繁读取时每类只走一次索引首行。 */
	public List<ClassLag> lag() {
		if (cachedLag != null && System.nanoTime() - cachedLagAt < 5_000_000_000L)
			return cachedLag;
		var now = clock.instant();
		var result = new ArrayList<ClassLag>();
		result.add(lag("DELIVERED_EVENTS", policy.deliveredEvents(), mapper.oldestTerminal("DELIVERED"), now));
		result.add(lag("SKIPPED_EVENTS", policy.skippedEvents(), mapper.oldestTerminal("SKIPPED"), now));
		result.add(lag("COMMANDS", policy.commands(), mapper.oldestCommand(), now));
		cachedLag = List.copyOf(result);
		cachedLagAt = System.nanoTime();
		return cachedLag;
	}

	private ClassLag lag(String name, Duration retention, Instant oldest, Instant now) {
		Long age = oldest == null ? null : Math.max(0, Duration.between(oldest, now).getSeconds());
		Long lag = !policy.enabled() || retention == null || age == null ? null
				: Math.max(0, age - retention.getSeconds());
		return new ClassLag(name, policy.enabled() ? retention : null, age, lag);
	}

	public Policy policy() {
		return policy;
	}

	public Stats stats() {
		return new Stats(policy.enabled(), runs.sum(), yielded.sum(), failures.sum(), consecutiveFailures,
				delivered.sum(), skipped.sum(), inbox.sum(), commands.sum(), lastRunAt, lastRunMillis,
				lastFailureClass);
	}

}
