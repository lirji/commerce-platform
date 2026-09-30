package com.lrj.commerce.app;

import com.lrj.commerce.app.runtime.monitoring.BackgroundRuntime;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.runtime.api.identity.Actor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * B2安全契约：跨租户运行健康只对平台运维开放；租户管理员只能读本租户健康；403/404隐藏规则不变。
 * 路由层要求PLATFORM_OPERATOR，用例层再要求EVENT_RUNTIME_METRICS_READ，任一层失效另一层仍拒绝。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PlatformRuntimeAuthorizationTest {

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

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	BackgroundRuntime runtime;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	private String tenant, other, run;

	@BeforeEach
	void tenants() {
		run = UUID.randomUUID().toString().substring(0, 8);
		tenant = "pa-" + run;
		other = "pb-" + run;
	}

	@AfterEach
	void clean() {
		jdbc.update("DELETE FROM platform_event WHERE tenant_id IN (?,?)", tenant, other);
	}

	private String token(String tenantId, String actor, String role) {
		String token = UUID.randomUUID() + "-" + UUID.randomUUID();
		jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,?,?)",
				JsonCodec.hash(token), tenantId, actor, role, java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
		return token;
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(15))
			.GET();
		if (token != null)
			req.header("Authorization", "Bearer " + token);
		return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	void globalRuntimeHealthRequiresThePlatformCapability() throws Exception {
		String member = token(tenant, "buyer", "MEMBER"), operator = token(tenant, "ops", "OPERATOR"),
				admin = token(tenant, "admin", "ADMIN"), platform = token("platform", "sre", "PLATFORM_OPERATOR");
		assertEquals(401, get("/v1/platform/runtime", null).statusCode(), "匿名");
		assertEquals(403, get("/v1/platform/runtime", member).statusCode(), "租户会员");
		assertEquals(403, get("/v1/platform/runtime", operator).statusCode(), "租户运营");
		assertEquals(403, get("/v1/platform/runtime", admin).statusCode(), "租户管理员不隐含跨租户能力");
		assertEquals(403, get("/v1/platform/no-such-endpoint", admin).statusCode(), "无权命名空间内不存在的路径也是403，不泄露路由存在性");
		var ok = get("/v1/platform/runtime", platform);
		assertEquals(200, ok.statusCode());
		var body = JsonCodec.read(ok.body(), Map.class);
		assertTrue(body.containsKey("events") && body.containsKey("lanes") && body.containsKey("alerts"), ok.body());
		assertEquals(404, get("/v1/platform/no-such-endpoint", platform).statusCode(), "有权命名空间内不存在的路径是404");
	}

	@Test
	void platformIdentityDoesNotGrantTenantAccess() throws Exception {
		String platform = token(tenant, "sre", "PLATFORM_OPERATOR");
		assertEquals(401, get("/v1/platform/me", null).statusCode());
		for (String role : List.of("ADMIN", "MEMBER", "OPERATOR"))
			assertEquals(403, get("/v1/platform/me", token(tenant, "actor-" + role, role)).statusCode());
		var response = get("/v1/platform/me", platform);
		assertEquals(200, response.statusCode());
		var identity = JsonCodec.read(response.body(), Map.class);
		assertEquals("PLATFORM_OPERATOR", identity.get("role"));
		assertEquals("sre", identity.get("actorId"));
		assertEquals(403, get("/v1/me", platform).statusCode());
		assertEquals(403, get("/v1/admin/runtime/work-types", platform).statusCode());
	}

	@Test
	void platformOperatorHasNoTenantPermissions() throws Exception {
		String platform = token(tenant, "sre", "PLATFORM_OPERATOR");
		// 即使平台凭据归属某个租户，也不能读取或操作该租户的任何业务或管理接口。
		for (String path : List.of("/v1/admin/events/health", "/v1/admin/events", "/v1/me", "/v1/orders",
				"/v1/operations/stores", "/v1/members/me/points"))
			assertEquals(403, get(path, platform).statusCode(), path);
	}

	@Test
	void globalViewExposesAggregatesOnlyWithoutTenantIdentifiers() throws Exception {
		jdbc.update(
				"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status) VALUES(?,?,'order.created.v1',?,1,'{}','PENDING')",
				"pe-" + run, other, "agg-" + run);
		var body = get("/v1/platform/runtime", token("platform", "sre", "PLATFORM_OPERATOR")).body();
		assertFalse(body.contains(other), "全局视图不得含租户标识");
		assertFalse(body.contains("agg-" + run), "全局视图不得含聚合标识");
		for (String lane : List.of("orders", "payments", "refunds", "points", "cycles", "segments", "journeys",
				"deliveries", "catalog-jobs", "events"))
			assertTrue(body.contains("\"" + lane + "\""), lane);
	}

	@Test
	void tenantAdministratorSeesOnlyItsOwnBacklog() throws Exception {
		jdbc.update(
				"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status) VALUES(?,?,'order.created.v1',?,1,'{}','PENDING')",
				"pe-" + run, other, "agg-" + run);
		String admin = token(tenant, "admin", "ADMIN");
		// 跨租户调用方即使传入其他租户参数也只得到本租户数据。
		var own = JsonCodec.read(get("/v1/admin/events/health?tenant=" + other, admin).body(), Map.class);
		assertEquals(0, ((Number) own.get("due")).intValue());
		var otherAdmin = JsonCodec.read(get("/v1/admin/events/health", token(other, "admin", "ADMIN")).body(),
				Map.class);
		assertEquals(1, ((Number) otherAdmin.get("due")).intValue());
	}

	@Test
	void useCaseLayerRejectsTenantRolesEvenIfTheRouteWereOpened() {
		for (var role : List.of(Actor.Role.ADMIN, Actor.Role.MEMBER, Actor.Role.OPERATOR)) {
			var failure = assertThrows(DomainException.class, () -> runtime.view(new Actor(tenant, "x", role)),
					role.name());
			assertEquals(DomainException.Code.FORBIDDEN, failure.code());
		}
		assertNotNull(runtime.view(new Actor("platform", "sre", Actor.Role.PLATFORM_OPERATOR)).events());
	}

}
