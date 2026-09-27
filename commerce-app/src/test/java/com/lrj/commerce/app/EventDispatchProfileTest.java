package com.lrj.commerce.app;

import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.EventHandler;
import com.lrj.commerce.runtime.persistence.EventMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;

/**
 * P4.9 many-small基准剖析（需显式 -Dcommerce.event-profile=true）：记录每次消费者调用的纳秒时间与租户，
 * 把相邻两次调用的间隔分为“同租户”（单事件事务成本）与“换租户”（单事件成本+租户切换：轮转簿记、租户内取数、分批发现），
 * 另测单独的租户轮转开销（无数据库的空访问）与提交往返，定位many-small比第二阶段慢约12%的来源。
 */
@SpringBootTest
@EnabledIfSystemProperty(named = "commerce.event-profile", matches = "true")
class EventDispatchProfileTest {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		Phase4Properties.register(registry);
	}

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	EventMapper mapper;

	@Autowired
	PlatformTransactionManager transactions;

	@Autowired
	Commands commands;

	@Test
	void profileManySmallAndSingleTenant() throws Exception {
		var out = new StringBuilder();
		for (var scenario : List.of(new int[] { 2500, 4 }, new int[] { 1, 10000 }, new int[] { 20, 500 }))
			for (int repeat = 0; repeat < 2; repeat++)
				out.append(profile(scenario[0], scenario[1])).append('\n');
		out.append(rotationOnly()).append('\n');
		out.append(commitRoundTrip()).append('\n');
		Files.writeString(Path.of("target", "event-profile.jsonl"), out);
		System.out.println(out);
	}

	private String profile(int tenants, int perTenant) {
		String run = UUID.randomUUID().toString().substring(0, 8), type = "prof." + run + ".v1",
				prefix = "prof-" + run + "-";
		var stamps = new ArrayList<long[]>();
		var names = new ArrayList<String>();
		EventHandler h = new EventHandler() {
			public String consumer() {
				return "prof-" + run + "-c0";
			}

			public Set<String> types() {
				return Set.of(type);
			}

			public void handle(Event e) {
				stamps.add(new long[] { System.nanoTime() });
				names.add(e.tenantId());
			}
		};
		var rows = new ArrayList<Object[]>();
		for (int t = 0; t < tenants; t++)
			for (int i = 0; i < perTenant; i++) {
				String id = UUID.randomUUID().toString();
				rows.add(new Object[] { id, prefix + String.format("%06d", t), type, id, i + 1 });
			}
		for (int from = 0; from < rows.size(); from += 1000)
			jdbc.batchUpdate(
					"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,available_at) VALUES(?,?,?,?,?,'{}','PENDING',TIMESTAMPADD(HOUR,-1,CURRENT_TIMESTAMP(3)))",
					rows.subList(from, Math.min(rows.size(), from + 1000)));
		var dispatcher = new EventDispatcher(mapper, List.of(h), transactions, commands);
		long started = System.nanoTime();
		int ticks = 0;
		while (ticks < 200
				&& jdbc.queryForObject("SELECT COUNT(*) FROM platform_event WHERE event_type=? AND status='PENDING'",
						Integer.class, type) > 0) {
			ticks++;
			dispatcher.tick();
		}
		long total = System.nanoTime() - started;
		// 相邻调用间隔：同租户=单事件事务成本；换租户=单事件成本+租户切换成本。只统计同一轮内的间隔（跨轮包含计数查询与轮间调用）。
		var same = new ArrayList<Long>();
		var cross = new ArrayList<Long>();
		for (int i = 1; i < stamps.size(); i++) {
			long gap = stamps.get(i)[0] - stamps.get(i - 1)[0];
			if (gap > 200_000_000L)
				continue;
			(names.get(i).equals(names.get(i - 1)) ? same : cross).add(gap);
		}
		jdbc.update("DELETE FROM platform_inbox WHERE consumer_id=?", "prof-" + run + "-c0");
		jdbc.update("DELETE FROM platform_event WHERE event_type=?", type);
		double sameMs = median(same) / 1e6, crossMs = median(cross) / 1e6;
		return String.format(Locale.ROOT,
				"{\"scenario\":\"%dx%d\",\"events\":%d,\"ticks\":%d,\"wallMs\":%d,\"eventsPerTickAvg\":%.1f,\"sameTenantGapMedianMs\":%.3f,\"crossTenantGapMedianMs\":%.3f,\"switchOverheadMedianMs\":%.3f,\"tenantSwitches\":%d,\"switchShareOfWall\":%.3f}",
				tenants, perTenant, stamps.size(), ticks, total / 1_000_000, stamps.size() / (double) ticks, sameMs,
				crossMs, crossMs - sameMs, cross.size(), cross.size() * (crossMs - sameMs) / (total / 1e6));
	}

	/** 纯轮转簿记：2500个租户、每访问4项、无数据库，测TenantRotation本身每次访问的开销。 */
	private String rotationOnly() {
		var tenants = new ArrayList<String>();
		for (int t = 0; t < 2500; t++)
			tenants.add(String.format("t%06d", t));
		var remaining = new HashMap<String, Integer>();
		for (var t : tenants)
			remaining.put(t, 4);
		var rotation = new TenantRotation("profile", new TenantRotation.Policy(5, 50, Duration.ofSeconds(60), 20_000));
		long started = System.nanoTime();
		int handled = rotation.run((after, limit) -> tenants.stream()
			.filter(t -> t.compareTo(after) > 0 && remaining.get(t) > 0)
			.limit(limit)
			.toList(), (tenant, r) -> {
				int n = 0;
				while (remaining.get(tenant) > 0 && n < r.limit()) {
					r.attempted();
					remaining.merge(tenant, -1, Integer::sum);
					r.succeeded();
					n++;
				}
				return n;
			});
		long nanos = System.nanoTime() - started;
		return String.format(Locale.ROOT,
				"{\"scenario\":\"rotation-only-2500x4\",\"items\":%d,\"visits\":2500,\"totalMs\":%.3f,\"perVisitMicros\":%.2f}",
				handled, nanos / 1e6, nanos / 1e3 / 2500);
	}

	/** 单语句自动提交往返（含redo刷盘），与单事件事务成本对照。 */
	private String commitRoundTrip() {
		jdbc.update("CREATE TEMPORARY TABLE IF NOT EXISTS prof_commit(id INT PRIMARY KEY AUTO_INCREMENT,v INT)");
		var samples = new ArrayList<Long>();
		for (int i = 0; i < 200; i++) {
			long s = System.nanoTime();
			jdbc.update(
					"INSERT INTO platform_audit(tenant_id,actor_id,operation,command_key) VALUES('prof','prof','commit','k')");
			samples.add(System.nanoTime() - s);
		}
		jdbc.update("DELETE FROM platform_audit WHERE tenant_id='prof' AND actor_id='prof'");
		return String.format(Locale.ROOT, "{\"scenario\":\"autocommit-insert\",\"samples\":200,\"medianMs\":%.3f}",
				median(samples) / 1e6);
	}

	private static double median(List<Long> values) {
		if (values.isEmpty())
			return 0;
		var s = new ArrayList<>(values);
		Collections.sort(s);
		return s.get(s.size() / 2);
	}

}
