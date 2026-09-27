package com.lrj.commerce.runtime.event;

import com.lrj.commerce.runtime.event.persistence.EventMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

/**
 * 告警契约的检测面：调度线程每分钟最多做一次全局健康查询，超过阈值输出带固定代码的WARN日志。
 * 外部告警投递尚未接入，运维以日志代码或管理端健康接口接入；日志不含租户标识与载荷。
 */
public final class EventHealthLog {

	static final long INTERVAL_NANOS = 60_000_000_000L;

	/** 最老到期事件超过5分钟未投递。 */
	static final long OLDEST_DUE_WARN_SECONDS = 300;

	/** 一整圈轮转超过5分钟，即单租户最坏等待超过5分钟。 */
	static final long ROTATION_WARN_MILLIS = 300_000;

	/** 连续5个观测周期到期积压都在增长。 */
	static final int GROWTH_WARN_INTERVALS = 5;

	/** 周期内至少20次尝试且消费者失败超过20%。 */
	static final int FAILURE_RATE_MIN_ATTEMPTS = 20, FAILURE_RATE_WARN_PERCENT = 20;

	private static final Logger log = LoggerFactory.getLogger(EventHealthLog.class);

	private final EventDispatcher dispatcher;

	private long nextAt = System.nanoTime() + INTERVAL_NANOS;

	private Snapshot previous;

	private int growth;

	EventHealthLog(EventDispatcher dispatcher) {
		this.dispatcher = dispatcher;
	}

	/** 一个观测周期的全局数据；计数为进程启动以来的累计值。 */
	public record Snapshot(EventMapper.Health health, EventDispatcher.Stats stats) {
	}

	void maybeLog() {
		if (System.nanoTime() < nextAt)
			return;
		nextAt = System.nanoTime() + INTERVAL_NANOS;
		try {
			var current = new Snapshot(dispatcher.health((String) null), dispatcher.stats());
			growth = previous != null && current.health().due() > previous.health().due() ? growth + 1 : 0;
			var alerts = evaluate(previous, current, growth);
			previous = current;
			var h = current.health();
			var s = current.stats();
			if (!alerts.isEmpty())
				log.warn(
						"event runtime alert codes={} due={} oldestDueAgeS={} retrying={} transientRetrying={} isolated={} unrouted={} lastRotationMs={} lastRotationTenants={}",
						alerts, h.due(), h.oldestDueAgeSeconds(), h.retrying(), h.transientRetrying(), h.isolated(),
						h.unrouted(), s.lastRotationMillis(), s.lastRotationTenants());
			else if (h.due() > 0 || h.isolated() > 0)
				log.info(
						"event runtime health due={} oldestDueAgeS={} retrying={} isolated={} unrouted={} delivered={} lastRotationMs={} lastRotationTenants={}",
						h.due(), h.oldestDueAgeSeconds(), h.retrying(), h.isolated(), h.unrouted(), s.delivered(),
						s.lastRotationMillis(), s.lastRotationTenants());
		}
		catch (RuntimeException failure) {
			// 健康检查失败不能影响投递。
			log.warn("event runtime health unavailable errorType={}", failure.getClass().getSimpleName());
		}
	}

	/** 纯函数规则，便于测试告警契约；previous为空时只评估当前值。 */
	public static List<String> evaluate(Snapshot previous, Snapshot current, int growthIntervals) {
		var alerts = new ArrayList<String>();
		var h = current.health();
		var s = current.stats();
		if (h.oldestDueAgeSeconds() != null && h.oldestDueAgeSeconds() > OLDEST_DUE_WARN_SECONDS)
			alerts.add("EVENT_BACKLOG_AGE");
		if (s.lastRotationMillis() > ROTATION_WARN_MILLIS)
			alerts.add("EVENT_ROTATION_SLOW");
		if (growthIntervals >= GROWTH_WARN_INTERVALS)
			alerts.add("EVENT_BACKLOG_GROWING");
		// 未声明又没有消费者的类型：必需消费者缺失或装配错误，持续告警直到部署消费者或发布方声明。
		if (h.unrouted() > 0)
			alerts.add("EVENT_NO_REQUIRED_CONSUMER");
		if (s.breakerOpen() || previous != null && s.breakerTrips() > previous.stats().breakerTrips())
			alerts.add("EVENT_DEPENDENCY_UNAVAILABLE");
		if (previous != null) {
			if (h.isolated() > previous.health().isolated())
				alerts.add("EVENT_QUARANTINE_GROWTH");
			long attempts = s.attempted() - previous.stats().attempted(),
					failures = s.consumerFailures() - previous.stats().consumerFailures();
			if (attempts >= FAILURE_RATE_MIN_ATTEMPTS && failures * 100 > attempts * FAILURE_RATE_WARN_PERCENT)
				alerts.add("EVENT_FAILURE_RATE");
			if (previous.health().due() > 0 && h.due() > 0 && s.attempted() == previous.stats().attempted())
				alerts.add("EVENT_NO_PROGRESS");
		}
		return alerts;
	}

}
