package com.lrj.commerce.app;

import static org.junit.jupiter.api.Assertions.*;

import com.lrj.commerce.runtime.serialization.JsonCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 用真实 MySQL 和 HTTP 保护待开店隔离、并发开通、幂等以及旧客户端兼容。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StoreOpeningMySqlTest {
	private static final String OPENINGS = "/v1/admin/store-openings";
	private static final String STORE_PATH = OPENINGS + "/fresh-store";
	private static final String MERCHANT = "housewares";
	private static final Map<String, Object> DRAFT = Map.of("storeId", "fresh-store", "merchantId", MERCHANT,
			"name", "新店", "subjectType", "INDIVIDUAL", "category", "家居");

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

	@Autowired JdbcTemplate jdbc;
	@LocalServerPort int port;

	private final HttpClient http = HttpClient.newHttpClient();
	private final JsonMapper json = JsonMapper.builder().build();
	private String admin;
	private String member;
	private String otherAdmin;

	record Reply(int status, JsonNode body) {
	}

	@BeforeEach
	void prepareMerchant() throws Exception {
		String tenant = "store-opening-" + UUID.randomUUID();
		admin = credential(tenant, "owner", "ADMIN");
		member = credential(tenant, "buyer", "MEMBER");
		otherAdmin = credential("other-" + UUID.randomUUID(), "other-owner", "ADMIN");
		assertEquals(200, post("/v1/admin/merchants", "merchant", Map.of("merchantId", MERCHANT, "name", "家居商家")).status());
	}

	@Test
	void draftIsRecoverableAndOpeningRequiresCurrentReviewedVersion() throws Exception {
		assertEquals(400, post(OPENINGS, "bad-subject", Map.of("storeId", "invalid", "merchantId", MERCHANT,
				"name", "错误类型", "subjectType", 0, "category", "家居")).status());
		var draft = post(OPENINGS, "draft", DRAFT);
		assertEquals(200, draft.status(), draft.body().toString());
		assertEquals("PENDING_OPEN", draft.body().path("status").asText());
		assertEquals(draft, post(OPENINGS, "draft", DRAFT));
		assertEquals("PENDING_OPEN", get("/v1/admin/stores?status=PENDING_OPEN", admin).body().path(0).path("status").asText());
		assertEquals(0, get("/v1/stores", member).body().size());
		assertEquals(409, get("/v1/catalog?storeId=fresh-store", member).status());
		assertEquals(403, get(STORE_PATH, member).status());
		assertEquals(404, get(STORE_PATH, otherAdmin).status());
		assertEquals(400, post(STORE_PATH + "/open", "no-review", Map.of("expectedVersion", 0)).status());
		var revise = post(STORE_PATH + "/revise", "revise", Map.of("expectedVersion", 0, "name", "修订后新店",
				"subjectType", "ENTERPRISE", "category", "家居收纳"));
		assertEquals(200, revise.status(), revise.body().toString());
		assertEquals("家居收纳", get(STORE_PATH, admin).body().path("category").asText());
		assertEquals(409, post(STORE_PATH + "/open", "stale", Map.of("expectedVersion", 0, "reviewReference", "REVIEW-1")).status());

		var request = Map.of("expectedVersion", 1, "reviewReference", "REVIEW-2");
		var first = send("POST", STORE_PATH + "/open", admin, "open-1", request);
		var second = send("POST", STORE_PATH + "/open", admin, "open-2", request);
		var replies = List.of(first.get(), second.get());
		assertEquals(List.of(200, 409), replies.stream().map(Reply::status).sorted().toList());
		var accepted = replies.stream().filter(reply -> reply.status() == 200).findFirst().orElseThrow();
		assertEquals("owner", accepted.body().path("openedBy").asText());
		assertEquals("REVIEW-2", accepted.body().path("reviewReference").asText());
		assertEquals(accepted, post(STORE_PATH + "/open", first.get().status() == 200 ? "open-1" : "open-2", request));
		assertEquals(409, post(STORE_PATH + "/revise", "revise-opened", Map.of("expectedVersion", 2,
				"name", "不可修改", "subjectType", "ENTERPRISE", "category", "家居")).status());
		assertEquals("fresh-store", get("/v1/stores", member).body().path(0).path("storeId").asText());
		assertEquals(200, get("/v1/catalog?storeId=fresh-store", member).status());
	}

	@Test
	void legacyCreateKeepsItsResponseAndDoesNotAcquireOpeningProfile() throws Exception {
		var legacy = post("/v1/admin/stores", "legacy", Map.of("storeId", "legacy-store", "merchantId", MERCHANT,
				"name", "存量直建"));
		assertEquals(200, legacy.status(), legacy.body().toString());
		assertEquals("ACTIVE", legacy.body().path("status").asText());
		assertEquals(404, get(OPENINGS + "/legacy-store", admin).status());
		assertEquals("legacy-store", get("/v1/stores", member).body().path(0).path("storeId").asText());
	}

	private String credential(String tenant, String actor, String role) {
		String value = UUID.randomUUID().toString();
		jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,?,?)",
				JsonCodec.hash(value), tenant, actor, role, Timestamp.from(Instant.now().plusSeconds(3600)));
		return value;
	}

	private Reply post(String path, String key, Object body) throws Exception {
		return send("POST", path, admin, key, body).get();
	}

	private Reply get(String path, String credential) throws Exception {
		return send("GET", path, credential, null, null).get();
	}

	private CompletableFuture<Reply> send(String method, String path, String token, String key, Object body) {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
		request.header("Authorization", "Bearer " + token);
		if (body != null) {
			request.header("Idempotency-Key", key);
			request.header("Content-Type", "application/json");
			request.method(method, HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
		}
		return http.sendAsync(request.build(), HttpResponse.BodyHandlers.ofString())
				.thenApply(response -> new Reply(response.statusCode(), json.readTree(response.body())));
	}
}
