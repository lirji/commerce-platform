package com.lrj.commerce.runtime.work;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 租户公平轮转，事件调度在第二阶段验证过的语义推广到各后台车道： 按租户字典序游标分批发现有到期工作的租户，每次访问最多quantum项；一轮受时间与项数预算约束，
 * 走到末尾回到开头继续，整圈没有任何尝试才结束，因此任一有到期工作的租户最迟在一整圈轮转内被访问。
 * 全局只有一个租户有到期工作时连续处理到取空或预算用完。租户内顺序、事务和重试状态由车道自己负责。
 * 依赖熔断：连续3次瞬时失败（通常来自不同租户的连续访问）说明依赖整体不可用，车道暂停5秒起翻倍、最长1分钟，
 * 期间不领取工作、不消耗任何工作项的重试预算；冷却后首个成功即恢复。上限1分钟使依赖恢复后车道最迟1分钟内恢复处理。
 */
public final class TenantRotation {

	/** 连续瞬时失败达到此数即熔断。 */
	public static final int BREAKER_STREAK = 3;
	static final Duration BREAKER_BASE = Duration.ofSeconds(5), BREAKER_MAX = Duration.ofMinutes(1);

	private static final Logger log = LoggerFactory.getLogger(TenantRotation.class);

	/** quantum为单次访问单租户的工作项上限，tenantBatch为每次发现的租户数，tick与maxItems共同限制一轮占用调度线程的时间和项数。 */
	public record Policy(int quantum, int tenantBatch, Duration tick, int maxItems) {
		public Policy {
			if (quantum < 1 || tenantBatch < 1 || maxItems < 2 || tick.isNegative() || tick.isZero())
				throw new IllegalArgumentException("轮转预算无效");
		}
	}

	/** 从游标之后按租户字典序返回至多limit个有到期工作的租户。 */
	@FunctionalInterface
	public interface Discovery {

		List<String> tenants(String after, int limit);

	}

	/** 访问一个租户：每领取一项调用run.attempted()，项数不超过run.limit()；返回成功完成的项数。 */
	@FunctionalInterface
	public interface Visit {

		int visit(String tenant, Run run);

	}

	/** 进程内累计观测，不含租户标识，可直接作为低基数指标。 */
	public record Stats(String lane, long runs, long items, long completed, long transientFailures, long otherFailures,
			long rotations, long lastRotationMillis, int lastRotationTenants, long breakerTrips, boolean breakerOpen,
			long lastRunMillis, Instant lastRunAt) {
	}

	private final String lane;

	private final Policy policy;

	private final LongSupplier nanos;

	private String cursor = "";

	private long rotationStartedAt;

	private int rotationTenants;

	/** 熔断状态单独加锁：管理端手工触发与后台轮转并发时，不等待整轮轮转结束。 */
	private final Object breaker = new Object();

	private int streak, trips;

	private long openUntil;

	private boolean open;

	private final LongAdder runs = new LongAdder(), items = new LongAdder(), completed = new LongAdder(),
			transientFailures = new LongAdder(), otherFailures = new LongAdder(), rotations = new LongAdder(),
			breakerTrips = new LongAdder();

	private volatile long lastRotationMillis = -1, lastRunMillis = -1;

	private volatile int lastRotationTenants;

	private volatile Instant lastRunAt;

	public TenantRotation(String lane, Policy policy) {
		this(lane, policy, System::nanoTime);
	}

	/** 测试注入单调时钟以确定熔断冷却；生产使用System.nanoTime。 */
	public TenantRotation(String lane, Policy policy, LongSupplier nanos) {
		this.lane = lane;
		this.policy = policy;
		this.nanos = nanos;
		rotationStartedAt = nanos.getAsLong();
	}

	/** 一轮的预算与熔断视图；只在执行本轮的线程内使用。 */
	public final class Run {

		private final long started = nanos.getAsLong(), deadline = started + policy.tick().toNanos();

		private int items;

		private boolean aborted;

		private Run(boolean blocked) {
			aborted = blocked;
		}

		public boolean exhausted() {
			return aborted || items >= policy.maxItems() || nanos.getAsLong() - deadline >= 0;
		}

		/** 前半预算：优先阶段（如新到期事件）最多使用一半，后续轮转始终保有另一半。 */
		public boolean halfExhausted() {
			return aborted || items >= policy.maxItems() / 2
					|| nanos.getAsLong() - (started + policy.tick().toNanos() / 2) >= 0;
		}

		/** 本次租户访问还可领取的项数。 */
		public int limit() {
			return Math.max(0, Math.min(policy.quantum(), policy.maxItems() - items));
		}

		public int items() {
			return items;
		}

		public void attempted() {
			items++;
			TenantRotation.this.items.increment();
		}

		/** 工作项成功完成，同时说明依赖可用，清除熔断计数。 */
		public void succeeded() {
			completed.increment();
			recovered();
		}

		/** 记录失败并返回是否为瞬时失败；瞬时失败时调用方应结束对该租户的本次访问，熔断时本轮立即结束。 */
		public boolean failed(FailureClass failure) {
			if (!failure.transientFailure()) {
				otherFailures.increment();
				return false;
			}
			transientFailures.increment();
			if (transientFailed(failure))
				aborted = true;
			return true;
		}

	}

	private void recovered() {
		synchronized (breaker) {
			streak = 0;
			trips = 0;
		}
	}

	private boolean transientFailed(FailureClass failure) {
		long cooldown;
		synchronized (breaker) {
			if (++streak < BREAKER_STREAK)
				return false;
			trips++;
			cooldown = Math.min(BREAKER_MAX.toNanos(), BREAKER_BASE.toNanos() << Math.min(trips - 1, 16));
			openUntil = nanos.getAsLong() + cooldown;
			open = true;
		}
		breakerTrips.increment();
		log.warn("lane dependency breaker open lane={} failureClass={} cooldownMs={}", lane, failure,
				cooldown / 1_000_000);
		return true;
	}

	/** 车道自有的发现查询（如事件的新到期阶段）也计入熔断，否则数据库不可用时该车道每轮都会重试。 */
	public <T> T discover(Run run, java.util.function.Supplier<T> query) {
		try {
			return query.get();
		}
		catch (RuntimeException failure) {
			run.failed(FailureClass.of(failure));
			throw failure;
		}
	}

	/** 熔断冷却期内返回已结束的轮次，调用方不领取工作。 */
	public Run start() {
		boolean blocked;
		synchronized (breaker) {
			blocked = open && nanos.getAsLong() - openUntil < 0;
			if (!blocked)
				open = false;
		}
		runs.increment();
		lastRunAt = Instant.now();
		return new Run(blocked);
	}

	/** 管理端手工触发只用预算，不参与轮转统计。 */
	public Run manual() {
		return new Run(false);
	}

	/** 新建一轮并执行完整轮转。 */
	public synchronized int run(Discovery discovery, Visit visit) {
		return rotate(start(), discovery, visit);
	}

	/** 在已有预算上继续轮转，供先执行优先阶段的车道使用。 */
	public synchronized int rotate(Run run, Discovery discovery, Visit visit) {
		int count = 0, wraps = 0, itemsAtWrap = run.items;
		try {
			while (!run.exhausted()) {
				List<String> tenants;
				try {
					tenants = discovery.tenants(cursor, policy.tenantBatch());
				}
				catch (RuntimeException failure) {
					run.failed(FailureClass.of(failure));
					throw failure;
				}
				if (tenants.isEmpty()) {
					rotationCompleted();
					cursor = "";
					if (wraps++ > 0 && run.items == itemsAtWrap)
						break;
					itemsAtWrap = run.items;
					continue;
				}
				if (cursor.isEmpty() && tenants.size() == 1) {
					// 全局只有一个租户有到期工作：无人竞争，连续处理到取空或预算用完，避免每个quantum重扫该租户。
					String only = tenants.getFirst();
					int before;
					do {
						before = run.items;
						count += visit(only, run, visit);
					}
					while (run.items - before == policy.quantum() && !run.exhausted());
					cursor = only;
					rotationTenants++;
					continue;
				}
				for (var tenant : tenants) {
					if (run.exhausted())
						break;
					count += visit(tenant, run, visit);
					cursor = tenant;
					rotationTenants++;
				}
			}
		}
		finally {
			lastRunMillis = (nanos.getAsLong() - run.started) / 1_000_000;
		}
		return count;
	}

	/** 单租户访问失败只影响该租户：分类计数后继续下一个租户，不让一个坏租户中断整轮。 */
	public int visit(String tenant, Run run, Visit visit) {
		try {
			return visit.visit(tenant, run);
		}
		catch (RuntimeException failure) {
			var type = FailureClass.of(failure);
			run.failed(type);
			log.warn("lane visit failed lane={} tenant={} failureClass={} errorType={}", lane, tenant, type,
					failure.getClass().getSimpleName());
			return 0;
		}
	}

	private void rotationCompleted() {
		long now = nanos.getAsLong();
		if (rotationTenants > 0) {
			rotations.increment();
			lastRotationMillis = (now - rotationStartedAt) / 1_000_000;
			lastRotationTenants = rotationTenants;
		}
		rotationStartedAt = now;
		rotationTenants = 0;
	}

	public boolean breakerOpen() {
		synchronized (breaker) {
			return open && nanos.getAsLong() - openUntil < 0;
		}
	}

	public Stats stats() {
		return new Stats(lane, runs.sum(), items.sum(), completed.sum(), transientFailures.sum(), otherFailures.sum(),
				rotations.sum(), lastRotationMillis, lastRotationTenants, breakerTrips.sum(), breakerOpen(),
				lastRunMillis, lastRunAt);
	}

	public Policy policy() {
		return policy;
	}

	public String lane() {
		return lane;
	}

}
