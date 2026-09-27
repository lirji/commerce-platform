package com.lrj.commerce.runtime.work;

import java.time.Duration;

/**
 * 有界指数退避：第n次失败后等待min(base·2^(n-1),max)，再加[0,jitter·等待)的抖动，打散同时恢复的重试。
 * budget是该类失败允许的次数，达到即终止自动处理；随机数由调用方传入，测试可固定。
 */
public record RetryPolicy(Duration base, Duration max, double jitter, int budget) {

	/** 毒工作预算（业务拒绝、数据损坏、配置错误、未知）：2/4/8/16秒，第5次失败隔离，约30秒内可见。 */
	public static final RetryPolicy POISON = new RetryPolicy(Duration.ofSeconds(2), Duration.ofSeconds(16), 0.25, 5);

	/** 瞬时预算（依赖不可用、锁冲突、超时）：2秒起翻倍到5分钟封顶，300次约27小时后才隔离。 */
	public static final RetryPolicy TRANSIENT = new RetryPolicy(Duration.ofSeconds(2), Duration.ofMinutes(5), 0.2, 300);

	public RetryPolicy {
		if (base.isNegative() || base.isZero() || max.compareTo(base) < 0 || jitter < 0 || jitter > 1 || budget < 1)
			throw new IllegalArgumentException("重试策略无效");
	}

	/** failures为含本次在内的失败次数，random取[0,1)。 */
	public long delayMillis(int failures, double random) {
		if (failures < 1 || random < 0 || random >= 1)
			throw new IllegalArgumentException("重试参数无效");
		long delay = Math.min(max.toMillis(), base.toMillis() << Math.min(failures - 1, 30));
		return delay + (long) (delay * jitter * random);
	}

	/**
	 * 没有瞬时计数列的车道（带业务截止时间的任务）遇瞬时失败时的固定延后：约32到38秒，不累加尝试次数； 重试总时长由任务自身的截止时间终止，频率由车道熔断约束。
	 */
	public static long deferralMillis(double random) {
		return TRANSIENT.delayMillis(5, random);
	}

	/** 本次失败后是否已用尽预算。 */
	public boolean exhausted(int failures) {
		return failures >= budget;
	}
}
