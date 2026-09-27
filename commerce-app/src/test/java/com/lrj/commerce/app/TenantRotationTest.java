package com.lrj.commerce.app;

import com.lrj.commerce.kernel.DomainException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import com.lrj.commerce.runtime.event.EventDispatcher;
import com.lrj.commerce.runtime.work.FailureClass;
import com.lrj.commerce.runtime.work.TenantRotation;

/**
 * 共享轮转原语的公平与隔离契约，用内存工作队列验证，不依赖数据库与机器速度： 时间预算设得足够大，以项数预算作为每轮的唯一限制。
 */
class TenantRotationTest {

	/** 内存车道：每个租户一个FIFO工作队列，失败注入按租户；失败项像真实车道一样进入退避，不再立即到期。 */
	static final class Lane {

		final TreeMap<String, Deque<Integer>> work = new TreeMap<>();

		final List<String> order = new ArrayList<>();

		final Map<String, RuntimeException> failing = new HashMap<>();

		final Map<String, Integer> deferred = new HashMap<>();

		void add(String tenant, int items) {
			var q = work.computeIfAbsent(tenant, k -> new ArrayDeque<>());
			for (int i = 0; i < items; i++)
				q.add(i);
		}

		List<String> tenants(String after, int limit) {
			return work.tailMap(after, false)
				.entrySet()
				.stream()
				.filter(e -> !e.getValue().isEmpty())
				.map(Map.Entry::getKey)
				.limit(limit)
				.toList();
		}

		int visit(String tenant, TenantRotation.Run run) {
			int count = 0;
			var q = work.get(tenant);
			for (int i = 0, limit = run.limit(); i < limit && !q.isEmpty() && !run.exhausted(); i++) {
				run.attempted();
				var failure = failing.get(tenant);
				if (failure != null) {
					q.poll();
					deferred.merge(tenant, 1, Integer::sum);
					if (run.failed(FailureClass.of(failure)))
						break;
					continue;
				}
				q.poll();
				order.add(tenant);
				count++;
				run.succeeded();
			}
			return count;
		}

		int pending() {
			return work.values().stream().mapToInt(Deque::size).sum();
		}

	}

	static TenantRotation rotation(int quantum, int batch, int maxItems, AtomicLong clock) {
		return new TenantRotation("test", new TenantRotation.Policy(quantum, batch, Duration.ofHours(1), maxItems),
				clock::get);
	}

	@Test
	void everyTenantWithWorkIsVisitedWithinOneRotationAndHotTenantGetsOneQuantumPerVisit() {
		var lane = new Lane();
		lane.add("a-hot", 1000);
		for (int t = 0; t < 40; t++)
			lane.add(String.format("b-%02d", t), 2);
		var rotation = rotation(5, 7, 100, new AtomicLong());
		rotation.run(lane::tenants, lane::visit);
		// 一轮100项：41个租户各访问一次（热租户5项、小租户各2项=85），余下15项继续下一圈。
		int lastSmall = 0;
		for (int i = 0; i < lane.order.size(); i++)
			if (lane.order.get(i).startsWith("b-"))
				lastSmall = i;
		assertTrue(lane.order.subList(0, lastSmall).stream().filter("a-hot"::equals).count() <= 5,
				"其他租户首次处理前热租户最多一个quantum");
		assertEquals(40, lane.order.stream().filter(t -> t.startsWith("b-")).distinct().count());
		assertEquals(100, lane.order.size(), "预算用尽前继续轮转");
		assertEquals(1, rotation.stats().rotations(), "完成一整圈");
		assertEquals(41, rotation.stats().lastRotationTenants());
	}

	@Test
	void loneTenantDrainsContinuouslyWithinBudget() {
		var lane = new Lane();
		lane.add("only", 250);
		var rotation = rotation(5, 50, 100, new AtomicLong());
		rotation.run(lane::tenants, lane::visit);
		assertEquals(150, lane.pending());
		rotation.run(lane::tenants, lane::visit);
		rotation.run(lane::tenants, lane::visit);
		assertEquals(0, lane.pending());
		assertEquals(0, rotation.run(lane::tenants, lane::visit), "没有工作时整圈无尝试即结束");
	}

	@Test
	void newTenantBehindTheCursorIsReachedAfterWrapping() {
		var lane = new Lane();
		for (int t = 0; t < 30; t++)
			lane.add(String.format("m-%02d", t), 20);
		var rotation = rotation(5, 10, 60, new AtomicLong());
		rotation.run(lane::tenants, lane::visit);
		lane.add("a-late", 1);
		lane.order.clear();
		// 游标停在m-11附近，新租户字典序在最前：必须在本圈剩余租户之后、下一圈开头被访问，而不是等游标自然走完所有历史。
		for (int i = 0; i < 10 && !lane.order.contains("a-late"); i++)
			rotation.run(lane::tenants, lane::visit);
		int position = lane.order.indexOf("a-late");
		assertTrue(position >= 0 && position <= 30 * 5, "最迟一整圈内被访问：" + position);
	}

	@Test
	void poisonTenantOnlyLosesItsOwnVisits() {
		var lane = new Lane();
		lane.add("a-poison", 50);
		for (int t = 0; t < 10; t++)
			lane.add("h-" + t, 3);
		lane.failing.put("a-poison", new DomainException(DomainException.Code.CONFLICT, "坏数据"));
		var rotation = rotation(5, 50, 1000, new AtomicLong());
		rotation.run(lane::tenants, lane::visit);
		assertEquals(50, lane.deferred.get("a-poison"), "毒租户每项失败后退避，交由车道自身的隔离预算处理");
		assertEquals(0, lane.pending(), "其他租户全部完成");
		assertEquals(30, lane.order.size());
		assertFalse(rotation.stats().breakerOpen(), "业务拒绝不是依赖故障，不熔断");
		assertTrue(rotation.stats().otherFailures() > 0);
	}

	@Test
	void visitExceptionIsIsolatedToThatTenant() {
		var lane = new Lane();
		lane.add("a-broken", 5);
		lane.add("b-ok", 5);
		var rotation = rotation(5, 50, 100, new AtomicLong());
		TenantRotation.Visit visit = (tenant, run) -> {
			if (tenant.equals("a-broken"))
				throw new ArithmeticException("到期查询失败");
			return lane.visit(tenant, run);
		};
		rotation.run(lane::tenants, visit);
		assertEquals(0, lane.work.get("b-ok").size(), "一个租户访问抛异常不能中断整轮");
	}

	@Test
	void consecutiveDependencyFailuresOpenTheBreakerWithBackoffAndOneSuccessClosesIt() {
		var clock = new AtomicLong();
		var lane = new Lane();
		for (int t = 0; t < 10; t++)
			lane.add("t-" + t, 3);
		for (int t = 0; t < 10; t++)
			lane.failing.put("t-" + t, new org.springframework.jdbc.CannotGetJdbcConnectionException("down"));
		var rotation = rotation(5, 50, 1000, clock);
		rotation.run(lane::tenants, lane::visit);
		var stats = rotation.stats();
		assertEquals(3, stats.items(), "第3次连续瞬时失败即熔断，不再领取后续租户的工作");
		assertTrue(stats.breakerOpen());
		assertEquals(1, stats.breakerTrips());
		assertEquals(0, rotation.run(lane::tenants, lane::visit));
		assertEquals(3, rotation.stats().items(), "冷却期内不领取");
		clock.addAndGet(Duration.ofSeconds(5).toNanos());
		rotation.run(lane::tenants, lane::visit);
		assertEquals(4, rotation.stats().items(), "半开：冷却后第一个瞬时失败立即再次熔断");
		assertEquals(2, rotation.stats().breakerTrips());
		clock.addAndGet(Duration.ofSeconds(5).toNanos());
		assertTrue(rotation.stats().breakerOpen(), "第二次冷却翻倍为10秒");
		clock.addAndGet(Duration.ofSeconds(5).toNanos());
		lane.failing.clear();
		rotation.run(lane::tenants, lane::visit);
		assertEquals(0, lane.pending(), "依赖恢复后首个成功关闭熔断，工作全部完成");
		assertFalse(rotation.stats().breakerOpen());
	}

	@Test
	void transientFailuresInterleavedWithSuccessDoNotOpenTheBreaker() {
		var lane = new Lane();
		for (int t = 0; t < 20; t++)
			lane.add("t-" + String.format("%02d", t), 1);
		for (int t = 0; t < 20; t += 2)
			lane.failing.put("t-" + String.format("%02d", t),
					new org.springframework.dao.CannotAcquireLockException("lock"));
		var rotation = rotation(5, 50, 1000, new AtomicLong());
		rotation.run(lane::tenants, lane::visit);
		assertFalse(rotation.stats().breakerOpen(), "单个租户的锁冲突不代表依赖整体不可用");
		assertEquals(10, lane.order.size());
		assertEquals(0, lane.pending());
	}

	@Test
	void discoveryFailureTripsTheBreakerAndPropagates() {
		var clock = new AtomicLong();
		var rotation = rotation(5, 50, 100, clock);
		TenantRotation.Discovery down = (after, limit) -> {
			throw new org.springframework.jdbc.CannotGetJdbcConnectionException("down");
		};
		for (int i = 0; i < 3; i++)
			assertThrows(org.springframework.jdbc.CannotGetJdbcConnectionException.class,
					() -> rotation.run(down, (t, r) -> 0));
		assertTrue(rotation.stats().breakerOpen(), "数据库整体不可用时车道暂停，不每秒重试");
		assertEquals(0, rotation.run(down, (t, r) -> 0), "冷却期内不再查询");
	}

	/** 事件车道的新到期阶段查询失败同样计入熔断：数据库不可用时第3轮后不再每轮查询（第一次停机实测中遗漏的路径）。 */
	@Test
	void eventFreshLaneDiscoveryFailureAlsoTripsTheBreaker() {
		var calls = new java.util.concurrent.atomic.AtomicInteger();
		var mapper = (com.lrj.commerce.runtime.event.persistence.EventMapper) java.lang.reflect.Proxy.newProxyInstance(
				getClass().getClassLoader(),
				new Class<?>[] { com.lrj.commerce.runtime.event.persistence.EventMapper.class },
				(proxy, method, args) -> {
					calls.incrementAndGet();
					throw new org.springframework.jdbc.CannotGetJdbcConnectionException("down");
				});
		var handler = new com.lrj.commerce.runtime.api.event.EventHandler() {
			public String consumer() {
				return "c";
			}

			public Set<String> types() {
				return Set.of("t.v1");
			}

			public void handle(Event e) {
			}
		};
		var dispatcher = new EventDispatcher(mapper, List.of(handler),
				org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class), null);
		for (int i = 0; i < 3; i++)
			assertThrows(org.springframework.jdbc.CannotGetJdbcConnectionException.class, dispatcher::tick);
		assertTrue(dispatcher.stats().breakerOpen());
		int before = calls.get();
		assertEquals(0, dispatcher.tick(), "冷却期内不访问数据库");
		assertEquals(before, calls.get());
	}

}
