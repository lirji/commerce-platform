package com.lrj.commerce.app;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.member.api.MemberPointsApi;
import com.lrj.commerce.member.infrastructure.persistence.WorkRetryMapper;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.3 隔离恢复：查看 → 按分类筛选 → 指定标识决定 → 执行 → 审计。恢复只作用于本租户、只接受显式标识，
 * 保留失败证据，同一幂等键不重复执行也不重复审计；所有恢复入口（含旧的单项重试接口）都写恢复审计。 同时覆盖第33至35节的授权矩阵与401/403/404隐藏约定。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuntimeRecoveryTest {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		Phase4Properties.register(registry);
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	WorkRetryMapper retries;

	@Autowired
	MemberPointsApi points;

	@Autowired
	RuntimeRecovery recovery;

	private Phase4Http http;

	private String tenant, admin;

	@BeforeEach
	void setup() {
		http = new Phase4Http(jdbc, port);
		tenant = "rr-" + UUID.randomUUID();
		admin = http.token(tenant, "ops-admin", "ADMIN");
	}

	@AfterEach
	void cleanup() {
		jdbc.update("DELETE FROM member_work_retry WHERE tenant_id LIKE 'rr-%'");
		jdbc.update("DELETE FROM member_point_lot WHERE tenant_id LIKE 'rr-%' AND member_id='ghost'");
	}

	@Test
	void quarantinedLotRecoveryIsExplicitAuditedIdempotentAndPreservesEvidence() throws Exception {
		quarantinedLot(tenant, "bad");
		var before = retries.find(tenant, "points", "bad");
		// 查看与按分类筛选
		var stopped = http.ok("GET", "/v1/admin/runtime/stopped?workType=member.points.expiry", admin, null, null);
		assertEquals(1, stopped.size());
		assertEquals("bad", stopped.get(0).path("workId").asString());
		assertEquals("BUSINESS_REJECTED", stopped.get(0).path("failureClass").asString());
		assertEquals(5, stopped.get(0).path("attempts").asInt());
		assertEquals(0, http
			.ok("GET", "/v1/admin/runtime/stopped?workType=member.points.expiry&failureClass=DEPENDENCY_UNAVAILABLE",
					admin, null, null)
			.size());
		assertEquals(400, http
			.call("GET", "/v1/admin/runtime/stopped?workType=member.points.expiry&failureClass=NOPE", admin, null, null)
			.status());
		// 执行：带期望分类护栏与原因
		var request = Map.of("workType", "member.points.expiry", "action", "RETRY", "workIds", List.of("bad"),
				"expectedFailureClass", "BUSINESS_REJECTED", "reason", "补录缺失会员后重试");
		var result = http.ok("POST", "/v1/admin/runtime/recoveries", admin, "recover-1", request);
		assertEquals(1, result.path("applied").asInt());
		var outcome = result.path("outcomes").get(0);
		assertEquals("QUARANTINED", outcome.path("previousState").asString());
		assertEquals("READY", outcome.path("newState").asString());
		var after = retries.find(tenant, "points", "bad");
		assertNull(after.quarantinedAt());
		assertEquals(0, after.attempts());
		assertEquals(1, after.manualRecoveries());
		assertEquals(before.failureClass(), after.failureClass(), "恢复保留失败分类");
		assertEquals(before.lastError(), after.lastError());
		assertEquals(before.firstFailedAt(), after.firstFailedAt(), "恢复保留首次失败时间");
		// 审计：谁、何时、哪项、前后状态、原因、结果
		var audit = jdbc.queryForMap("SELECT * FROM platform_recovery WHERE tenant_id=? AND work_id='bad'", tenant);
		assertEquals("ops-admin", audit.get("actor_id"));
		assertEquals("runtime.recovery", audit.get("operation"));
		assertEquals("recover-1", audit.get("command_key"));
		assertEquals("member.points.expiry", audit.get("work_type"));
		assertEquals("RETRY", audit.get("action"));
		assertEquals("QUARANTINED", audit.get("previous_state"));
		assertEquals("READY", audit.get("new_state"));
		assertEquals("BUSINESS_REJECTED", audit.get("failure_class"));
		assertEquals("补录缺失会员后重试", audit.get("reason"));
		assertEquals("APPLIED", audit.get("result"));
		assertNotNull(audit.get("created_at"));
		// 同一幂等键重放：原结果，不重复执行也不重复审计
		assertEquals(result, http.ok("POST", "/v1/admin/runtime/recoveries", admin, "recover-1", request));
		assertEquals(1, audits(tenant));
		assertEquals(409,
				http.call("POST", "/v1/admin/runtime/recoveries", admin, "recover-1", Map.of("workType",
						"member.points.expiry", "action", "RETRY", "workIds", List.of("bad"), "reason", "不同内容"))
					.status(),
				"相同幂等键不同内容");
		// 修复数据后由车道完成：副作用只发生一次（批次归零、账本一条EXPIRE），成功即删除重试行
		jdbc.update(
				"INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,'ghost','ghost','补录会员','L1')",
				tenant);
		jdbc.update("INSERT INTO member_point_account(tenant_id,member_id) VALUES(?,'ghost')", tenant);
		for (int i = 0; i < 300 && remaining(tenant, "bad") > 0; i++)
			points.tick();
		assertEquals(0, remaining(tenant, "bad"));
		assertNull(retries.find(tenant, "points", "bad"));
		assertEquals(1, (int) jdbc.queryForObject(
				"SELECT COUNT(*) FROM member_point_ledger WHERE tenant_id=? AND action='EXPIRE' AND source_id='bad'",
				Integer.class, tenant));
		// 已不再停止：再次恢复被拒绝，拒绝也写审计
		var again = http.ok("POST", "/v1/admin/runtime/recoveries", admin, "recover-2", Map.of("workType",
				"member.points.expiry", "action", "RETRY", "workIds", List.of("bad"), "reason", "再次尝试"));
		assertEquals(0, again.path("applied").asInt());
		assertEquals("NOT_STOPPED", again.path("outcomes").get(0).path("rejection").asString());
		assertEquals(1, (int) jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_recovery WHERE tenant_id=? AND result='REJECTED' AND rejection='NOT_STOPPED'",
				Integer.class, tenant));
		assertEquals(2, http
			.ok("GET", "/v1/admin/runtime/recoveries?workType=member.points.expiry&workId=bad", admin, null, null)
			.size(), "审计历史可查");
	}

	@Test
	void guardsRejectMismatchedUnboundedOrUnsupportedRequests() throws Exception {
		quarantinedLot(tenant, "bad");
		var mismatch = http.ok("POST", "/v1/admin/runtime/recoveries", admin, "g1",
				Map.of("workType", "member.points.expiry", "action", "RETRY", "workIds", List.of("bad"),
						"expectedFailureClass", "DEPENDENCY_UNAVAILABLE", "reason", "分类不符"));
		assertEquals("FAILURE_CLASS_MISMATCH", mismatch.path("outcomes").get(0).path("rejection").asString());
		assertNotNull(retries.find(tenant, "points", "bad").quarantinedAt(), "分类不符时状态不变");
		var tooMany = new ArrayList<String>();
		for (int i = 0; i <= RuntimeRecovery.MAX_ITEMS; i++)
			tooMany.add("id-" + i);
		assertEquals(400,
				http.call("POST", "/v1/admin/runtime/recoveries", admin, "g2", Map.of("workType",
						"member.points.expiry", "action", "RETRY", "workIds", tooMany, "reason", "过多"))
					.status(),
				"不提供无界恢复");
		assertEquals(
				400, http
					.call("POST", "/v1/admin/runtime/recoveries", admin, "g3",
							Map.of("workType", "member.points.expiry", "action", "RETRY", "workIds", List.of(),
									"reason", "空"))
					.status());
		assertEquals(
				400, http
					.call("POST", "/v1/admin/runtime/recoveries", admin, "g4",
							Map.of("workType", "member.points.expiry", "action", "RETRY", "workIds",
									List.of("bad", "bad"), "reason", "重复"))
					.status());
		assertEquals(400,
				http.call("POST", "/v1/admin/runtime/recoveries", admin, "g5", Map.of("workType",
						"member.points.expiry", "action", "RETRY", "workIds", List.of("bad"), "reason", " "))
					.status(),
				"必须填写原因");
		assertEquals(400,
				http.call("POST", "/v1/admin/runtime/recoveries", admin, "g6", Map.of("workType",
						"member.points.expiry", "action", "SKIP", "workIds", List.of("bad"), "reason", "跳过"))
					.status(),
				"积分过期不支持跳过");
		assertEquals(404,
				http.call("POST", "/v1/admin/runtime/recoveries", admin, "g7",
						Map.of("workType", "nope", "action", "RETRY", "workIds", List.of("bad"), "reason", "未知"))
					.status());
		var types = http.ok("GET", "/v1/admin/runtime/work-types", admin, null, null).toString();
		for (var t : List.of("event", "order.expiry", "member.points.expiry", "member.cycle.assessment"))
			assertTrue(types.contains(t), types);
	}

	@Test
	void recoveryIsScopedToTheCallersTenant() throws Exception {
		quarantinedLot(tenant, "bad");
		String other = "rr-" + UUID.randomUUID();
		String otherAdmin = http.token(other, "other-admin", "ADMIN");
		assertEquals(0,
				http.ok("GET", "/v1/admin/runtime/stopped?workType=member.points.expiry", otherAdmin, null, null)
					.size(),
				"其他租户看不到");
		var result = http.ok("POST", "/v1/admin/runtime/recoveries", otherAdmin, "x1", Map.of("workType",
				"member.points.expiry", "action", "RETRY", "workIds", List.of("bad"), "reason", "越权尝试"));
		assertEquals("NOT_STOPPED", result.path("outcomes").get(0).path("rejection").asString());
		assertNotNull(retries.find(tenant, "points", "bad").quarantinedAt(), "其他租户的恢复不影响本租户");
		assertEquals(0, audits(tenant));
		assertEquals(1, audits(other), "拒绝记在请求者租户");
	}

	@Test
	void isolatedEventsCanBeSkippedOrRetriedAndLegacyEndpointAudits() throws Exception {
		String skip = isolatedEvent(tenant, "order.created.v1"), retry = isolatedEvent(tenant, "order.created.v1"),
				unrouted = isolatedEvent(tenant, "legacy.unrouted.v1");
		var listed = http
			.ok("GET", "/v1/admin/runtime/stopped?workType=event&failureClass=BUSINESS_REJECTED", admin, null, null)
			.toString();
		assertTrue(listed.contains(skip) && listed.contains(retry), listed);
		var skipped = http.ok("POST", "/v1/admin/runtime/recoveries", admin, "e1",
				Map.of("workType", "event", "action", "SKIP", "workIds", List.of(skip), "reason", "业务确认不再处理"));
		assertEquals("SKIPPED", skipped.path("outcomes").get(0).path("newState").asString());
		assertEquals("OPERATOR_SKIPPED",
				jdbc.queryForObject("SELECT skip_reason FROM platform_event WHERE event_id=?", String.class, skip));
		assertEquals("BUSINESS_REJECTED",
				jdbc.queryForObject("SELECT failure_class FROM platform_event WHERE event_id=?", String.class, skip),
				"跳过保留失败证据");
		var retried = http.ok("POST", "/v1/admin/runtime/recoveries", admin, "e2",
				Map.of("workType", "event", "action", "RETRY", "workIds", List.of(retry, unrouted), "reason", "修复后重放"));
		assertEquals(1, retried.path("applied").asInt());
		assertEquals("PENDING",
				jdbc.queryForObject("SELECT status FROM platform_event WHERE event_id=?", String.class, retry));
		assertEquals("STATE_CHANGED", retried.path("outcomes").get(1).path("rejection").asString(), "没有消费者的类型拒绝重放");
		assertEquals("ISOLATED",
				jdbc.queryForObject("SELECT status FROM platform_event WHERE event_id=?", String.class, unrouted));
		// 旧的单项重试接口同样写恢复审计（原因为空）。
		String legacy = isolatedEvent(tenant, "order.created.v1");
		assertEquals(1, http.ok("POST", "/v1/admin/events/" + legacy + "/retry", admin, "legacy-1", null).asInt());
		var audit = jdbc.queryForMap("SELECT * FROM platform_recovery WHERE tenant_id=? AND work_id=?", tenant, legacy);
		assertEquals("event.retry", audit.get("operation"));
		assertEquals("ISOLATED", audit.get("previous_state"));
		assertEquals("PENDING", audit.get("new_state"));
		assertEquals("APPLIED", audit.get("result"));
		assertNull(audit.get("reason"));
	}

	/**
	 * 第33至35节：匿名401；会员、门店运营、平台运维访问租户恢复与重放接口403；租户管理员200；管理员访问命名空间内不存在路径404；管理员访问平台路径403。
	 */
	@Test
	void recoveryAndReplayAuthorizationMatrix() throws Exception {
		String member = http.token(tenant, "buyer", "MEMBER"), operator = http.token(tenant, "store-op", "OPERATOR"),
				platform = http.token("platform", "platform-op", "PLATFORM_OPERATOR");
		var endpoints = List.of(new String[] { "GET", "/v1/admin/runtime/stopped?workType=event" },
				new String[] { "POST", "/v1/admin/runtime/recoveries" },
				new String[] { "GET", "/v1/admin/runtime/recoveries" },
				new String[] { "POST", "/v1/admin/runtime/replay/dry-run" },
				new String[] { "POST", "/v1/admin/runtime/replays" },
				new String[] { "GET", "/v1/admin/runtime/replays" },
				new String[] { "GET", "/v1/admin/runtime/replay/classifications" });
		for (var e : endpoints) {
			assertEquals(401, http.call(e[0], e[1], null, "k", e[0].equals("POST") ? Map.of() : null).status(),
					"匿名 " + e[1]);
			for (var token : List.of(member, operator, platform))
				assertEquals(403, http.call(e[0], e[1], token, "k", e[0].equals("POST") ? Map.of() : null).status(),
						"非租户管理员 " + e[1]);
		}
		assertEquals(200, http.call("GET", "/v1/admin/runtime/stopped?workType=event", admin, null, null).status());
		assertEquals(200, http.call("GET", "/v1/admin/runtime/replay/classifications", admin, null, null).status());
		assertEquals(404, http.call("GET", "/v1/admin/runtime/nope", admin, null, null).status(), "可访问区域内的不存在路径");
		assertEquals(403, http.call("GET", "/v1/platform/runtime", admin, null, null).status(), "租户管理员没有平台能力");
		var view = http.ok("GET", "/v1/platform/runtime", platform, null, null);
		assertTrue(view.has("replay") && view.has("retention"), view.toString());
		assertFalse(view.toString().contains(tenant), "平台视图不含租户标识");
		// 用例层第二道校验：绕过路由直接调用也被拒绝。
		var direct = assertThrows(DomainException.class,
				() -> recovery.recover(new Actor(tenant, "store-op", Actor.Role.OPERATOR), "k",
						new RuntimeRecovery.Request("event", RecoverableWork.Action.RETRY, List.of("x"), null, "r")));
		assertEquals(DomainException.Code.FORBIDDEN, direct.code());
		assertThrows(DomainException.class, () -> recovery
			.stopped(new Actor("platform", "p", Actor.Role.PLATFORM_OPERATOR), "event", null, "", 10));
	}

	// ---------------- helpers ----------------
	/** 通过与车道相同的原子SQL记录5次非瞬时失败，得到带完整证据的隔离批次。 */
	private void quarantinedLot(String tenant, String lot) {
		jdbc.execute((ConnectionCallback<Void>) c -> {
			try (var off = c.createStatement()) {
				off.execute("SET SESSION foreign_key_checks=0");
			}
			try (var ps = c.prepareStatement(
					"INSERT INTO member_point_lot(tenant_id,lot_id,member_id,policy_version,credited,remaining,expires_at) VALUES(?,?,'ghost',1,10,10,?)")) {
				ps.setString(1, tenant);
				ps.setString(2, lot);
				ps.setTimestamp(3, Timestamp.from(Instant.now().minusSeconds(3600)));
				ps.executeUpdate();
			}
			finally {
				try (var on = c.createStatement()) {
					on.execute("SET SESSION foreign_key_checks=1");
				}
			}
			return null;
		});
		var first = Instant.now().minusSeconds(120);
		for (int i = 0; i < RetryPolicy.POISON.budget(); i++)
			retries.failed(tenant, "points", lot, false, Instant.now(), "BUSINESS_REJECTED:DomainException/NOT_FOUND",
					"BUSINESS_REJECTED", i == 0 ? first : Instant.now(), RetryPolicy.POISON.budget(),
					RetryPolicy.TRANSIENT.budget());
		assertNotNull(retries.find(tenant, "points", lot).quarantinedAt());
	}

	private String isolatedEvent(String tenant, String type) {
		String id = UUID.randomUUID().toString();
		jdbc.update(
				"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,attempts,last_error,failure_class,first_failed_at,last_failed_at) VALUES(?,?,?,?,1,'{}','ISOLATED',5,'c:DomainException/CONFLICT','BUSINESS_REJECTED',?,?)",
				id, tenant, type, "agg-" + id, Timestamp.from(Instant.now().minusSeconds(60)),
				Timestamp.from(Instant.now()));
		return id;
	}

	private long remaining(String tenant, String lot) {
		return jdbc.queryForObject("SELECT remaining FROM member_point_lot WHERE tenant_id=? AND lot_id=?", Long.class,
				tenant, lot);
	}

	private int audits(String tenant) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM platform_recovery WHERE tenant_id=?", Integer.class, tenant);
	}

}
