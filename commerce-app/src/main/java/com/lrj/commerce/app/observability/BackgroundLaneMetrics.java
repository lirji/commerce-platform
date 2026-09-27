package com.lrj.commerce.app.observability;

import com.lrj.commerce.app.runtime.LaneMonitor;
import com.lrj.commerce.runtime.*;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** 后台车道低基数指标：唯一标签是固定车道名，不含租户、订单或事件标识；积压值来自WorkLanes的5秒缓存。 */
@Component
public class BackgroundLaneMetrics implements MeterBinder {

	/** 租户轮转车道的固定集合，事件车道另有commerce.events.*指标；replay为历史重放车道（积压为运行中任务，隔离数为失败任务）。 */
	static final List<String> LANES = List.of("payments", "refunds", "orders", "segments", "journeys", "cycles",
			"points", "deliveries", "catalog-jobs", "replay");

	/** 保留期数据类别，固定集合。 */
	static final List<String> RETENTION_CLASSES = List.of("DELIVERED_EVENTS", "SKIPPED_EVENTS", "COMMANDS");

	private final WorkLanes lanes;

	private final LaneMonitor monitor;

	private final EventReplay replay;

	private final RetentionLane retention;

	public BackgroundLaneMetrics(WorkLanes lanes, LaneMonitor monitor, EventReplay replay, RetentionLane retention) {
		this.lanes = lanes;
		this.monitor = monitor;
		this.replay = replay;
		this.retention = retention;
	}

	public void bindTo(MeterRegistry registry) {
		for (String lane : LANES) {
			counter(registry, lane, "commerce.lanes.items", "领取的工作项数", s -> s.items());
			counter(registry, lane, "commerce.lanes.completed", "成功完成的工作项数", s -> s.completed());
			counter(registry, lane, "commerce.lanes.transient.failures", "依赖不可用、锁冲突、超时等瞬时失败数",
					s -> s.transientFailures());
			counter(registry, lane, "commerce.lanes.failures", "非瞬时失败数", s -> s.otherFailures());
			counter(registry, lane, "commerce.lanes.breaker.trips", "依赖熔断次数", s -> s.breakerTrips());
			gauge(registry, lane, "commerce.lanes.breaker.open", "依赖熔断是否打开", v -> v.rotation().breakerOpen() ? 1 : 0);
			gauge(registry, lane, "commerce.lanes.rotation.last", "最近一整圈租户轮转秒数",
					v -> v.rotation().lastRotationMillis() / 1000.0);
			gauge(registry, lane, "commerce.lanes.backlog.due", "到期未处理工作项数",
					v -> v.backlog() == null ? Double.NaN : v.backlog().due());
			gauge(registry, lane, "commerce.lanes.backlog.oldest.age", "最老到期工作等待秒数",
					v -> v.backlog() == null || v.backlog().oldestDueAgeSeconds() == null ? 0
							: v.backlog().oldestDueAgeSeconds());
			gauge(registry, lane, "commerce.lanes.backlog.quarantined", "已停止自动处理的工作项数",
					v -> v.backlog() == null ? Double.NaN : v.backlog().quarantined());
		}
		FunctionCounter.builder("commerce.replay.executed", replay, r -> r.stats().executed())
			.description("重放中执行消费者的事件数")
			.register(registry);
		FunctionCounter.builder("commerce.replay.already.processed", replay, r -> r.stats().alreadyProcessed())
			.description("重放中因消费者已处理而跳过的事件数")
			.register(registry);
		FunctionCounter.builder("commerce.replay.failures", replay, r -> r.stats().failed())
			.description("重放中消费者非瞬时失败数")
			.register(registry);
		FunctionCounter.builder("commerce.replay.blocked", replay, r -> r.stats().blocked())
			.description("安全门拒绝的重放（创建或执行）次数")
			.register(registry);
		FunctionCounter.builder("commerce.replay.yielded", replay, r -> r.stats().yielded())
			.description("实时积压高而让路的重放轮数")
			.register(registry);
		Gauge.builder("commerce.retention.enabled", retention, r -> r.policy().enabled() ? 1 : 0)
			.description("保留期清理是否开启")
			.register(registry);
		FunctionCounter.builder("commerce.retention.purged", retention, r -> r.stats().deliveredPurged())
			.tag("class", "DELIVERED_EVENTS")
			.description("清理的主行数")
			.register(registry);
		FunctionCounter.builder("commerce.retention.purged", retention, r -> r.stats().skippedPurged())
			.tag("class", "SKIPPED_EVENTS")
			.description("清理的主行数")
			.register(registry);
		FunctionCounter.builder("commerce.retention.purged", retention, r -> r.stats().commandsPurged())
			.tag("class", "COMMANDS")
			.description("清理的主行数")
			.register(registry);
		FunctionCounter.builder("commerce.retention.inbox.purged", retention, r -> r.stats().inboxPurged())
			.description("随事件一起清理的Inbox行数")
			.register(registry);
		FunctionCounter.builder("commerce.retention.failures", retention, r -> r.stats().failures())
			.description("清理失败轮数")
			.register(registry);
		Gauge.builder("commerce.retention.failures.consecutive", retention, r -> r.stats().consecutiveFailures())
			.description("连续失败轮数")
			.register(registry);
		for (String dataClass : RETENTION_CLASSES)
			Gauge.builder("commerce.retention.lag", retention, r -> lag(r, dataClass))
				.tag("class", dataClass)
				.baseUnit("seconds")
				.description("最老可清理数据超出保留期的秒数，未开启或未配置为NaN")
				.register(registry);
		for (String lane : concat()) {
			Gauge.builder("commerce.lanes.start.lag", monitor, m -> schedule(m, lane, true))
				.tag("lane", lane)
				.description("最近一次开始比预定晚的毫秒数")
				.baseUnit("milliseconds")
				.register(registry);
			Gauge.builder("commerce.lanes.run.duration", monitor, m -> schedule(m, lane, false))
				.tag("lane", lane)
				.description("最近一次运行耗时毫秒")
				.baseUnit("milliseconds")
				.register(registry);
		}
	}

	private static List<String> concat() {
		var all = new java.util.ArrayList<>(LANES);
		all.add("events");
		all.add("retention");
		return all;
	}

	private static double lag(RetentionLane retention, String dataClass) {
		try {
			for (var l : retention.lag())
				if (l.dataClass().equals(dataClass))
					return l.lagSeconds() == null ? Double.NaN : l.lagSeconds();
		}
		catch (RuntimeException unavailable) {
			return Double.NaN;
		}
		return Double.NaN;
	}

	private static double schedule(LaneMonitor monitor, String lane, boolean lag) {
		for (var s : monitor.schedules())
			if (s.lane().equals(lane))
				return lag ? s.lastStartLagMillis() : s.lastDurationMillis();
		return Double.NaN;
	}

	private void counter(MeterRegistry registry, String lane, String name, String description,
			ToDoubleFunction<TenantRotation.Stats> value) {
		FunctionCounter.builder(name, lanes, l -> {
			var v = l.snapshot().get(lane);
			return v == null ? 0 : value.applyAsDouble(v.rotation());
		}).tag("lane", lane).description(description).register(registry);
	}

	private void gauge(MeterRegistry registry, String lane, String name, String description,
			ToDoubleFunction<WorkLanes.Lane> value) {
		Gauge.builder(name, lanes, l -> {
			var v = l.snapshot().get(lane);
			return v == null ? Double.NaN : value.applyAsDouble(v);
		}).tag("lane", lane).description(description).register(registry);
	}

}
