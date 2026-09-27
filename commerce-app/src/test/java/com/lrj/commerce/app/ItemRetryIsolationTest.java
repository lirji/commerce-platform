package com.lrj.commerce.app;

import com.lrj.commerce.member.cycle.api.MemberCycleApi;
import com.lrj.commerce.member.points.api.MemberPointsApi;
import com.lrj.commerce.member.cycle.application.MemberCycleService;
import com.lrj.commerce.member.points.application.MemberPointsService;
import com.lrj.commerce.member.recovery.infrastructure.persistence.WorkRetryMapper;
import com.lrj.commerce.runtime.work.RetryPolicy;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.1 逐项重试隔离（积分过期、周期考核）：一个持续失败的项只按自己的退避重试，不再反复占用该租户的处理机会；
 * 其他项、其他租户照常推进；瞬时失败不消耗毒工作预算；隔离项只能由恢复命令放回。
 * 连接初始化把锁等待缩短为1秒，用真实行锁制造瞬时失败（CONCURRENCY_RETRYABLE）。
 */
@SpringBootTest
class ItemRetryIsolationTest {

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
		registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET SESSION innodb_lock_wait_timeout=1");
	}

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MemberPointsApi points;

	@Autowired
	MemberCycleApi cycles;

	@Autowired
	WorkRetryMapper retries;

	private String prefix;

	private final List<String> tenants = new ArrayList<>();

	@BeforeEach
	void prefix() {
		prefix = "ir-" + UUID.randomUUID().toString().substring(0, 8) + "-";
	}

	@AfterEach
	void cleanup() {
		for (var t : tenants) {
			jdbc.update("DELETE FROM member_work_retry WHERE tenant_id=?", t);
			jdbc.update("DELETE FROM member_point_lot WHERE tenant_id=? AND member_id LIKE 'ghost%'", t);
		}
	}

	private String tenant(String name) {
		String t = prefix + name;
		tenants.add(t);
		return t;
	}

	// ---------------- 积分过期 ----------------

	/** 场景1：同租户一个坏批次（最早到期）与健康批次：健康批次照常过期；坏批次按退避重试，5次非瞬时失败后隔离，之后不再被选中。 */
	@Test
	void badLotBacksOffAloneAndIsQuarantinedAfterFiveFailures() {
		String a = tenant("a");
		healthy(a, "m1", 3, 60);
		ghostLot(a, "bad", 3600);
		tickPointsUntil(() -> remaining(a, "m1") == 0);
		var row = retry(a, "points", "bad");
		assertEquals(1, row.attempts(), "第一次失败计入毒工作次数");
		assertEquals(0, row.transientAttempts());
		assertEquals("BUSINESS_REJECTED", row.failureClass());
		assertTrue(row.lastError().startsWith("BUSINESS_REJECTED:DomainException/NOT_FOUND"), row.lastError());
		assertNotNull(row.firstFailedAt());
		assertNull(row.quarantinedAt());
		assertTrue(row.retryAt().isAfter(Instant.now()), "失败后进入退避");
		// 退避期间反复执行车道也不会再尝试该批次。
		for (int i = 0; i < 5; i++)
			points.tick();
		assertEquals(1, retry(a, "points", "bad").attempts(), "退避中的批次不被选中");
		for (int failure = 2; failure <= RetryPolicy.POISON.budget(); failure++) {
			elapse(a, "points", "bad");
			int expected = failure;
			tickPointsUntil(() -> retry(a, "points", "bad").attempts() == expected);
		}
		row = retry(a, "points", "bad");
		assertNotNull(row.quarantinedAt(), "第5次非瞬时失败后隔离");
		assertEquals(row.firstFailedAt(), retry(a, "points", "bad").firstFailedAt());
		// 隔离项即使下次时间已过也不会自动回到执行（R5）。
		elapse(a, "points", "bad");
		for (int i = 0; i < 5; i++)
			points.tick();
		assertEquals(RetryPolicy.POISON.budget(), retry(a, "points", "bad").attempts());
		assertEquals(10, lotRemaining(a, "bad"), "坏批次保持原状，等待人工恢复");
	}

	/** P0：同租户最早到期的坏批次多于一个访问配额（20）时，旧逻辑每次访问都只取到坏批次，健康批次永远轮不到；现在坏批次进入退避后健康批次在随后几轮内完成。 */
	@Test
	void moreBadLotsThanOneQuantumNoLongerBlockTheTenant() {
		String a = tenant("a");
		int bad = MemberPointsService.EXPIRY.quantum() + 5;
		for (int i = 0; i < bad; i++)
			ghostLot(a, String.format("bad-%02d", i), 7200 + i);
		healthy(a, "m1", 5, 60);
		tickPointsUntil(() -> remaining(a, "m1") == 0);
		assertEquals(bad,
				(int) jdbc.queryForObject(
						"SELECT COUNT(*) FROM member_work_retry WHERE tenant_id=? AND lane='points' AND attempts=1",
						Integer.class, a),
				"每个坏批次只失败一次即退避");
	}

	/** 场景2：租户A持续失败，租户B健康积压：B照常清空，A的坏批次在清空B期间最多被尝试一次（退避2秒以上）。 */
	@Test
	void permanentlyFailingTenantDoesNotDelayAnotherTenant() {
		String a = tenant("a"), b = tenant("b");
		ghostLot(a, "bad", 3600);
		healthy(b, "m1", 30, 60);
		tickPointsUntil(() -> remaining(b, "m1") == 0);
		assertTrue(retry(a, "points", "bad").attempts() <= 2, "清空B期间A的坏批次不被反复尝试");
	}

	/** 场景3：同租户多个独立坏批次与健康批次交错：健康批次全部完成，每个坏批次各自有重试状态。 */
	@Test
	void independentFailingLotsKeepTheirOwnRetryState() {
		String a = tenant("a");
		for (int i = 0; i < 3; i++) {
			ghostLot(a, "bad-" + i, 600 - i * 100);
		}
		healthy(a, "m1", 6, 550);
		tickPointsUntil(() -> remaining(a, "m1") == 0);
		for (int i = 0; i < 3; i++) {
			var r = retry(a, "points", "bad-" + i);
			assertEquals(1, r.attempts());
			assertNull(r.quarantinedAt());
		}
		// 修复其中一个（补上会员）并让它到期：成功后重试行删除，其余两个不受影响。
		jdbc.update(
				"INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,'ghost-fixed','ghost-fixed','修复会员','L1')",
				a);
		jdbc.update("UPDATE member_point_lot SET member_id='ghost-fixed' WHERE tenant_id=? AND lot_id='bad-1'", a);
		elapse(a, "points", "bad-1");
		tickPointsUntil(() -> lotRemaining(a, "bad-1") == 0);
		assertNull(retries.find(a, "points", "bad-1"), "成功即删除重试行");
		assertNotNull(retries.find(a, "points", "bad-0"));
		assertNotNull(retries.find(a, "points", "bad-2"));
	}

	/** 场景4：瞬时失败（行锁等待超时）只累加瞬时次数，不计入毒工作预算；依赖恢复后成功并清除重试行。 */
	@Test
	void transientLockTimeoutDoesNotConsumeThePoisonBudget() throws Exception {
		String a = tenant("a");
		healthy(a, "m1", 1, 60);
		try (var holder = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
			holder.setAutoCommit(false);
			try (var lock = holder.prepareStatement(
					"SELECT member_id FROM member_record WHERE tenant_id=? AND member_id='m1' FOR UPDATE")) {
				lock.setString(1, a);
				lock.executeQuery();
			}
			tickPointsUntil(() -> retries.find(a, "points", "lot-0") != null);
			holder.rollback();
		}
		var row = retry(a, "points", "lot-0");
		assertEquals(0, row.attempts(), "瞬时失败不计入毒工作次数");
		assertEquals(1, row.transientAttempts());
		assertEquals("CONCURRENCY_RETRYABLE", row.failureClass());
		assertNull(row.quarantinedAt());
		elapse(a, "points", "lot-0");
		tickPointsUntil(() -> remaining(a, "m1") == 0);
		assertNull(retries.find(a, "points", "lot-0"), "恢复后成功即清除重试状态");
	}

	/** 预算算术（与车道相同的原子累加SQL）：瞬时失败超过毒预算也不隔离，毒预算只按非瞬时失败计数；瞬时预算用尽才隔离。 */
	@Test
	void retryBudgetsAreCountedSeparatelyAndAtomically() {
		String a = tenant("a");
		var now = Instant.now();
		for (int i = 0; i < RetryPolicy.POISON.budget() + 1; i++)
			retries.failed(a, "points", "x", true, now, "DEPENDENCY_UNAVAILABLE:X", "DEPENDENCY_UNAVAILABLE", now,
					RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget());
		for (int i = 0; i < RetryPolicy.POISON.budget() - 1; i++)
			retries.failed(a, "points", "x", false, now, "UNKNOWN:X", "UNKNOWN", now, RetryPolicy.POISON.budget(),
					RetryPolicy.TRANSIENT.budget());
		var row = retries.find(a, "points", "x");
		assertEquals(RetryPolicy.POISON.budget() + 1, row.transientAttempts());
		assertEquals(RetryPolicy.POISON.budget() - 1, row.attempts());
		assertNull(row.quarantinedAt());
		retries.failed(a, "points", "x", false, now, "UNKNOWN:X", "UNKNOWN", now, RetryPolicy.POISON.budget(),
				RetryPolicy.TRANSIENT.budget());
		assertNotNull(retries.find(a, "points", "x").quarantinedAt(), "第5次非瞬时失败隔离");
		for (int i = 0; i < RetryPolicy.TRANSIENT.budget() - 1; i++)
			retries.failed(a, "points", "y", true, now, "TRANSIENT:X", "TRANSIENT", now, RetryPolicy.POISON.budget(),
					RetryPolicy.TRANSIENT.budget());
		assertNull(retries.find(a, "points", "y").quarantinedAt());
		retries.failed(a, "points", "y", true, now, "TRANSIENT:X", "TRANSIENT", now, RetryPolicy.POISON.budget(),
				RetryPolicy.TRANSIENT.budget());
		assertNotNull(retries.find(a, "points", "y").quarantinedAt(), "瞬时预算（约27小时）用尽才隔离");
	}

	// ---------------- 周期考核 ----------------

	/** 周期场景：同租户坏会员（贡献求和溢出）多于一个访问配额，健康会员仍在随后几轮内完成考核；坏会员各自退避。 */
	@Test
	void badMembersDoNotBlockAssessmentOfHealthyMembers() {
		String a = tenant("a");
		policy(a, 30);
		int bad = MemberCycleService.ASSESSMENT.quantum() + 2;
		for (int i = 0; i < bad; i++) {
			String m = String.format("bad-%02d", i);
			member(a, m);
			overflow(a, m);
		}
		for (int i = 0; i < 3; i++)
			member(a, "ok-" + i);
		// 坏会员更早到期（纪元值相同时按会员标识，bad-排在ok-之前）。
		tickCyclesUntil(() -> assessed(a, "ok-") == 3);
		assertEquals(bad, (int) jdbc.queryForObject(
				"SELECT COUNT(*) FROM member_work_retry WHERE tenant_id=? AND lane='cycles' AND attempts>=1 AND quarantined_at IS NULL",
				Integer.class, a));
		var row = retry(a, "cycles", "bad-00");
		assertFalse(row.failureClass().isEmpty());
		assertTrue(row.retryAt().isAfter(Instant.now().minusSeconds(1)));
	}

	/** 周期场景：一个租户的全部会员都失败（策略周期为0的历史坏数据），另一个租户照常考核；坏租户的会员进入退避而不是每轮重试。 */
	@Test
	void poisonCycleTenantDoesNotDelayOtherTenants() {
		String bad = tenant("a-bad"), good = tenant("b-good");
		policy(bad, 0);
		member(bad, "m1");
		policy(good, 30);
		member(good, "m1");
		tickCyclesUntil(() -> assessed(good, "m1") == 1);
		var row = retry(bad, "cycles", "m1");
		assertEquals(1, row.attempts());
		for (int i = 0; i < 5; i++)
			cycles.tick();
		assertEquals(1, retry(bad, "cycles", "m1").attempts(), "退避中的会员不被选中");
	}

	// ---------------- helpers ----------------
	private void healthy(String tenant, String member, int lots, int expiredSecondsAgo) {
		jdbc.update(
				"INSERT IGNORE INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,?,?,'测试会员','L1')",
				tenant, member, member);
		jdbc.update("INSERT IGNORE INTO member_point_account(tenant_id,member_id) VALUES(?,?)", tenant, member);
		for (int i = 0; i < lots; i++)
			jdbc.update(
					"INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,expires_at) VALUES(?,?,?,1,10,10,?)",
					tenant, "lot-" + i, member, Timestamp.from(Instant.now().minusSeconds(expiredSecondsAgo + i)));
	}

	/** 坏数据：批次指向不存在的会员（绕过外键写入），过期时锁会员得到NOT_FOUND（BUSINESS_REJECTED）。 */
	private void ghostLot(String tenant, String lot, int expiredSecondsAgo) {
		jdbc.execute((ConnectionCallback<Void>) c -> {
			try (var off = c.createStatement()) {
				off.execute("SET SESSION foreign_key_checks=0");
			}
			try (var ps = c.prepareStatement(
					"INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,expires_at) VALUES(?,?,'ghost',1,10,10,?)")) {
				ps.setString(1, tenant);
				ps.setString(2, lot);
				ps.setTimestamp(3, Timestamp.from(Instant.now().minusSeconds(expiredSecondsAgo)));
				ps.executeUpdate();
			}
			finally {
				try (var on = c.createStatement()) {
					on.execute("SET SESSION foreign_key_checks=1");
				}
			}
			return null;
		});
	}

	private void policy(String tenant, int periodDays) {
		jdbc.update(
				"INSERT INTO member_cycle_policy(tenant_id,version,effective_from,policy_json) VALUES(?,1,'2000-01-01 00:00:00.000',?)",
				tenant, "{\"version\":1,\"effectiveFrom\":\"2000-01-01T00:00:00Z\",\"periodDays\":" + periodDays
						+ ",\"levels\":[{\"code\":\"L1\",\"minimumGrowth\":0}]}");
	}

	private void member(String tenant, String member) {
		jdbc.update(
				"INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,?,?,'周期会员','L1')",
				tenant, member, member);
	}

	/** 坏数据：同一周期两笔极大贡献，求和超出long范围，考核时读取失败（非瞬时）。 */
	private void overflow(String tenant, String member) {
		for (int i = 0; i < 2; i++)
			jdbc.update(
					"INSERT INTO member_cycle_contribution(tenant_id,source_id,member_id,occurred_at,contribution) VALUES(?,?,?,?,9000000000000000000)",
					tenant, member + "-src-" + i, member, Timestamp.from(Instant.now().minusSeconds(60)));
	}

	private int assessed(String tenant, String memberPrefix) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM member_cycle_account WHERE tenant_id=? AND member_id LIKE ?",
				Integer.class, tenant, memberPrefix + "%");
	}

	private long remaining(String tenant, String member) {
		return jdbc.queryForObject(
				"SELECT COALESCE(SUM(remaining),0) FROM member_point_lot WHERE tenant_id=? AND member_id=?", Long.class,
				tenant, member);
	}

	private long lotRemaining(String tenant, String lot) {
		return jdbc.queryForObject("SELECT remaining FROM member_point_lot WHERE tenant_id=? AND lot_id=?", Long.class,
				tenant, lot);
	}

	private WorkRetryMapper.Row retry(String tenant, String lane, String item) {
		return Objects.requireNonNull(retries.find(tenant, lane, item), "缺少重试行 " + item);
	}

	/** 模拟退避时间已过。 */
	private void elapse(String tenant, String lane, String item) {
		jdbc.update("UPDATE member_work_retry SET retry_at=? WHERE tenant_id=? AND lane=? AND item_id=?",
				Timestamp.from(Instant.now().minusSeconds(1)), tenant, lane, item);
	}

	/** 共享测试库中其他租户也有到期工作：按轮转执行直到条件成立（有上限）。 */
	private void tickPointsUntil(java.util.function.BooleanSupplier done) {
		for (int i = 0; i < 300 && !done.getAsBoolean(); i++)
			points.tick();
		assertTrue(done.getAsBoolean(), "积分车道未在上限内达到预期");
	}

	private void tickCyclesUntil(java.util.function.BooleanSupplier done) {
		for (int i = 0; i < 300 && !done.getAsBoolean(); i++)
			cycles.tick();
		assertTrue(done.getAsBoolean(), "周期车道未在上限内达到预期");
	}

}
