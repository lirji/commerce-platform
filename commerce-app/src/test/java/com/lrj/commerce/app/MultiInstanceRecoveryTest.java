package com.lrj.commerce.app;

import com.lrj.commerce.member.api.MemberPointsApi;
import com.lrj.commerce.member.infrastructure.persistence.WorkRetryMapper;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.7/§19 多实例恢复：并发的恢复命令只有一个生效且全部留有审计（不丢请求），并发的SKIP与RETRY只产生合法的状态链，
 * 后台过期与运维手动过期并发时积分效果恰好一次，并发失败记录不丢失计数。多实例等价于多个线程各自持有连接与事务。
 */
@SpringBootTest
class MultiInstanceRecoveryTest {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		Phase4Properties.register(registry);
	}

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	RuntimeRecovery recovery;

	@Autowired
	WorkRetryMapper retries;

	@Autowired
	MemberPointsApi points;

	private String tenant;

	private Actor admin;

	private ExecutorService pool;

	@BeforeEach
	void setup() {
		tenant = "mi-" + UUID.randomUUID();
		admin = new Actor(tenant, "ops-admin", Actor.Role.ADMIN);
		pool = Executors.newFixedThreadPool(8);
	}

	@AfterEach
	void cleanup() {
		pool.shutdownNow();
		jdbc.update("DELETE FROM member_work_retry WHERE tenant_id=?", tenant);
		jdbc.update("DELETE FROM platform_event WHERE tenant_id=?", tenant);
		jdbc.update("DELETE FROM member_point_lot WHERE tenant_id=? AND lot_id='lot'", tenant);
	}

	/** 8个并发恢复请求（不同幂等键）作用于同一隔离项：恰好一个APPLIED，其余REJECTED；8条审计；恢复次数为1。 */
	@Test
	void concurrentRecoveriesOfOneItemApplyExactlyOnceAndAreAllAudited() throws Exception {
		// 批次仍到期，恢复走RETRY（放回自动处理）路径。
		jdbc.update(
				"INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,'m1','m1','并发会员','L1')",
				tenant);
		jdbc.update(
				"INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,expires_at) VALUES(?,'lot','m1',1,10,10,?)",
				tenant, Timestamp.from(Instant.now().minusSeconds(60)));
		for (int i = 0; i < RetryPolicy.POISON.budget(); i++)
			retries.failed(tenant, "points", "lot", false, Instant.now(), "UNKNOWN:X", "UNKNOWN", Instant.now(),
					RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget());
		var request = new RuntimeRecovery.Request("member.points.expiry", RecoverableWork.Action.RETRY, List.of("lot"),
				null, "并发恢复");
		var start = new CountDownLatch(1);
		var futures = new ArrayList<Future<RuntimeRecovery.Result>>();
		for (int i = 0; i < 8; i++) {
			int n = i;
			futures.add(pool.submit(() -> {
				start.await();
				return recovery.recover(admin, "k" + n, request);
			}));
		}
		start.countDown();
		int applied = 0;
		for (var f : futures)
			applied += f.get(30, TimeUnit.SECONDS).applied();
		assertEquals(1, applied);
		assertEquals(1, retries.find(tenant, "points", "lot").manualRecoveries());
		assertEquals(1,
				(int) jdbc.queryForObject(
						"SELECT COUNT(*) FROM platform_recovery WHERE tenant_id=? AND result='APPLIED'", Integer.class,
						tenant));
		assertEquals(8, (int) jdbc.queryForObject("SELECT COUNT(*) FROM platform_recovery WHERE tenant_id=?",
				Integer.class, tenant), "没有丢失的恢复请求");
		// 底层工作已不需要处理（批次已由其他路径归档）时并发恢复：同样只有一个生效（RESOLVED，删除重试行）。
		for (int i = 0; i < RetryPolicy.POISON.budget(); i++)
			retries.failed(tenant, "points", "gone", false, Instant.now(), "UNKNOWN:X", "UNKNOWN", Instant.now(),
					RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget());
		var resolve = new RuntimeRecovery.Request("member.points.expiry", RecoverableWork.Action.RETRY, List.of("gone"),
				null, "并发恢复");
		var go = new CountDownLatch(1);
		var results = new ArrayList<Future<RuntimeRecovery.Result>>();
		for (int i = 0; i < 8; i++) {
			int n = i;
			results.add(pool.submit(() -> {
				go.await();
				return recovery.recover(admin, "g" + n, resolve);
			}));
		}
		go.countDown();
		int resolved = 0;
		for (var f : results)
			resolved += f.get(30, TimeUnit.SECONDS).applied();
		assertEquals(1, resolved);
		assertNull(retries.find(tenant, "points", "gone"));
		assertEquals("RESOLVED", jdbc.queryForObject(
				"SELECT new_state FROM platform_recovery WHERE tenant_id=? AND work_id='gone' AND result='APPLIED'",
				String.class, tenant));
	}

	/** 并发SKIP与RETRY同一隔离事件：行锁串行化，审计的前后状态构成合法链，终态等于最后一个生效动作的新状态。 */
	@Test
	void concurrentSkipAndRetryProduceOnlyValidTransitions() throws Exception {
		for (int round = 0; round < 10; round++) {
			String id = UUID.randomUUID().toString();
			jdbc.update(
					"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,attempts,failure_class) VALUES(?,?,'order.created.v1',?,1,'{}','ISOLATED',5,'BUSINESS_REJECTED')",
					id, tenant, id);
			var start = new CountDownLatch(1);
			String tag = round + id.substring(0, 8);
			var skip = pool.submit(() -> {
				start.await();
				return recovery.recover(admin, "s" + tag,
						new RuntimeRecovery.Request("event", RecoverableWork.Action.SKIP, List.of(id), null, "跳过"));
			});
			var retry = pool.submit(() -> {
				start.await();
				return recovery.recover(admin, "r" + tag,
						new RuntimeRecovery.Request("event", RecoverableWork.Action.RETRY, List.of(id), null, "重试"));
			});
			start.countDown();
			skip.get(30, TimeUnit.SECONDS);
			retry.get(30, TimeUnit.SECONDS);
			var chain = jdbc.queryForList(
					"SELECT previous_state,new_state FROM platform_recovery WHERE tenant_id=? AND work_id=? AND result='APPLIED' ORDER BY id",
					tenant, id);
			assertFalse(chain.isEmpty());
			assertEquals("ISOLATED", chain.getFirst().get("previous_state"));
			for (int i = 1; i < chain.size(); i++)
				assertEquals(chain.get(i - 1).get("new_state"), chain.get(i).get("previous_state"),
						"审计前状态就是上一个生效动作的新状态");
			assertEquals(chain.getLast().get("new_state"),
					jdbc.queryForObject("SELECT status FROM platform_event WHERE event_id=?", String.class, id));
		}
	}

	/** 后台过期车道与运维手动过期并发：每个批次只过期一次（账本一条EXPIRE），余额不重复扣减。 */
	@Test
	void backgroundAndOperatorExpiryNeverDoubleExpireALot() throws Exception {
		jdbc.update(
				"INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,'m1','m1','并发会员','L1')",
				tenant);
		jdbc.update("INSERT INTO member_point_account(tenant_id,member_id) VALUES(?,'m1')", tenant);
		for (int i = 0; i < 40; i++)
			jdbc.update(
					"INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,expires_at) VALUES(?,?,'m1',1,10,10,?)",
					tenant, "lot-" + i, Timestamp.from(Instant.now().minusSeconds(60 + i)));
		var start = new CountDownLatch(1);
		var tasks = new ArrayList<Future<?>>();
		tasks.add(pool.submit(() -> {
			start.await();
			for (int i = 0; i < 200 && remaining() > 0; i++)
				points.tick();
			return null;
		}));
		for (int t = 0; t < 3; t++) {
			int n = t;
			tasks.add(pool.submit(() -> {
				start.await();
				for (int i = 0; i < 5; i++)
					points.expire(admin, "expire-" + n + "-" + i, "m1");
				return null;
			}));
		}
		start.countDown();
		for (var f : tasks)
			f.get(60, TimeUnit.SECONDS);
		assertEquals(0, remaining());
		assertEquals(40,
				(int) jdbc.queryForObject(
						"SELECT COUNT(*) FROM member_point_ledger WHERE tenant_id=? AND action='EXPIRE'", Integer.class,
						tenant),
				"每个批次恰好一条过期账本");
		assertEquals(400, (long) jdbc.queryForObject("SELECT SUM(expired) FROM member_point_lot WHERE tenant_id=?",
				Long.class, tenant));
	}

	/** 两个实例并发记录同一项的失败：原子累加，不丢失计数，隔离只写入一次。 */
	@Test
	void concurrentFailureRecordingNeverLosesCounts() throws Exception {
		var start = new CountDownLatch(1);
		var tasks = new ArrayList<Future<?>>();
		for (int t = 0; t < 2; t++)
			tasks.add(pool.submit(() -> {
				start.await();
				for (int i = 0; i < 50; i++)
					retries.failed(tenant, "cycles", "m1", true, Instant.now(), "TRANSIENT:X", "TRANSIENT",
							Instant.now(), RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget());
				return null;
			}));
		start.countDown();
		for (var f : tasks)
			f.get(60, TimeUnit.SECONDS);
		var row = retries.find(tenant, "cycles", "m1");
		assertEquals(100, row.transientAttempts());
		assertEquals(0, row.attempts());
		assertNull(row.quarantinedAt());
	}

	private long remaining() {
		return jdbc.queryForObject("SELECT COALESCE(SUM(remaining),0) FROM member_point_lot WHERE tenant_id=?",
				Long.class, tenant);
	}

}
