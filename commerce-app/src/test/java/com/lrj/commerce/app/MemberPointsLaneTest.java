package com.lrj.commerce.app;

import com.lrj.commerce.member.points.api.MemberPointsApi;
import com.lrj.commerce.member.points.application.MemberPointsService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 积分过期车道由全局到期时间FIFO（每轮20个）改为租户轮转：一个租户批量过期的积分不再推迟其他租户。 以账本自增序号判断处理顺序，与机器速度无关。
 */
@SpringBootTest
class MemberPointsLaneTest {

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
	MemberPointsApi points;

	private String prefix;

	@BeforeEach
	void run() {
		prefix = "mp-" + UUID.randomUUID().toString().substring(0, 8) + "-";
	}

	/** 场景D（积分）：热租户100个最早过期的批次在旧的全局FIFO下要先处理5轮；现在小租户最迟一整圈内处理。 */
	@Test
	void bulkExpiryInOneTenantDoesNotDelayOtherTenants() {
		seed(prefix + "a-hot", 100, 7200);
		var small = new ArrayList<String>();
		for (int t = 0; t < 20; t++) {
			String tenant = prefix + String.format("b-%02d", t);
			seed(tenant, 1, 60);
			small.add(tenant);
		}
		for (int i = 0; i < 100 && small.stream().anyMatch(t -> remaining(t) > 0); i++)
			points.tick();
		for (var t : small)
			assertEquals(0, remaining(t), t);
		long lastSmall = jdbc.queryForObject(
				"SELECT MAX(sequence_id) FROM member_point_ledger WHERE tenant_id LIKE ? AND tenant_id<>? AND action='EXPIRE'",
				Long.class, prefix + "%", prefix + "a-hot");
		int hotBefore = jdbc.queryForObject(
				"SELECT COUNT(*) FROM member_point_ledger WHERE tenant_id=? AND action='EXPIRE' AND sequence_id<?",
				Integer.class, prefix + "a-hot", lastSmall);
		assertTrue(hotBefore <= MemberPointsService.EXPIRY.quantum(), "小租户全部处理前热租户处理了" + hotBefore + "个批次");
	}

	private void seed(String tenant, int lots, int expiredSecondsAgo) {
		jdbc.update(
				"INSERT IGNORE INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,'m1','buyer','测试会员','L1')",
				tenant);
		jdbc.update("INSERT IGNORE INTO member_point_account(tenant_id,member_id) VALUES(?,'m1')", tenant);
		var rows = new ArrayList<Object[]>();
		for (int i = 0; i < lots; i++)
			rows.add(new Object[] { tenant, "lot-" + i,
					Timestamp.from(Instant.now().minusSeconds(expiredSecondsAgo + i)) });
		jdbc.batchUpdate(
				"INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,expires_at) VALUES(?,?,'m1',1,10,10,?)",
				rows);
	}

	private long remaining(String tenant) {
		return jdbc.queryForObject("SELECT COALESCE(SUM(remaining),0) FROM member_point_lot WHERE tenant_id=?",
				Long.class, tenant);
	}

}
