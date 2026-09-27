package com.lrj.commerce.app.runtime;

import com.lrj.commerce.runtime.FailureClass;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.*;

/**
 * 车道调度观测：每次运行的开始延迟（实际开始减去上次结束加固定延迟）与耗时，用来证明车道间没有饥饿； 只含车道名这一低基数维度，不含租户。未启用后台Worker时没有记录。
 */
@Component
public class LaneMonitor {

	/** 开始延迟与耗时单位毫秒；lastStartedAt为空表示该车道从未运行。 */
	public record LaneSchedule(String lane, long runs, long failures, long lastStartLagMillis, long maxStartLagMillis,
			long lastDurationMillis, long maxDurationMillis, Instant lastStartedAt, Instant lastFinishedAt,
			String lastFailureClass) {
	}

	private final class State {

		final String lane;

		final long delayMillis;

		final LongAdder runs = new LongAdder(), failures = new LongAdder();

		final AtomicLong maxLag = new AtomicLong(), maxDuration = new AtomicLong();

		volatile long lastLag, lastDuration, finishedNanos = -1;

		volatile Instant startedAt, finishedAt;

		volatile String lastFailure;

		State(String lane, long delayMillis) {
			this.lane = lane;
			this.delayMillis = delayMillis;
		}

	}

	private final Map<String, State> lanes = new ConcurrentSkipListMap<>();

	/** 登记车道及其固定延迟，重复登记视为配置错误。 */
	public void register(String lane, long delayMillis) {
		if (lanes.putIfAbsent(lane, new State(lane, delayMillis)) != null)
			throw new IllegalStateException("重复调度车道");
	}

	/** 执行一次车道：任一异常只记录车道名、失败分类和异常类型，不输出可能含业务载荷的异常文本，后续运行照常调度。 */
	public void run(String lane, Runnable tick) {
		var state = Objects.requireNonNull(lanes.get(lane), "未登记车道");
		long started = System.nanoTime();
		if (state.finishedNanos >= 0) {
			long lag = Math.max(0, (started - state.finishedNanos) / 1_000_000 - state.delayMillis);
			state.lastLag = lag;
			state.maxLag.accumulateAndGet(lag, Math::max);
		}
		state.startedAt = Instant.now();
		state.runs.increment();
		try {
			tick.run();
		}
		catch (RuntimeException failure) {
			state.failures.increment();
			state.lastFailure = FailureClass.of(failure).name();
			org.slf4j.LoggerFactory.getLogger(EventWorker.class)
				.warn("worker lane failed lane={} failureClass={} errorType={}", lane, state.lastFailure,
						failure.getClass().getSimpleName());
		}
		finally {
			long finished = System.nanoTime();
			long duration = (finished - started) / 1_000_000;
			state.lastDuration = duration;
			state.maxDuration.accumulateAndGet(duration, Math::max);
			state.finishedNanos = finished;
			state.finishedAt = Instant.now();
		}
	}

	public List<LaneSchedule> schedules() {
		var result = new ArrayList<LaneSchedule>();
		for (var s : lanes.values())
			result.add(new LaneSchedule(s.lane, s.runs.sum(), s.failures.sum(), s.lastLag, s.maxLag.get(),
					s.lastDuration, s.maxDuration.get(), s.startedAt, s.finishedAt, s.lastFailure));
		return result;
	}

}
