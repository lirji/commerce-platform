package com.lrj.commerce.app;

import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.event.EventDispatcher;
import com.lrj.commerce.runtime.api.event.EventHandler;
import com.lrj.commerce.runtime.event.persistence.EventMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** 积压与租户公平基准：只注册本轮专属事件类型，历史残留不参与调度但参与索引扫描；需显式 -Dcommerce.event-benchmark=true。 */
@SpringBootTest
@EnabledIfSystemProperty(named = "commerce.event-benchmark", matches = "true")
class EventSchedulingBenchmarkTest {

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

	/** 生产调度器固定延迟，模型时间=各轮耗时+轮间延迟，不含其他后台任务，属于下界。 */
	static final long FIXED_DELAY_MS = 1000;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EventMapper mapper;

	@Autowired
	PlatformTransactionManager transactions;

	@Autowired
	Commands commands;

	final Map<String, Integer> firstTick = new ConcurrentHashMap<>();

	final Map<String, Integer> handled = new ConcurrentHashMap<>();

	volatile int currentTick;

	record Scenario(String name, int dominant, int smallTenants, int perSmall, boolean victim, int consumers) {
		int total() {
			return dominant + smallTenants * perSmall;
		}
	}

	@Test
	void backlogScenarios() throws Exception {
		String label = System.getProperty("commerce.event-benchmark.label", "current");
		var sizes = Arrays.stream(System.getProperty("commerce.event-benchmark.sizes", "100,1000,10000").split(","))
			.map(Integer::parseInt)
			.toList();
		var scenarios = new ArrayList<Scenario>();
		for (int n : sizes) {
			scenarios.add(new Scenario("single-tenant-" + n, n, 0, 0, false, 1));
			scenarios.add(new Scenario("balanced-20-" + n, 0, 20, Math.max(1, n / 20), false, 1));
			scenarios.add(new Scenario("dominant-90pct-" + n, n * 9 / 10, Math.max(1, n / 10 / 2), 2, true, 1));
			scenarios.add(new Scenario("many-small-" + n, 0, Math.max(1, n / 4), 4, true, 1));
		}
		scenarios.add(new Scenario("fixed-300-1-consumer", 0, 10, 30, false, 1));
		scenarios.add(new Scenario("fixed-300-3-consumers", 0, 10, 30, false, 3));
		var out = new StringBuilder();
		for (var s : scenarios) {
			var line = run(label, s);
			System.out.println("BENCH " + line);
			out.append(line).append('\n');
		}
		Path file = Path.of("target", "event-benchmark-" + label + ".jsonl");
		Files.writeString(file, out);
	}

	private String run(String label, Scenario s) {
		firstTick.clear();
		handled.clear();
		currentTick = 0;
		String run = UUID.randomUUID().toString().substring(0, 8), type = "bench." + run + ".v1",
				prefix = "bench-" + run + "-";
		var handlers = new ArrayList<EventHandler>();
		for (int c = 0; c < s.consumers(); c++) {
			String consumer = "bench-" + run + "-c" + c;
			handlers.add(handler(consumer, type));
		}
		var dispatcher = dispatcher(handlers);
		// 其他租户以b前缀排序；受害租户以a前缀排序，在轮转游标之后到达，代表字典序轮转的最坏位置。
		var rows = new ArrayList<Object[]>();
		for (int i = 0; i < s.dominant(); i++)
			rows.add(row(prefix + "b-dominant", type, i));
		for (int t = 0; t < s.smallTenants(); t++)
			for (int i = 0; i < s.perSmall(); i++)
				rows.add(row(prefix + "b-" + String.format("%06d", t), type, i));
		insert(rows);
		// aged=true把积压设为1小时前到期，代表历史积压；否则代表刚到达的突发流量，两者都在新到窗口内竞争。
		if (Boolean.getBoolean("commerce.event-benchmark.aged"))
			jdbc.update("UPDATE platform_event SET available_at=TIMESTAMPADD(HOUR,-1,available_at) WHERE event_type=?",
					type);
		long depthBefore = depth(type);
		long oldestBefore = oldestMs(type);
		int tenants = (s.dominant() > 0 ? 1 : 0) + s.smallTenants();
		long processingNanos = 0;
		int victimArrivalTick = -1;
		long victimArrivalModelNanos = 0;
		int maxTicks = Integer.getInteger("commerce.event-benchmark.max-ticks", 4000);
		long wallStart = System.nanoTime();
		while (currentTick < maxTicks) {
			currentTick++;
			long start = System.nanoTime();
			dispatcher.tick();
			processingNanos += System.nanoTime() - start;
			if (s.victim() && victimArrivalTick < 0) {
				insert(List.<Object[]>of(row(prefix + "a-victim", type, 0)));
				victimArrivalTick = currentTick;
				victimArrivalModelNanos = processingNanos + currentTick * FIXED_DELAY_MS * 1_000_000L;
			}
			if (depth(type) == 0)
				break;
			if (System.nanoTime() - wallStart > Long.getLong("commerce.event-benchmark.max-seconds", 600L)
					* 1_000_000_000L)
				break;
		}
		long depthAfter = depth(type);
		long oldestAfter = oldestMs(type);
		int ticks = currentTick;
		long delivered = depthBefore + (s.victim() ? 1 : 0) - depthAfter;
		double processingMs = processingNanos / 1e6, modeledMs = processingMs + ticks * FIXED_DELAY_MS;
		Integer victimTick = firstTick.get(prefix + "a-victim");
		int maxWait = firstTick.entrySet()
			.stream()
			.filter(e -> !e.getKey().endsWith("a-victim"))
			.mapToInt(Map.Entry::getValue)
			.max()
			.orElse(-1);
		long starved = firstTick.size() < tenants + (s.victim() ? 1 : 0)
				? tenants + (s.victim() ? 1 : 0) - firstTick.size() : 0;
		var result = new LinkedHashMap<String, Object>();
		result.put("label", label);
		result.put("scenario", s.name());
		result.put("events", s.total() + (s.victim() ? 1 : 0));
		result.put("tenants", tenants + (s.victim() ? 1 : 0));
		result.put("consumers", s.consumers());
		result.put("ticks", ticks);
		result.put("delivered", delivered);
		result.put("processingMs", Math.round(processingMs));
		result.put("modeledMs", Math.round(modeledMs));
		result.put("eventsPerSecProcessing", Math.round(delivered / (processingMs / 1000.0)));
		result.put("eventsPerSecModeled", Math.round(delivered / (modeledMs / 1000.0) * 10) / 10.0);
		result.put("queueDepthBefore", depthBefore);
		result.put("queueDepthAfter", depthAfter);
		result.put("oldestAgeBeforeMs", oldestBefore);
		result.put("oldestAgeAfterMs", oldestAfter);
		result.put("maxTenantFirstAttemptTick", maxWait);
		result.put("tenantsNeverAttempted", starved);
		if (s.victim()) {
			result.put("victimArrivalTick", victimArrivalTick);
			result.put("victimTicksToFirstAttempt", victimTick == null ? null : victimTick - victimArrivalTick);
			result.put("victimModeledMsToFirstAttempt", victimTick == null ? null
					: Math.round(modeledAt(victimTick, processingNanos, ticks) - victimArrivalModelNanos / 1e6));
		}
		jdbc.update("DELETE FROM platform_inbox WHERE consumer_id LIKE ?", "bench-" + run + "-%");
		jdbc.update("DELETE FROM platform_event WHERE event_type=?", type);
		return tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(result);
	}

	/** 用平均轮耗时估算受害者首次尝试时刻；只用于报告，不作为断言。 */
	private double modeledAt(int tick, long processingNanos, int ticks) {
		return tick * (processingNanos / 1e6 / ticks) + tick * FIXED_DELAY_MS;
	}

	EventDispatcher dispatcher(List<EventHandler> handlers) {
		return new EventDispatcher(mapper, handlers, transactions, commands);
	}

	private EventHandler handler(String consumer, String type) {
		return new EventHandler() {
			public String consumer() {
				return consumer;
			}

			public Set<String> types() {
				return Set.of(type);
			}

			public void handle(Event event) {
				firstTick.putIfAbsent(event.tenantId(), currentTick);
				handled.merge(event.tenantId(), 1, Integer::sum);
			}
		};
	}

	private Object[] row(String tenant, String type, int i) {
		String id = UUID.randomUUID().toString();
		return new Object[] { id, tenant, type, id, i + 1 };
	}

	private void insert(List<Object[]> rows) {
		for (int from = 0; from < rows.size(); from += 1000)
			jdbc.batchUpdate(
					"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status) VALUES(?,?,?,?,?,'{}','PENDING')",
					rows.subList(from, Math.min(rows.size(), from + 1000)));
	}

	private long depth(String type) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE event_type=? AND status='PENDING'",
				Long.class, type);
	}

	private long oldestMs(String type) {
		Long v = jdbc.queryForObject(
				"SELECT TIMESTAMPDIFF(MICROSECOND,MIN(available_at),CURRENT_TIMESTAMP(3))/1000 FROM platform_event WHERE event_type=? AND status='PENDING'",
				Long.class, type);
		return v == null ? 0 : v;
	}

}
