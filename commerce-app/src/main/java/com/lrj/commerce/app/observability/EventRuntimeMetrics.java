package com.lrj.commerce.app.observability;

import com.lrj.commerce.runtime.EventDispatcher;
import com.lrj.commerce.runtime.persistence.EventMapper;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;
import java.util.function.ToDoubleFunction;

/** 事件运行时低基数指标：不带租户标签；数据库积压值缓存5秒，避免每次读取指标都扫描事件表。 */
@Component
public class EventRuntimeMetrics implements MeterBinder {

	private final EventDispatcher events;

	private final com.lrj.commerce.runtime.Outbox outbox;

	private volatile EventMapper.Health cached;

	private volatile long cachedAt;

	public EventRuntimeMetrics(EventDispatcher events, com.lrj.commerce.runtime.Outbox outbox) {
		this.events = events;
		this.outbox = outbox;
	}

	public void bindTo(MeterRegistry registry) {
		gauge(registry, "commerce.events.queue.depth", "到期未投递事件数", h -> h.due());
		gauge(registry, "commerce.events.pending", "全部待投递事件数，含退避中", h -> h.pending());
		gauge(registry, "commerce.events.retrying", "已失败待重试事件数", h -> h.retrying());
		gauge(registry, "commerce.events.quarantined", "已隔离等待人工重放事件数", h -> h.isolated());
		gauge(registry, "commerce.events.unrouted", "没有消费者也未声明的事件数（缺少必需消费者）", h -> h.unrouted());
		gauge(registry, "commerce.events.retrying.transient", "因依赖不可用等瞬时失败待重试的事件数", h -> h.transientRetrying());
		gauge(registry, "commerce.events.oldest.due.age", "最老到期事件等待秒数",
				h -> h.oldestDueAgeSeconds() == null ? 0 : h.oldestDueAgeSeconds());
		counter(registry, "commerce.events.attempted", "事件处理尝试次数", s -> s.attempted());
		counter(registry, "commerce.events.delivered", "事件全部消费者成功次数", s -> s.delivered());
		counter(registry, "commerce.events.consumer.failures", "消费者失败次数", s -> s.consumerFailures());
		counter(registry, "commerce.events.quarantined.total", "本进程隔离的事件数", s -> s.quarantined());
		counter(registry, "commerce.events.transient.failures", "瞬时失败次数，不计入隔离预算", s -> s.transientFailures());
		counter(registry, "commerce.events.breaker.trips", "事件车道依赖熔断次数", s -> s.breakerTrips());
		FunctionCounter.builder("commerce.events.skipped", outbox, o -> o.skipped())
			.description("写入即跳过的已声明无消费者事件数")
			.register(registry);
		Gauge.builder("commerce.events.breaker.open", events, e -> e.stats().breakerOpen() ? 1 : 0)
			.description("事件车道依赖熔断是否打开")
			.register(registry);
		counter(registry, "commerce.events.latency.total", "已投递事件累计端到端延迟毫秒，除以delivered得平均",
				s -> s.latencyMillisTotal());
		Gauge.builder("commerce.events.rotation.last", events, e -> e.stats().lastRotationMillis() / 1000.0)
			.description("最近一整圈租户轮转秒数，即租户最坏等待的观测值")
			.baseUnit("seconds")
			.register(registry);
		Gauge.builder("commerce.events.rotation.tenants", events, e -> e.stats().lastRotationTenants())
			.description("最近一整圈轮转访问的租户数")
			.register(registry);
	}

	private void gauge(MeterRegistry registry, String name, String description,
			ToDoubleFunction<EventMapper.Health> value) {
		Gauge.builder(name, this, m -> value.applyAsDouble(m.health())).description(description).register(registry);
	}

	private void counter(MeterRegistry registry, String name, String description,
			ToDoubleFunction<EventDispatcher.Stats> value) {
		FunctionCounter.builder(name, events, e -> value.applyAsDouble(e.stats()))
			.description(description)
			.register(registry);
	}

	private EventMapper.Health health() {
		if (cached == null || System.nanoTime() - cachedAt > 5_000_000_000L) {
			cached = events.health((String) null);
			cachedAt = System.nanoTime();
		}
		return cached;
	}

}
