package com.lrj.commerce.app;

import static org.junit.jupiter.api.Assertions.*;

import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 在真实 MySQL 和 HTTP 入口验证操作审计事务、拒绝、去重及查询授权。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OperationAuditMySqlTest {
	private static final String CATEGORIES = "/v1/platform/commerce-categories";
	private static final String AUDITS = "/v1/platform/operation-audits";
	private final HttpClient http = HttpClient.newHttpClient();
	private final JsonMapper json = JsonMapper.builder().build();

	@Autowired JdbcTemplate jdbc;
	@LocalServerPort int port;

	private record Reply(int status, JsonNode body, String traceId) {}

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		String url = System.getenv("COMMERCE_TEST_DB_URL");
		if (url == null || !url.contains("/commerce_test_20260923?"))
			throw new IllegalStateException("必须使用隔离测试库");
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", () -> System.getenv("COMMERCE_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("COMMERCE_DB_PASSWORD"));
		registry.add("commerce.workers-enabled", () -> false);
	}

	@Test
	void disablingProducesFourAttemptsOneEffectAndReadEvidence() throws Exception {
		String tenant = "audit-" + UUID.randomUUID();
		String otherTenant = "other-" + UUID.randomUUID();
		String curator = credential(tenant, "audit-curator", "PLATFORM_OPERATOR");
		String reader = credential(tenant, "audit-reader", "PLATFORM_OPERATOR");
		String admin = credential(otherTenant, "audit-tenant-admin", "ADMIN");
		jdbc.update("INSERT INTO platform_category_curator(tenant_id,actor_id) VALUES(?,?)", tenant, "audit-curator");
		String category = category(curator);
		var payload = payload(category, "按字典调整停用");
		var success = call("POST", CATEGORIES + "/" + category, curator, "disable", payload);
		assertEquals(200, success.status(), success.body().toString());
		assertEquals(success.body(), call("POST", CATEGORIES + "/" + category, curator, "disable", payload).body());
		assertEquals(409, call("POST", CATEGORIES + "/" + category, curator, "stale", payload).status());
		assertEquals(403, call("POST", CATEGORIES + "/" + category, reader, "forbidden", payload).status());
		assertEquals(403, call("POST", CATEGORIES + "/" + category, admin, "forbidden", payload).status());
		assertEquals(403, call("GET", AUDITS, admin, null, null).status());

		var page = call("GET", disableHistory(category), reader, null, null);
		assertEquals(200, page.status(), page.body().toString());
		var results = new java.util.HashMap<String, JsonNode>();
		page.body().path("items").forEach(record -> results.put(record.path("result").asText(), record));
		assertEquals(4, page.body().path("items").size(), page.body().toString());
		assertEquals(Set.of("SUCCEEDED", "REPLAYED", "CONFLICT", "DENIED"), results.keySet());
		var committed = results.get("SUCCEEDED");
		assertEquals(success.traceId(), committed.path("traceId").asText());
		assertEquals(0, committed.path("beforeVersion").asLong());
		assertEquals(1, committed.path("afterVersion").asLong());
		assertEquals("按字典调整停用", committed.path("reason").asText());
		var state = committed.path("changes").path(committed.path("changes").size() - 1);
		assertEquals("enabled", state.path("field").asText());
		assertTrue(state.path("before").asBoolean());
		assertFalse(state.path("after").asBoolean());
		assertEquals(committed.path("id"), results.get("REPLAYED").path("originalAttemptId"));
		assertEquals(committed.path("effectId"), results.get("REPLAYED").path("effectId"));
		assertEquals(1, count(CATEGORY_EFFECTS, category));

		String detailPath = AUDITS + "/" + committed.path("id").asText();
		assertEquals(200, call("GET", detailPath, reader, null, null).status());
		assertEquals(403, call("GET", detailPath, admin, null, null).status());
		assertEquals(1, count("SELECT COUNT(*) FROM audit_operation_attempt WHERE actor_tenant_id=? "
				+ "AND action_code='platform.audit.read'", tenant));

		Set<String> seen = new HashSet<>();
		String next = "";
		do {
			var slice = call("GET", disableHistory(category) + "&limit=1" + next, reader, null, null);
			assertEquals(200, slice.status(), slice.body().toString());
			assertEquals(1, slice.body().path("items").size());
			assertTrue(seen.add(slice.body().path("items").path(0).path("id").asText()));
			var cursor = slice.body().path("nextCursor");
			next = cursor.isTextual() ? "&cursor=" + URLEncoder.encode(cursor.asText(), StandardCharsets.UTF_8) : "";
		}
		while (!next.isEmpty());
		assertEquals(4, seen.size());
		assertEquals(400, call("GET", AUDITS + "?limit=101", reader, null, null).status());
		assertEquals(1, count("SELECT COUNT(*) FROM audit_operation_attempt WHERE actor_tenant_id=? "
				+ "AND actor_id='audit-tenant-admin' AND scope_type='SECURITY' AND resource_id IS NULL", otherTenant));
	}

	@Test
	void auditWriteFailureRollsBackVersionDomainHistoryAndReceiptAndSameKeyCanRetry() throws Exception {
		String tenant = "audit-" + UUID.randomUUID();
		String curator = credential(tenant, "audit-rollback", "PLATFORM_OPERATOR");
		jdbc.update("INSERT INTO platform_category_curator(tenant_id,actor_id) VALUES(?,?)", tenant, "audit-rollback");
		String category = category(curator);
		String reason = "联络账号 security-test@example.invalid";
		// 仅拒绝本次生成的受控类目编码，其他事务可照常通过。
		jdbc.execute("ALTER TABLE platform_audit ADD CONSTRAINT ck_oa_test_effect CHECK ("
				+ "operation <> 'platform.category.change' OR audit_resource_id <> '" + category + "')");
		try {
			assertEquals(503, call("POST", CATEGORIES + "/" + category, curator, "disable", payload(category, reason)).status());
			assertEquals(0, jdbc.queryForObject("SELECT version FROM store_opening_category WHERE category_code=?",
					Integer.class, category));
			assertEquals(0, count("SELECT COUNT(*) FROM platform_command WHERE tenant_id=? "
					+ "AND command_key='disable'", tenant));
			assertEquals(0, count("SELECT COUNT(*) FROM platform_category_history WHERE category_code=? AND version=1",
					category));
		}
		finally {
			jdbc.execute("ALTER TABLE platform_audit DROP CHECK ck_oa_test_effect");
		}
		assertEquals(200, call("POST", CATEGORIES + "/" + category, curator, "disable", payload(category, reason)).status());
		assertEquals("已脱敏", jdbc.queryForObject("SELECT audit_reason FROM platform_audit WHERE audit_resource_id=? "
				+ "AND operation='platform.category.change'", String.class, category));
	}

	@Test
	void lostResponseStillLinksRetryToCommittedEffect() throws Exception {
		String tenant = "audit-" + UUID.randomUUID();
		String curator = credential(tenant, "audit-disconnect", "PLATFORM_OPERATOR");
		jdbc.update("INSERT INTO platform_category_curator(tenant_id,actor_id) VALUES(?,?)", tenant, "audit-disconnect");
		String category = category(curator);
		String path = CATEGORIES + "/" + category;
		var payload = payload(category, "断连后核实提交结果");
		byte[] body = JsonCodec.write(payload).getBytes(StandardCharsets.UTF_8);
		String headers = "POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer " + curator
				+ "\r\nIdempotency-Key: disconnect\r\nContent-Type: application/json\r\nContent-Length: " + body.length
				+ "\r\nConnection: close\r\n\r\n";
		try (var socket = new Socket("127.0.0.1", port)) {
			socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
			socket.getOutputStream().write(body);
			socket.shutdownOutput(); // 发送完整请求后主动关闭，不读取服务端响应。
		}
		for (int i = 0; i < 60 && count(CATEGORY_EFFECTS, category) == 0; i++)
			Thread.sleep(50);
		assertEquals(1, count(CATEGORY_EFFECTS, category));
		assertEquals(200, call("POST", path, curator, "disconnect", payload).status());
		var page = call("GET", disableHistory(category), curator, null, null).body().path("items");
		assertEquals(2, page.size());
		assertEquals("REPLAYED", page.path(0).path("result").asText());
		assertEquals("SUCCEEDED", page.path(1).path("result").asText());
		assertEquals(page.path(1).path("id"), page.path(0).path("originalAttemptId"));
	}

	@Test
	void changingNameAndSortProducesOneSuccessfulEffectWithBothFields() throws Exception {
		String tenant = "audit-" + UUID.randomUUID();
		String curator = credential(tenant, "audit-edit-category", "PLATFORM_OPERATOR");
		jdbc.update("INSERT INTO platform_category_curator(tenant_id,actor_id) VALUES(?,?)", tenant, "audit-edit-category");
		String category = category(curator);
		String edit = CATEGORIES + "/" + category;
		var payload = Map.of("expectedVersion", 0, "name", category + "改名", "sortOrder", 12, "enabled", true,
				"reason", "同一次编辑名称和排序");
		assertEquals(200, call("POST", edit, curator, "edit", payload).status());
		var rows = call("GET", AUDITS + "?resourceId=" + category + "&action=platform.category.rename",
				curator, null, null).body().path("items");
		assertEquals(1, rows.size());
		assertEquals(2, rows.path(0).path("changes").size());
		assertEquals("SUCCEEDED", rows.path(0).path("result").asText());
		assertEquals(1, count(CATEGORY_EFFECTS, category));
	}

	private static final String CATEGORY_EFFECTS = "SELECT COUNT(*) FROM platform_audit WHERE audit_resource_id=? "
			+ "AND operation='platform.category.change'";

	private static String disableHistory(String category) {
		return AUDITS + "?resourceId=" + category + "&action=platform.category.disable";
	}

	private String category(String curator) throws Exception {
		String code = "OA_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT);
		var created = call("POST", CATEGORIES, curator, "create", Map.of("code", code, "name", code, "kind", "BRANCH",
				"sortOrder", 10, "reason", "准备隔离测试类目"));
		assertEquals(200, created.status(), created.body().toString());
		return code;
	}

	private Map<String, Object> payload(String category, String reason) {
		return Map.of("expectedVersion", 0, "name", category, "sortOrder", 10, "enabled", false, "reason", reason);
	}

	private int count(String sql, String key) {
		return jdbc.queryForObject(sql, Integer.class, key);
	}

	private String credential(String tenant, String actor, String role) {
		String token = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,?,?)",
				JsonCodec.hash(token), tenant, actor, role, java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
		return token;
	}

	private Reply call(String method, String path, String token, String key, Object body) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.header("Authorization", "Bearer " + token);
		if (key != null)
			request.header("Idempotency-Key", key);
		if (body != null)
			request.header("Content-Type", "application/json");
		var response = http.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(JsonCodec.write(body))).build(), HttpResponse.BodyHandlers.ofString());
		return new Reply(response.statusCode(), json.readTree(response.body()),
				response.headers().firstValue("X-Trace-Id").orElseThrow());
	}
}
