package com.lrj.commerce.app.runtime.scheduling;

import com.lrj.commerce.ordering.order.api.OrderApi;
import com.lrj.commerce.runtime.event.persistence.EventMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import com.lrj.commerce.runtime.api.event.EventHandler;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.event.EventDispatcher;
import com.lrj.commerce.app.runtime.monitoring.LaneMonitor;
import com.lrj.commerce.app.runtime.scheduling.EventWorker;

/**
 * 第三阶段本地基准（需显式 -Dcommerce.runtime-benchmark=true）：本机Docker MySQL测量，不代表生产容量。 1)
 * 事件并行实验：1/2/4个独立调度实例并发消费同一积压；2) 订单到期公平：旧算法（每轮4租户、单租户20单同一事务）与新轮转； 3)
 * 跨车道：真实调度拓扑下事件积压与订单到期积压对其他车道的影响，1线程（旧拓扑）对照3线程。结果写入target/runtime-benchmark.jsonl。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "commerce.runtime-benchmark", matches = "true")
class BackgroundRuntimeBenchmarkTest {

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

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EventMapper mapper;

	@Autowired
	PlatformTransactionManager transactions;

	@Autowired
	Commands commands;

	@Autowired
	DataSource dataSource;

	@Autowired
	OrderApi orders;

	private final StringBuilder out = new StringBuilder();

	private final String run = UUID.randomUUID().toString().substring(0, 8);

	@AfterEach
	void write() throws Exception {
		Files.writeString(Path.of("target", "runtime-benchmark.jsonl"), out, StandardOpenOption.CREATE,
				StandardOpenOption.APPEND);
	}

	private void emit(Map<String, Object> result) {
		String line = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(result);
		System.out.println("BENCH " + line);
		out.append(line).append('\n');
	}

	/** 场景：200租户×25事件，2个消费者；每个实例连续tick直到取空（无固定延迟），测吞吐、延迟、连接、锁等待、死锁与重复执行。 */
	@Test
	void parallelDispatchExperiment() throws Exception {
		for (int workers : List.of(1, 2, 4))
			for (int repeat = 0; repeat < 2; repeat++)
				emit(parallel(workers, 200, 25));
	}

	private Map<String, Object> parallel(int workers, int tenants, int perTenant) throws Exception {
		String type = "pbench." + run + "." + workers + "." + UUID.randomUUID().toString().substring(0, 4) + ".v1",
				prefix = "pb-" + run + "-";
		var calls = new ConcurrentHashMap<String, AtomicInteger>();
		var firstAttempt = new ConcurrentHashMap<String, Long>();
		var handlers = new ArrayList<EventHandler>();
		for (int c = 0; c < 2; c++) {
			String consumer = "pb-" + run + "-" + type.hashCode() + "-c" + c;
			handlers.add(new EventHandler() {
				public String consumer() {
					return consumer;
				}

				public Set<String> types() {
					return Set.of(type);
				}

				public void handle(Event e) {
					calls.computeIfAbsent(consumer + "/" + e.eventId(), k -> new AtomicInteger()).incrementAndGet();
					firstAttempt.putIfAbsent(e.tenantId(), System.nanoTime());
				}
			});
		}
		var rows = new ArrayList<Object[]>();
		for (int t = 0; t < tenants; t++)
			for (int i = 0; i < perTenant; i++) {
				String id = UUID.randomUUID().toString();
				rows.add(new Object[] { id, prefix + String.format("%04d", t), type, id, i + 1 });
			}
		for (int from = 0; from < rows.size(); from += 1000)
			jdbc.batchUpdate(
					"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,available_at) VALUES(?,?,?,?,?,'{}','PENDING',TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP(3)))",
					rows.subList(from, Math.min(rows.size(), from + 1000)));
		long lockWaitsBefore = status("Innodb_row_lock_waits"), lockTimeBefore = status("Innodb_row_lock_time");
		Long deadlocksBefore = deadlocks();
		var pool = ((HikariDataSource) dataSource).getHikariPoolMXBean();
		var maxActive = new AtomicInteger();
		var sampling = new AtomicBoolean(true);
		var sampler = Thread.ofPlatform().daemon().start(() -> {
			while (sampling.get()) {
				maxActive.accumulateAndGet(pool.getActiveConnections(), Math::max);
				try {
					Thread.sleep(5);
				}
				catch (InterruptedException e) {
					return;
				}
			}
		});
		var executor = Executors.newFixedThreadPool(workers);
		long start = System.nanoTime();
		try {
			var futures = new ArrayList<Future<?>>();
			for (int w = 0; w < workers; w++) {
				var dispatcher = new EventDispatcher(mapper, handlers, transactions, commands);
				futures.add(executor.submit(() -> {
					while (pending(type) > 0)
						dispatcher.tick();
				}));
			}
			for (var f : futures)
				f.get(600, TimeUnit.SECONDS);
		}
		finally {
			executor.shutdownNow();
			sampling.set(false);
			sampler.join();
		}
		double seconds = (System.nanoTime() - start) / 1e9;
		var latencies = jdbc.queryForList(
				"SELECT TIMESTAMPDIFF(MICROSECOND,e.available_at,MAX(i.processed_at))/1000 FROM platform_event e JOIN platform_inbox i ON i.event_id=e.event_id WHERE e.event_type=? GROUP BY e.event_id",
				Long.class, type);
		Collections.sort(latencies);
		long drainedMs = Math.round(seconds * 1000);
		var result = new LinkedHashMap<String, Object>();
		result.put("benchmark", "parallel-dispatch");
		result.put("workers", workers);
		result.put("tenants", tenants);
		result.put("events", rows.size());
		result.put("consumers", 2);
		result.put("durationMs", drainedMs);
		result.put("eventsPerSec", Math.round(rows.size() / seconds));
		// 延迟以本轮开始时刻为零点（积压一次性写入），P50/P95是事件在本轮内被最后一个消费者提交的时刻。
		long base = jdbc.queryForObject(
				"SELECT TIMESTAMPDIFF(MICROSECOND,MIN(available_at),CURRENT_TIMESTAMP(3))/1000 FROM platform_event WHERE event_type=?",
				Long.class, type) - drainedMs;
		result.put("p50MsFromStart", latencies.get(latencies.size() / 2) - base);
		result.put("p95MsFromStart", latencies.get(latencies.size() * 95 / 100) - base);
		result.put("lastTenantFirstAttemptMs", Math
			.round((firstAttempt.values().stream().mapToLong(Long::longValue).max().orElse(start) - start) / 1e6));
		result.put("maxActiveConnections", maxActive.get());
		result.put("rowLockWaits", status("Innodb_row_lock_waits") - lockWaitsBefore);
		result.put("rowLockTimeMs", status("Innodb_row_lock_time") - lockTimeBefore);
		Long deadlocksAfter = deadlocks();
		result.put("deadlocks",
				deadlocksBefore == null || deadlocksAfter == null ? "UNAVAILABLE" : deadlocksAfter - deadlocksBefore);
		result.put("handlerCalls", calls.values().stream().mapToInt(AtomicInteger::get).sum());
		result.put("duplicateExecutions", calls.values().stream().filter(c -> c.get() > 1).count());
		result.put("inboxRows", jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_inbox i JOIN platform_event e ON e.event_id=i.event_id WHERE e.event_type=?",
				Integer.class, type));
		jdbc.update(
				"DELETE i FROM platform_inbox i JOIN platform_event e ON e.event_id=i.event_id WHERE e.event_type=?",
				type);
		jdbc.update("DELETE FROM platform_event WHERE event_type=?", type);
		return result;
	}

	/** 场景D（订单到期）：1个热租户2000单+300个小租户各1单；旧算法用管理员批量到期命令逐租户模拟（每轮4租户、单租户20单一个事务）。 */
	@Test
	void orderExpiryFairnessOldVersusNew() {
		emit(expiry("old-4-tenants-per-tick", true));
		emit(expiry("new-tenant-rotation", false));
	}

	private Map<String, Object> expiry(String label, boolean old) {
		String prefix = "eb-" + run + "-" + (old ? "o" : "n") + "-";
		seedOrders(prefix + "a-hot", 2000, 1200);
		var small = new ArrayList<String>();
		for (int t = 0; t < 300; t++) {
			String tenant = prefix + String.format("b-%03d", t);
			seedOrders(tenant, 1, 600);
			small.add(tenant);
		}
		String cursor = "";
		int ticks = 0;
		long processing = 0;
		Integer smallDoneTick = null;
		long smallDoneModelMs = 0;
		int hotAtSmallDone = -1;
		while (ticks < 5000 && remaining(prefix) > 0) {
			ticks++;
			long start = System.nanoTime();
			if (old) {
				// 旧tick：从游标取4个有到期订单的租户，每个租户一个20单的事务。
				var tenants = jdbc.queryForList(
						"SELECT DISTINCT tenant_id FROM order_record WHERE tenant_id LIKE ? AND status='PENDING_PAYMENT' AND expires_at<=UTC_TIMESTAMP(3) AND tenant_id>? ORDER BY tenant_id LIMIT 4",
						String.class, prefix + "%", cursor);
				if (tenants.isEmpty())
					cursor = "";
				for (var tenant : tenants)
					orders.expire(new Actor(tenant, "bench", Actor.Role.ADMIN),
							"k-" + ticks + "-" + UUID.randomUUID().toString().substring(0, 6));
				if (!tenants.isEmpty())
					cursor = tenants.getLast();
			}
			else
				orders.tick();
			processing += System.nanoTime() - start;
			if (smallDoneTick == null && small.stream()
				.allMatch(t -> jdbc
					.queryForObject("SELECT status FROM order_record WHERE tenant_id=? AND order_id='o0'", String.class,
							t)
					.equals("CANCELLED"))) {
				smallDoneTick = ticks;
				smallDoneModelMs = processing / 1_000_000 + ticks * 1000L;
				hotAtSmallDone = jdbc.queryForObject(
						"SELECT COUNT(*) FROM order_record WHERE tenant_id=? AND status='CANCELLED'", Integer.class,
						prefix + "a-hot");
			}
		}
		var result = new LinkedHashMap<String, Object>();
		result.put("benchmark", "order-expiry-fairness");
		result.put("algorithm", label);
		result.put("orders", 2300);
		result.put("tenants", 301);
		result.put("ticks", ticks);
		result.put("processingMs", processing / 1_000_000);
		result.put("modeledDrainMs", processing / 1_000_000 + ticks * 1000L);
		result.put("ordersPerSecModeled", Math.round(2300 / ((processing / 1e6 + ticks * 1000.0) / 1000) * 10) / 10.0);
		result.put("ticksUntilAllSmallTenantsServed", smallDoneTick);
		result.put("modeledMsUntilAllSmallTenantsServed", smallDoneModelMs);
		result.put("hotTenantOrdersBeforeLastSmallTenant", hotAtSmallDone);
		cleanOrders(prefix);
		return result;
	}

	/**
	 * 跨车道（场景B/C）：真实调度拓扑（车道固定延迟1秒）下，事件车道有5000条历史积压、订单车道有1500单到期积压（150租户），
	 * 运行期间每2秒新到一笔渠道已成功的支付，测到达到确认的耗时与各车道开始延迟；可选支付车道每次运行多2秒（慢渠道）。 1线程是第二阶段拓扑，3线程是第三阶段默认。
	 */
	@Test
	void crossLaneUnderBacklog() throws Exception {
		for (boolean slow : List.of(false, true))
			for (int threads : List.of(1, 3))
				emit(crossLane(threads, slow));
	}

	private Map<String, Object> crossLane(int threads, boolean slowPayments) throws Exception {
		String prefix = "xb-" + run + "-" + threads + (slowPayments ? "s" : "f") + "-",
				type = "xbench." + run + "." + threads + (slowPayments ? "s" : "f") + ".v1",
				consumer = "xb-" + run + "-" + threads + (slowPayments ? "s" : "f");
		// 事件车道：本轮专属类型与每条约2毫秒的消费者，模拟真实消费者的提交成本。
		var handler = new EventHandler() {
			public String consumer() {
				return consumer;
			}

			public Set<String> types() {
				return Set.of(type);
			}

			public void handle(Event e) {
				jdbc.queryForObject("SELECT SLEEP(0.002)", Integer.class);
			}
		};
		var events = new EventDispatcher(mapper, List.of(handler), transactions, commands);
		var rows = new ArrayList<Object[]>();
		for (int t = 0; t < 500; t++)
			for (int i = 0; i < 10; i++) {
				String id = UUID.randomUUID().toString();
				rows.add(new Object[] { id, prefix + "e" + String.format("%03d", t), type, id, i + 1 });
			}
		for (int from = 0; from < rows.size(); from += 1000)
			jdbc.batchUpdate(
					"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,available_at) VALUES(?,?,?,?,?,'{}','PENDING',TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP(3)))",
					rows.subList(from, Math.min(rows.size(), from + 1000)));
		for (int t = 0; t < 150; t++)
			seedOrders(prefix + "o" + String.format("%03d", t), 10, 600);
		var monitor = new LaneMonitor();
		var scheduler = EventWorker.scheduler(threads);
		scheduler.initialize();
		var registrar = new ScheduledTaskRegistrar();
		registrar.setTaskScheduler(scheduler);
		Runnable paymentsLane = () -> {
			if (slowPayments)
				try {
					Thread.sleep(2000);
				}
				catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			payments().tick();
		};
		var lanes = new TreeMap<String, Runnable>(
				Map.of("payments", paymentsLane, "orders", orders::tick, "events", events::tick));
		for (var e : lanes.entrySet()) {
			monitor.register(e.getKey(), 1000);
			registrar.addFixedDelayTask(() -> monitor.run(e.getKey(), e.getValue()), Duration.ofSeconds(1));
		}
		var arrivals = new LinkedHashMap<String, Long>();
		var confirmed = new HashMap<String, Long>();
		long start = System.nanoTime();
		Long ordersDoneMs = null;
		try {
			registrar.afterPropertiesSet();
			while (System.nanoTime() - start < Duration.ofSeconds(40).toNanos()) {
				long elapsed = (System.nanoTime() - start) / 1_000_000;
				if (elapsed / 2000 >= arrivals.size() && arrivals.size() < 18)
					arrivals.put(newPayment(prefix + "pay" + arrivals.size()), elapsed);
				for (var tenant : arrivals.keySet())
					if (!confirmed.containsKey(tenant) && "PAID".equals(jdbc
						.queryForObject("SELECT status FROM payment_attempt WHERE tenant_id=?", String.class, tenant)))
						confirmed.put(tenant, elapsed - arrivals.get(tenant));
				if (ordersDoneMs == null && remaining(prefix + "o") == 0)
					ordersDoneMs = elapsed;
				Thread.sleep(50);
			}
		}
		finally {
			registrar.destroy();
			scheduler.shutdown();
		}
		var latencies = new ArrayList<>(confirmed.values());
		Collections.sort(latencies);
		var result = new LinkedHashMap<String, Object>();
		result.put("benchmark", "cross-lane");
		result.put("threads", threads);
		result.put("slowPaymentChannelMs", slowPayments ? 2000 : 0);
		result.put("eventBacklog", rows.size());
		result.put("expiryBacklog", 1500);
		result.put("paymentsArrived", arrivals.size());
		result.put("paymentsConfirmed", confirmed.size());
		result.put("paymentConfirmP50Ms", latencies.isEmpty() ? null : latencies.get(latencies.size() / 2));
		result.put("paymentConfirmMaxMs", latencies.isEmpty() ? null : latencies.getLast());
		result.put("expiryBacklogDrainedMs", ordersDoneMs);
		result.put("eventsDeliveredIn40s", jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_event WHERE event_type=? AND status='DELIVERED'", Integer.class, type));
		for (var s : monitor.schedules()) {
			result.put(s.lane() + "MaxStartLagMs", s.maxStartLagMillis());
			result.put(s.lane() + "MaxRunMs", s.maxDurationMillis());
			result.put(s.lane() + "Runs", s.runs());
		}
		jdbc.update("DELETE FROM platform_inbox WHERE consumer_id=?", consumer);
		jdbc.update("DELETE FROM platform_event WHERE event_type=?", type);
		for (String table : List.of("platform_event", "payment_sandbox_ledger", "payment_attempt"))
			jdbc.update("DELETE FROM " + table + " WHERE tenant_id LIKE ?", prefix + "%");
		cleanOrders(prefix);
		return result;
	}

	/** 沙箱渠道已记录成功、等待后台核对的新支付。 */
	private String newPayment(String tenant) {
		String order = "po-" + UUID.randomUUID(), payment = "pp-" + UUID.randomUUID();
		var now = Instant.now();
		jdbc.update(
				"INSERT INTO order_record(tenant_id,order_id,member_id,store_id,merchant_id,quote_id,payable,status,payment_kind,version,created_at,expires_at,items_json,address_cipher,address_key_version) VALUES(?,?,'m1','s1','mc1',?,10.00,'PAYMENT_IN_PROGRESS','CHANNEL_REQUIRED',1,?,?,'[]',X'00',1)",
				tenant, order, "q-" + order, Timestamp.from(now), Timestamp.from(now.plusSeconds(900)));
		jdbc.update(
				"INSERT INTO payment_attempt(tenant_id,payment_id,order_id,amount,currency,provider,status,version) VALUES(?,?,?,10.00,'CNY','SANDBOX','UNKNOWN',0)",
				tenant, payment, order);
		jdbc.update(
				"INSERT INTO payment_sandbox_ledger(tenant_id,payment_id,order_id,amount,currency,status,transaction_id) VALUES(?,?,?,10.00,'CNY','PAID',?)",
				tenant, payment, order, "tx-" + payment);
		return tenant;
	}

	@Autowired
	org.springframework.context.ApplicationContext context;

	private com.lrj.commerce.payment.charge.api.PaymentApi payments() {
		return context.getBean(com.lrj.commerce.payment.charge.api.PaymentApi.class);
	}

	private void seedOrders(String tenant, int count, int expiredSecondsAgo) {
		jdbc.update(
				"INSERT IGNORE INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,'m1','buyer','x','L1')",
				tenant);
		var now = Instant.now();
		var rows = new ArrayList<Object[]>();
		// 编号越大到期越早，全部已到期。
		for (int i = 0; i < count; i++)
			rows.add(new Object[] { tenant, "o" + i, "q" + i,
					Timestamp.from(now.minusSeconds(expiredSecondsAgo + 900 + i)),
					Timestamp.from(now.minusSeconds(expiredSecondsAgo + i)) });
		jdbc.batchUpdate(
				"INSERT INTO order_record(tenant_id,order_id,member_id,store_id,merchant_id,quote_id,payable,status,payment_kind,version,created_at,expires_at,items_json,address_cipher,address_key_version) VALUES(?,?,'m1','s1','mc1',?,10.00,'PENDING_PAYMENT','CHANNEL_REQUIRED',0,?,?,'[]',X'00',1)",
				rows);
	}

	private long remaining(String prefix) {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM order_record WHERE tenant_id LIKE ? AND status='PENDING_PAYMENT'", Long.class,
				prefix + "%");
	}

	private void cleanOrders(String prefix) {
		jdbc.update("DELETE FROM platform_event WHERE tenant_id LIKE ?", prefix + "%");
		jdbc.update("DELETE FROM order_record WHERE tenant_id LIKE ?", prefix + "%");
		jdbc.update("DELETE FROM platform_audit WHERE tenant_id LIKE ?", prefix + "%");
		jdbc.update("DELETE FROM platform_command WHERE tenant_id LIKE ?", prefix + "%");
	}

	private long pending(String type) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE event_type=? AND status='PENDING'",
				Long.class, type);
	}

	private long status(String name) {
		return Long.parseLong(jdbc.queryForMap("SHOW GLOBAL STATUS LIKE '" + name + "'").get("Value").toString());
	}

	private Long deadlocks() {
		try {
			return jdbc.queryForObject(
					"SELECT COUNT FROM information_schema.INNODB_METRICS WHERE NAME='lock_deadlocks'", Long.class);
		}
		catch (RuntimeException unavailable) {
			return null;
		}
	}

}
