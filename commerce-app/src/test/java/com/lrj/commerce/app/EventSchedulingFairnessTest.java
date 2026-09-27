package com.lrj.commerce.app;

import com.lrj.commerce.runtime.Commands;
import com.lrj.commerce.runtime.EventDispatcher;
import com.lrj.commerce.runtime.JsonCodec;
import com.lrj.commerce.runtime.api.*;
import com.lrj.commerce.runtime.persistence.EventMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 调度契约回归：每个用例使用本轮专属事件类型和独立调度器，历史残留不参与调度。 以事件数预算代替时间预算，使每轮处理量确定，断言不依赖机器速度。
 */
@SpringBootTest
class EventSchedulingFairnessTest {

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

	/** 每轮最多100次尝试，时间预算足够大，不成为限制条件。 */
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

	/** 按处理顺序记录租户，用于验证访问顺序与首次尝试。 */
	private final List<String> order = Collections.synchronizedList(new ArrayList<>());

	private final Map<String, Integer> firstTick = new ConcurrentHashMap<>();

	private final Map<String, AtomicInteger> calls = new ConcurrentHashMap<>();

	private final Set<String> poison = ConcurrentHashMap.newKeySet();

	private volatile int tick;

	@BeforeEach
	void run() {
		run = UUID.randomUUID().toString().substring(0, 8);
		type = "fair." + run + ".v1";
		prefix = "fair-" + run + "-";
	}

	@AfterEach
	void clean() {
		jdbc.update("DELETE FROM platform_inbox WHERE consumer_id LIKE ?", "fair-" + run + "-%");
		jdbc.update("DELETE FROM platform_event WHERE event_type=?", type);
	}

	@Test
	void newTenantIsAttemptedOnTheNextTickDespiteAManySmallTenantBacklog() {
		var dispatcher = dispatcher(handler("c0"));
		for (int t = 0; t < 300; t++)
			insert(prefix + "b-" + String.format("%04d", t), 3, true);
		tick(dispatcher);
		// 受害租户排在游标之前，旧的字典序轮转要等300/4=75轮才会访问。
		insert(prefix + "a-victim", 1, false);
		int arrival = tick;
		tick(dispatcher);
		assertEquals(arrival + 1, firstTick.get(prefix + "a-victim"), "新到期事件必须在下一轮尝试");
		// 积压轮转仍有界：每轮至少半个预算即50次尝试，900条积压最多18轮后全部租户被尝试。
		while (tick < arrival + 20 && pending() > 0)
			tick(dispatcher);
		assertEquals(301, firstTick.size());
		assertEquals(0, pending());
		assertTrue(firstTick.values().stream().mapToInt(Integer::intValue).max().orElseThrow() <= 19,
				firstTick.toString());
	}

	@Test
	void dominantTenantCannotMonopolizeATick() {
		var dispatcher = dispatcher(handler("c0"));
		// 占积压96%的租户字典序排第一，对轮转最不利。
		insert(prefix + "a-dominant", 1000, true);
		for (int t = 0; t < 40; t++)
			insert(prefix + "b-" + String.format("%03d", t), 1, true);
		tick(dispatcher);
		int lastSmall = 0;
		for (int i = 0; i < order.size(); i++)
			if (order.get(i).startsWith(prefix + "b-"))
				lastSmall = i;
		long dominantBefore = order.subList(0, lastSmall).stream().filter(t -> t.endsWith("a-dominant")).count();
		assertTrue(dominantBefore <= BUDGET.quantum(), "其他租户首次尝试前，主导租户最多处理一个quantum：" + dominantBefore);
		assertEquals(41, firstTick.size());
		// 其余预算继续用于主导租户，单轮总量受预算约束。
		assertEquals(BUDGET.maxAttempts(), order.size());
	}

	@Test
	void singleTenantBacklogUsesTheWholeTickBudget() {
		var dispatcher = dispatcher(handler("c0"));
		insert(prefix + "a-only", 250, true);
		tick(dispatcher);
		assertEquals(100, order.size(), "旧实现每轮只处理5条");
		tick(dispatcher);
		tick(dispatcher);
		assertEquals(0, pending());
		assertEquals(250, order.size());
	}

	@Test
	void poisonEventIsQuarantinedWithEvidenceWithoutBlockingHealthyEvents() {
		var dispatcher = dispatcher(handler("c0"), handler("c1"));
		insert(prefix + "a-mixed", 1, false);
		String bad = jdbc.queryForObject("SELECT event_id FROM platform_event WHERE event_type=?", String.class, type);
		insert(prefix + "a-mixed", 10, false);
		insert(prefix + "b-other", 5, false);
		poison.add(bad);
		tick(dispatcher);
		// 同租户与其他租户的健康事件在首轮全部投递，毒事件只占一个名额。
		assertEquals(1, pending());
		assertEquals(1, calls.get("c1/" + bad).get());
		for (int i = 0; i < 10 && !status(bad).equals("ISOLATED"); i++) {
			due(bad);
			tick(dispatcher);
		}
		assertEquals("ISOLATED", status(bad));
		assertEquals(EventDispatcher.MAX_ATTEMPTS, attempts(bad));
		assertEquals(EventDispatcher.MAX_ATTEMPTS, calls.get("c1/" + bad).get(), "隔离后不再消耗调度名额");
		assertEquals(1, calls.get("c0/" + bad).get(), "成功的兄弟消费者不重复执行");
		assertEquals("fair-" + run + "-c1:IllegalStateException", lastError(bad));
		due(bad);
		tick(dispatcher);
		assertEquals(EventDispatcher.MAX_ATTEMPTS, calls.get("c1/" + bad).get());
		var admin = new Actor(prefix + "a-mixed", "admin", Actor.Role.ADMIN);
		var health = dispatcher.health(admin);
		assertEquals(1, health.isolated());
		assertEquals(0, health.due());
		// 修复后受控重放：只执行此前失败的消费者。
		poison.clear();
		assertEquals(1, dispatcher.retry(admin, "retry-" + run, bad));
		tick(dispatcher);
		assertEquals("DELIVERED", status(bad));
		assertEquals(1, calls.get("c0/" + bad).get());
	}

	/** 历史载荷缺少后来新增的基本类型字段时，解码失败按毒事件隔离，证据只含类型不含载荷。 */
	record Signal(String memberId, long policyVersion) {
	}

	@Test
	void malformedLegacyPayloadIsQuarantined() {
		var decoding = new EventHandler() {
			public String consumer() {
				return "fair-" + run + "-decode";
			}

			public Set<String> types() {
				return Set.of(type);
			}

			public void handle(Event event) {
				JsonCodec.read(event.payloadJson(), Signal.class);
			}
		};
		var dispatcher = dispatcher(decoding);
		jdbc.update(
				"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status) VALUES(?,?,?,?,1,'{\"memberId\":\"m1\"}','PENDING')",
				prefix + "legacy", prefix + "a", type, "agg");
		for (int i = 0; i < 10 && !status(prefix + "legacy").equals("ISOLATED"); i++) {
			due(prefix + "legacy");
			tick(dispatcher);
		}
		assertEquals("ISOLATED", status(prefix + "legacy"));
		assertEquals("fair-" + run + "-decode:MismatchedInputException", lastError(prefix + "legacy"));
	}

	@Test
	void concurrentWorkersNeverProcessAConsumerTwice() throws Exception {
		for (int t = 0; t < 20; t++)
			insert(prefix + "t-" + t, 25, true);
		var handlers = List.of(handler("c0"), handler("c1"));
		var pool = Executors.newFixedThreadPool(4);
		try {
			var futures = new ArrayList<Future<?>>();
			for (int w = 0; w < 4; w++) {
				var dispatcher = dispatcher(handlers.toArray(EventHandler[]::new));
				futures.add(pool.submit(() -> {
					for (int i = 0; i < 50 && pending() > 0; i++)
						dispatcher.tick();
				}));
			}
			for (var f : futures)
				f.get(120, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}
		assertEquals(0, pending());
		assertEquals(1000, calls.size(), "两个消费者各500条");
		assertTrue(calls.values().stream().allMatch(c -> c.get() == 1), "同一消费者对同一事件只执行一次");
		assertEquals(1000, jdbc.queryForObject("SELECT COUNT(*) FROM platform_inbox WHERE consumer_id LIKE ?",
				Integer.class, "fair-" + run + "-%"));
	}

	private EventDispatcher dispatcher(EventHandler... handlers) {
		return new EventDispatcher(mapper, List.of(handlers), transactions, commands, BUDGET);
	}

	private EventHandler handler(String name) {
		String consumer = "fair-" + run + "-" + name;
		return new EventHandler() {
			public String consumer() {
				return consumer;
			}

			public Set<String> types() {
				return Set.of(type);
			}

			public void handle(Event event) {
				calls.computeIfAbsent(name + "/" + event.eventId(), k -> new AtomicInteger()).incrementAndGet();
				if (poison.contains(event.eventId()) && name.equals("c1"))
					throw new IllegalStateException("故障注入");
				if (name.equals("c0")) {
					order.add(event.tenantId());
					firstTick.putIfAbsent(event.tenantId(), tick);
				}
			}
		};
	}

	private void tick(EventDispatcher dispatcher) {
		tick++;
		dispatcher.tick();
	}

	/** aged表示历史积压，到期时间早于新到窗口。 */
	private void insert(String tenant, int count, boolean aged) {
		var rows = new ArrayList<Object[]>();
		for (int i = 0; i < count; i++) {
			String id = UUID.randomUUID().toString();
			rows.add(new Object[] { id, tenant, type, id, aged ? 3600 : 0 });
		}
		jdbc.batchUpdate(
				"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,available_at) VALUES(?,?,?,?,1,'{}','PENDING',TIMESTAMPADD(SECOND,-?,CURRENT_TIMESTAMP(3)))",
				rows);
	}

	private void due(String id) {
		jdbc.update("UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(3) WHERE event_id=? AND status='PENDING'",
				id);
	}

	private long pending() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE event_type=? AND status='PENDING'",
				Long.class, type);
	}

	private String status(String id) {
		return jdbc.queryForObject("SELECT status FROM platform_event WHERE event_id=?", String.class, id);
	}

	private int attempts(String id) {
		return jdbc.queryForObject("SELECT attempts FROM platform_event WHERE event_id=?", Integer.class, id);
	}

	private String lastError(String id) {
		return jdbc.queryForObject("SELECT last_error FROM platform_event WHERE event_id=?", String.class, id);
	}

}
