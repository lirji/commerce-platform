package com.lrj.commerce.app.configuration;

import com.lrj.commerce.runtime.JsonCodec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.server.PathContainer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPatternParser;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 以真实控制器映射校验会员放行清单：会员接口全部可达，未登记路径默认拒绝。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationCoverageTest {

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

	private static final List<String> STAFF_PREFIXES = List.of("/v1/admin/", "/v1/operations/", "/v1/platform/");

	private static final Set<String> ALL_ROLE_PATHS = Set.of("/v1/me", "/v1/runtime-capabilities");

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping mappings;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	private String tenant;

	@BeforeEach
	void tenant() {
		tenant = "t-" + UUID.randomUUID();
	}

	private String token(String actor, String role) {
		String token = UUID.randomUUID() + "-" + UUID.randomUUID();
		jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,?,?)",
				JsonCodec.hash(token), tenant, actor, role, java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
		return token;
	}

	private int status(String method, String path, String token) throws Exception {
		var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
			.timeout(Duration.ofSeconds(15))
			.header("Authorization", "Bearer " + token);
		return http
			.send(req.method(method, HttpRequest.BodyPublishers.noBody()).build(),
					HttpResponse.BodyHandlers.discarding())
			.statusCode();
	}

	/** 真实注册的会员侧接口（方法、具体化路径）。 */
	private List<String[]> memberEndpoints() {
		var result = new ArrayList<String[]>();
		mappings.getHandlerMethods().forEach((info, handler) -> {
			if (info.getPathPatternsCondition() == null)
				return;
			for (String pattern : info.getPathPatternsCondition().getPatternValues()) {
				if (!pattern.startsWith("/v1/") || ALL_ROLE_PATHS.contains(pattern)
						|| STAFF_PREFIXES.stream().anyMatch(pattern::startsWith))
					continue;
				var methods = info.getMethodsCondition().getMethods();
				result.add(new String[] { methods.isEmpty() ? "GET" : methods.iterator().next().name(),
						pattern.replaceAll("\\{[^}]*}", "x") });
			}
		});
		return result;
	}

	@Test
	void everyRegisteredMemberEndpointIsReachableAndListedExplicitly() throws Exception {
		String member = token("buyer", "MEMBER");
		var endpoints = memberEndpoints();
		assertTrue(endpoints.size() >= 30, "必须扫描到真实会员接口：" + endpoints.size());
		for (var endpoint : endpoints) {
			int code = status(endpoint[0], endpoint[1], member);
			// 业务层可因参数、归属或幂等键拒绝，但不能被安全层默认拒绝。
			assertFalse(code == 401 || code == 403, endpoint[0] + " " + endpoint[1] + " -> " + code);
		}
		var parser = PathPatternParser.defaultInstance;
		for (String allowed : SecurityConfiguration.MEMBER_PATHS) {
			var pattern = parser.parse(allowed);
			assertTrue(endpoints.stream().anyMatch(e -> pattern.matches(PathContainer.parsePath(e[1]))),
					"放行清单存在失效条目：" + allowed);
		}
	}

	@Test
	void unlistedPathsAreDeniedByDefaultForEveryRole() throws Exception {
		for (String role : List.of("MEMBER", "ADMIN", "OPERATOR", "PLATFORM_OPERATOR")) {
			String token = token("actor-" + role, role);
			assertEquals(403, status("GET", "/v1/unregistered-capability", token), role);
			assertEquals(403, status("POST", "/v1/internal/anything", token), role);
		}
		assertEquals(403, status("GET", "/v1/orders", token("operator", "OPERATOR")));
		assertEquals(403, status("GET", "/v1/admin/members", token("buyer", "MEMBER")));
	}

	/**
	 * 403/404契约：调用方无权进入的命名空间一律403（匿名401），不论路径是否存在，避免借状态码枚举接口； 调用方有权进入的命名空间内不存在的路径才返回404。
	 */
	@Test
	void statusDistinguishesPermittedNamespaceNotRouteExistence() throws Exception {
		String member = token("buyer", "MEMBER"), admin = token("admin", "ADMIN");
		var anonymous = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/unregistered-capability"))
			.timeout(Duration.ofSeconds(15))
			.GET()
			.build();
		assertEquals(401, http.send(anonymous, HttpResponse.BodyHandlers.discarding()).statusCode());
		assertEquals(403, status("GET", "/v1/unregistered-capability", member));
		assertEquals(403, status("GET", "/v1/admin/no-such-endpoint", member));
		assertEquals(403, status("GET", "/v1/admin/events/health", member), "存在的管理接口对会员同样是403");
		assertEquals(404, status("GET", "/v1/admin/no-such-endpoint", admin));
		assertEquals(404, status("GET", "/v1/members/me/no-such-endpoint", member));
		assertEquals(200, status("GET", "/v1/admin/events/health", admin));
	}

}
