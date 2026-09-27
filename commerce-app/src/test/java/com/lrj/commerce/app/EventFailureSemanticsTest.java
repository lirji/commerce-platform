package com.lrj.commerce.app;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.event.persistence.EventMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import com.lrj.commerce.runtime.api.event.EventHandler;
import com.lrj.commerce.runtime.api.event.UnconsumedEventType;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.event.EventDispatcher;
import com.lrj.commerce.runtime.event.Outbox;

/**
 * 第三阶段事件失败语义：瞬时失败不把健康事件耗成隔离、连续依赖故障熔断、毒事件有界终止并保留证据、
 * 人工重放只执行未完成消费者、无消费者事件的显式终态，以及重试不挤占新到事件。每个用例使用专属事件类型。
 */
@SpringBootTest
class EventFailureSemanticsTest {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		String url = System.getenv("COMMERCE_TEST_DB_URL");
		if (url == null || !url.contains("/commerce_test_20260923?"))
			throw new IllegalStateException("必须显式指定本项目隔离测试库");
		registry.add("spring.datasource.url", () -> url);
		registry.add("commerce.sandbox-enabled", () -> true);
		registry.add("commerce.workers-enabled", () -> false);
		registry.add("spring.datasource.username", () -> System.getenv("COMMERCE_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("COMMERCE_DB_PASSWORD"));
	}

	static final EventDispatcher.Budget BUDGET = new EventDispatcher.Budget(5, 50, Duration.ofSeconds(60), 100,
			Duration.ofSeconds(10));

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EventMapper mapper;

	@Autowired
	PlatformTransactionManager transactions;

	@Autowired
	Commands commands;

	private String run, type, prefix;

	private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

	/** 消费者名到当前注入的故障。 */
	private final Map<String, RuntimeException> failing = new ConcurrentHashMap<>();

	private final List<String> order = Collections.synchronizedList(new ArrayList<>());

	@BeforeEach
	void run() {
		run = UUID.randomUUID().toString().substring(0, 8);
		type = "fail." + run + ".v1";
		prefix = "fail-" + run + "-";
	}

	@AfterEach
	void clean() {
		jdbc.update("DELETE FROM platform_inbox WHERE consumer_id LIKE ?", "fail-" + run + "-%");
		jdbc.update("DELETE FROM platform_event WHERE event_type IN (?,?)", type, type + ".none");
	}

	/** 场景E：数据库连接池耗尽或依赖不可用持续远超30秒，健康事件不能被隔离；恢复后正常投递。 */
	@Test
	void temporaryOutageNeverQuarantinesHealthyEvents() {
		var dispatcher = dispatcher(handler("c0"));
		String id = insert(prefix + "a", false);
		failing.put("c0", new org.springframework.jdbc.CannotGetJdbcConnectionException("连接池耗尽"));
		// 10次失败已是旧预算（5次隔离）的两倍；每次失败后熔断冷却也不影响后续用例，用新调度器模拟冷却结束。
		for (int i = 0; i < 10; i++) {
			due(id);
			dispatcher(handler("c0")).tick();
		}
		assertEquals("PENDING", status(id));
		assertEquals(0, intValue(id, "attempts"), "瞬时失败不消耗毒事件预算");
		assertEquals(10, intValue(id, "transient_attempts"));
		assertEquals("DEPENDENCY_UNAVAILABLE", value(id, "failure_class"));
		assertEquals("fail-" + run + "-c0:CannotGetJdbcConnectionException", value(id, "last_error"));
		assertNotNull(value(id, "first_failed_at"));
		// 瞬时退避按2秒起指数增长：第10次后下次尝试至少在256秒后。
		assertTrue(jdbc.queryForObject(
				"SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP(3),available_at) FROM platform_event WHERE event_id=?",
				Long.class, id) >= 250);
		failing.clear();
		due(id);
		dispatcher.tick();
		assertEquals("DELIVERED", status(id));
	}

	/** 连续3次瞬时失败（来自不同租户）熔断：同一轮不再领取其他租户的事件，健康事件不受任何计数影响。 */
	@Test
	void dependencyOutageOpensTheBreakerInsteadOfBurningEveryEvent() {
		for (int t = 0; t < 20; t++)
			insert(prefix + String.format("t-%02d", t), true);
		failing.put("c0", new DomainException(DomainException.Code.UNAVAILABLE, "渠道不可用"));
		var dispatcher = dispatcher(handler("c0"));
		dispatcher.tick();
		assertEquals(3,
				jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE event_type=? AND transient_attempts>0",
						Integer.class, type),
				"熔断前只尝试3个事件");
		assertTrue(dispatcher.stats().breakerOpen());
		assertEquals(1, dispatcher.stats().breakerTrips());
		failing.clear();
		dispatcher.tick();
		assertEquals(0, delivered(), "冷却期内不领取");
		dispatcher(handler("c0")).tick();
		assertEquals(17, delivered(), "新调度器（冷却结束）投递其余健康事件，退避中的3个稍后投递");
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE event_type=? AND attempts>0",
				Integer.class, type));
	}

	/** 业务拒绝走有界毒事件预算：第5次隔离，隔离证据完整；第二个消费者成功后不重复执行。 */
	@Test
	void businessRejectionQuarantinesWithCompleteEvidenceAndRetryRunsOnlyUnfinishedConsumers() {
		var dispatcher = dispatcher(handler("c0"), handler("c1"));
		String id = insert(prefix + "a", false);
		failing.put("c1", new DomainException(DomainException.Code.CONFLICT, "权益已用尽"));
		for (int i = 0; i < 10 && !status(id).equals("ISOLATED"); i++) {
			due(id);
			dispatcher.tick();
		}
		assertEquals("ISOLATED", status(id));
		assertEquals(EventDispatcher.MAX_ATTEMPTS, intValue(id, "attempts"));
		assertEquals(0, intValue(id, "transient_attempts"));
		assertEquals("BUSINESS_REJECTED", value(id, "failure_class"));
		assertEquals("fail-" + run + "-c1:DomainException/CONFLICT", value(id, "last_error"));
		String first = value(id, "first_failed_at"), last = value(id, "last_failed_at");
		assertNotNull(first);
		assertTrue(last.compareTo(first) > 0);
		assertEquals(1, calls.get("c0/" + id).get());
		assertEquals(5, calls.get("c1/" + id).get());
		var view = mapper.list(prefix + "a", "", 10).getFirst();
		assertEquals("BUSINESS_REJECTED", view.failureClass());
		assertEquals(5, view.attempts());
		assertNotNull(view.firstFailedAt());
		// 人工重放：不清除证据，只执行未成功的c1。
		failing.clear();
		var admin = new Actor(prefix + "a", "admin", Actor.Role.ADMIN);
		assertEquals(1, dispatcher.retry(admin, "retry-" + run, id));
		assertEquals(1, intValue(id, "manual_retries"));
		assertEquals("BUSINESS_REJECTED", value(id, "failure_class"));
		assertEquals(first, value(id, "first_failed_at"));
		dispatcher.tick();
		assertEquals("DELIVERED", status(id));
		assertEquals(1, calls.get("c0/" + id).get(), "已成功消费者不重复执行");
		assertEquals(6, calls.get("c1/" + id).get());
		assertEquals(1, dispatcher.retry(admin, "retry-" + run, id), "相同幂等键重放返回原结果");
		var conflict = assertThrows(DomainException.class, () -> dispatcher.retry(admin, "retry2-" + run, id));
		assertEquals(DomainException.Code.CONFLICT, conflict.code());
	}

	/** 同一事件一个消费者瞬时失败、另一个业务拒绝：按更严重的毒事件预算计数，不被瞬时类掩盖。 */
	@Test
	void mixedFailureCountsTowardThePoisonBudget() {
		var dispatcher = dispatcher(handler("c0"), handler("c1"));
		String id = insert(prefix + "a", false);
		failing.put("c0", new org.springframework.dao.CannotAcquireLockException("锁等待"));
		failing.put("c1", new DomainException(DomainException.Code.CONFLICT, "x"));
		dispatcher.tick();
		assertEquals(1, intValue(id, "attempts"));
		assertEquals(0, intValue(id, "transient_attempts"));
		assertEquals("BUSINESS_REJECTED", value(id, "failure_class"));
	}

	/** 场景H：大量到期重试不占用新到事件的优先阶段，新租户的新事件在第一轮就被处理。 */
	@Test
	void retryStormDoesNotDelayFreshEvents() {
		for (int t = 0; t < 300; t++) {
			String id = insert(prefix + String.format("r-%03d", t), false);
			jdbc.update(
					"UPDATE platform_event SET attempts=1,available_at=TIMESTAMPADD(SECOND,-5,CURRENT_TIMESTAMP(3)) WHERE event_id=?",
					id);
		}
		String fresh = insert(prefix + "z-fresh", false);
		dispatcher(handler("c0")).tick();
		assertEquals("DELIVERED", status(fresh), "新事件不排在300个到期重试之后");
		assertTrue(order.indexOf(prefix + "z-fresh") < 5, "新事件在优先阶段处理：" + order.indexOf(prefix + "z-fresh"));
	}

	/** B1：发布方声明无消费者的类型写入即SKIPPED；未声明又无消费者的保持PENDING并计为unrouted；声明与消费者冲突启动失败。 */
	@Test
	void noConsumerEventsHaveExplicitSemantics() {
		var declared = new UnconsumedEventType(type + ".none", UnconsumedEventType.Reason.NO_REGISTERED_CONSUMER);
		var outbox = new Outbox(mapper, List.of(declared));
		var tx = new TransactionTemplate(transactions);
		tx.executeWithoutResult(s -> {
			outbox.append(prefix + "a", type + ".none", "agg-1", 1, Map.of("k", "v"));
			outbox.append(prefix + "a", type, "agg-2", 1, Map.of("k", "v"));
		});
		assertEquals("SKIPPED", jdbc.queryForObject("SELECT status FROM platform_event WHERE event_type=?",
				String.class, type + ".none"));
		assertEquals("NO_REGISTERED_CONSUMER", jdbc
			.queryForObject("SELECT skip_reason FROM platform_event WHERE event_type=?", String.class, type + ".none"));
		assertEquals(1, outbox.skipped());
		assertEquals("PENDING",
				jdbc.queryForObject("SELECT status FROM platform_event WHERE event_type=?", String.class, type));
		// 本调度器没有任何消费者处理type：它是缺少必需消费者，而不是被静默跳过。
		var other = new EventHandler() {
			public String consumer() {
				return "fail-" + run + "-other";
			}

			public Set<String> types() {
				return Set.of("fail." + run + ".unrelated.v1");
			}

			public void handle(Event e) {
			}
		};
		var dispatcher = new EventDispatcher(mapper, List.of(other), transactions, commands, BUDGET, List.of(declared));
		assertEquals(1, dispatcher.health(prefix + "a").unrouted(), "未声明且无消费者：计入unrouted并告警");
		String skipped = jdbc.queryForObject("SELECT event_id FROM platform_event WHERE event_type=?", String.class,
				type + ".none");
		var admin = new Actor(prefix + "a", "admin", Actor.Role.ADMIN);
		assertEquals(DomainException.Code.CONFLICT,
				assertThrows(DomainException.class, () -> dispatcher.retry(admin, "k-" + run, skipped)).code(),
				"没有消费者的类型不能重放");
		var stale = assertThrows(IllegalStateException.class,
				() -> new EventDispatcher(mapper, List.of(handler("c0")), transactions, commands, BUDGET,
						List.of(new UnconsumedEventType(type, UnconsumedEventType.Reason.NO_REGISTERED_CONSUMER))));
		assertTrue(stale.getMessage().contains(type));
		assertThrows(IllegalStateException.class, () -> new Outbox(mapper, List.of(declared, declared)), "重复声明");
	}

	/** 已跳过的事件在出现消费者后可由管理员显式重放，只投递给当前消费者。 */
	@Test
	void skippedEventCanBeReplayedOnceAConsumerExists() {
		var tx = new TransactionTemplate(transactions);
		tx.executeWithoutResult(s -> new Outbox(mapper,
				List.of(new UnconsumedEventType(type, UnconsumedEventType.Reason.NO_REGISTERED_CONSUMER)))
			.append(prefix + "a", type, "agg", 1, Map.of()));
		String id = jdbc.queryForObject("SELECT event_id FROM platform_event WHERE event_type=?", String.class, type);
		var dispatcher = dispatcher(handler("c0"));
		dispatcher.tick();
		assertEquals("SKIPPED", status(id), "SKIPPED是终态，调度器不领取");
		assertEquals(1, dispatcher.retry(new Actor(prefix + "a", "admin", Actor.Role.ADMIN), "k-" + run, id));
		dispatcher.tick();
		assertEquals("DELIVERED", status(id));
		assertEquals("NO_REGISTERED_CONSUMER", value(id, "skip_reason"), "跳过历史保留");
	}

	/** 迁移V36把历史order.fulfilling.v1从永久PENDING转为SKIPPED，生产装配声明了该类型。 */
	@Test
	void fulfillingEventsAreNoLongerPendingForever(@Autowired List<UnconsumedEventType> declarations) {
		assertTrue(declarations.stream().anyMatch(d -> d.type().equals("order.fulfilling.v1")));
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_event WHERE event_type='order.fulfilling.v1' AND status='PENDING'",
				Integer.class));
	}

	private EventDispatcher dispatcher(EventHandler... handlers) {
		return new EventDispatcher(mapper, List.of(handlers), transactions, commands, BUDGET);
	}

	private EventHandler handler(String name) {
		String consumer = "fail-" + run + "-" + name;
		return new EventHandler() {
			public String consumer() {
				return consumer;
			}

			public Set<String> types() {
				return Set.of(type);
			}

			public void handle(Event event) {
				calls.computeIfAbsent(name + "/" + event.eventId(), k -> new AtomicInteger()).incrementAndGet();
				var failure = failing.get(name);
				if (failure != null)
					throw failure;
				if (name.equals("c0"))
					order.add(event.tenantId());
			}
		};
	}

	private String insert(String tenant, boolean aged) {
		String id = UUID.randomUUID().toString();
		jdbc.update(
				"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,available_at) VALUES(?,?,?,?,1,'{}','PENDING',TIMESTAMPADD(SECOND,-?,CURRENT_TIMESTAMP(3)))",
				id, tenant, type, id, aged ? 3600 : 0);
		return id;
	}

	private void due(String id) {
		jdbc.update("UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(3) WHERE event_id=? AND status='PENDING'",
				id);
	}

	private long delivered() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE event_type=? AND status='DELIVERED'",
				Long.class, type);
	}

	private String status(String id) {
		return value(id, "status");
	}

	private String value(String id, String column) {
		return jdbc.queryForObject("SELECT CAST(" + column + " AS CHAR) FROM platform_event WHERE event_id=?",
				String.class, id);
	}

	private int intValue(String id, String column) {
		return jdbc.queryForObject("SELECT " + column + " FROM platform_event WHERE event_id=?", Integer.class, id);
	}

}
