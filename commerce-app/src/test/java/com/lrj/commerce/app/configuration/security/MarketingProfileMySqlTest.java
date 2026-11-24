package com.lrj.commerce.app.configuration.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** 真库、真实过滤链与真实响应，覆盖机器授权和上游状态排除。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MarketingProfileMySqlTest {
	private static final String TENANT = "profile-test-" + UUID.randomUUID();
	private static final String MARKETING = "marketing-test";
	private static final String TOKEN = "test-" + UUID.randomUUID() + UUID.randomUUID();

	@DynamicPropertySource
	static void properties(DynamicPropertyRegistry registry) {
		String url = System.getenv("COMMERCE_TEST_DB_URL");
		if (url == null || !url.contains("/commerce_test_20260923?")) {
			throw new IllegalStateException("必须显式指定本项目隔离测试库");
		}
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", () -> System.getenv("COMMERCE_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("COMMERCE_DB_PASSWORD"));
		registry.add("commerce.workers-enabled", () -> false);
		registry.add("commerce.marketing.member-profile.enabled", () -> true);
		registry.add("commerce.marketing.member-profile.marketing-tenant", () -> MARKETING);
		registry.add("commerce.marketing.member-profile.commerce-tenant", () -> TENANT);
		registry.add("commerce.marketing.member-profile.bearer-token", () -> TOKEN);
	}

	@LocalServerPort int port;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper mapper;
	private final HttpClient http = HttpClient.newHttpClient();

	@Test
	void onlyPairedMachineReadsCompleteActivePopulation() throws Exception {
		var empty = request(MARKETING, TOKEN);
		assertEquals(200, empty.statusCode(), empty.body());
		assertEquals(0, mapper.readTree(empty.body()).path("profiles").size());
		member(TENANT, "active", "VIP", "ACTIVE");
		member(TENANT, "frozen", "VIP", "FROZEN");
		member(TENANT + "-other", "outside", "VIP", "ACTIVE");

		assertEquals(403, request("wrong-marketing-tenant", TOKEN).statusCode());
		assertEquals(403, request(MARKETING, "wrong-token").statusCode());
		assertEquals(403, request(MARKETING, null).statusCode());
		var snapshot = request(MARKETING, TOKEN);
		assertEquals(200, snapshot.statusCode(), snapshot.body());
		assertTrue(snapshot.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
		var body = mapper.readTree(snapshot.body());
		assertFalse(Instant.parse(body.path("asOf").asString()).isBefore(Instant.now().minusSeconds(15)));
		assertEquals(1, body.path("profiles").size());
		var member = body.path("profiles").get(0);
		assertEquals(2, member.size());
		assertEquals("active", member.path("memberId").asString());
		assertEquals("VIP", member.path("memberLevel").asString());
	}

	private void member(String tenant, String id, String level, String status) {
		jdbc.update("INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status) VALUES(?,?,?,?,?,?)",
				tenant, id, "actor-" + id, "private display name", level, status);
	}

	private HttpResponse<String> request(String tenant, String token) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + MarketingProfileSecurity.PATH))
			.header("X-Marketing-Tenant", tenant).GET();
		if (token != null) request.header("Authorization", "Bearer " + token);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
