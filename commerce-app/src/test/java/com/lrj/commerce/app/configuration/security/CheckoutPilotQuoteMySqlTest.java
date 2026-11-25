package com.lrj.commerce.app.configuration.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 通过真实报价、MySQL 和 HTTP 核对试点证据的信任范围。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CheckoutPilotQuoteMySqlTest {
	private static final String TENANT = "pilot-" + UUID.randomUUID();
	private static final String TRADE_TENANT = "906021";
	private static final String MACHINE = UUID.randomUUID() + "-" + UUID.randomUUID();
	private static final String MERCHANT = "pilot-merchant";
	private static final String STORE = "participating-store";
	private static final String CONTROL = "control-store";
	private static final String SKU = "pilot-mug";
	private static final String CONTROL_SKU = "control-mug";
	private static final String SCOPE_VERSION = "lc02-store-r1";

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
		registry.add("commerce.checkout-pilot.enabled", () -> true);
		registry.add("commerce.checkout-pilot.scope-version", () -> SCOPE_VERSION);
		registry.add("commerce.checkout-pilot.trade-tenant", () -> TRADE_TENANT);
		registry.add("commerce.checkout-pilot.commerce-tenant", () -> TENANT);
		registry.add("commerce.checkout-pilot.merchant-id", () -> MERCHANT);
		registry.add("commerce.checkout-pilot.store-id", () -> STORE);
		registry.add("commerce.checkout-pilot.sku-id", () -> SKU);
		registry.add("commerce.checkout-pilot.bearer-token", () -> MACHINE);
	}

	@LocalServerPort int port;
	@Autowired JdbcTemplate jdbc;
	@Autowired ObjectMapper json;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	@Test
	void returnsOnlyVerifiedMerchantStoreAndSkuForThePairedMachine() throws Exception {
		String buyer = seed(TENANT, true);
		String checkout = quote(buyer, STORE, SKU);
		String outside = quote(buyer, CONTROL, CONTROL_SKU);
		String nonparticipatingSku = quote(buyer, STORE, "unselected-sku");
		String otherTenant = quote(seed(TENANT + "-other", false), STORE, SKU);

		assertEquals(403, evidence(checkout, TRADE_TENANT, null).statusCode());
		assertEquals(403, evidence(checkout, TRADE_TENANT, buyer).statusCode());
		assertEquals(403, evidence(checkout, "906022", MACHINE).statusCode());
		assertEquals(403, http.send(request(path(checkout), MACHINE)
			.header(CheckoutPilotSecurity.TRADE_TENANT_HEADER, TRADE_TENANT)
			.header(CheckoutPilotSecurity.TRADE_TENANT_HEADER, TRADE_TENANT).GET().build(),
			HttpResponse.BodyHandlers.ofString()).statusCode());
		assertEquals(404, evidence(outside, TRADE_TENANT, MACHINE).statusCode());
		assertEquals(404, evidence(nonparticipatingSku, TRADE_TENANT, MACHINE).statusCode());
		assertEquals(404, evidence(otherTenant, TRADE_TENANT, MACHINE).statusCode());
		assertEquals(400, evidence("invalid", TRADE_TENANT, MACHINE).statusCode());

		var response = evidence(checkout, TRADE_TENANT, MACHINE);
		assertEquals(200, response.statusCode(), response.body());
		assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
		JsonNode body = json.readTree(response.body());
		assertEquals(TENANT, body.path("commerceTenantId").asString());
		assertEquals(MERCHANT, body.path("merchantId").asString());
		assertEquals(STORE, body.path("storeId").asString());
		assertEquals(SCOPE_VERSION, body.path("scopeVersion").asString());
		assertEquals(TRADE_TENANT, body.path("tradeTenantId").asString());
		assertEquals(checkout, body.path("commerceQuoteId").asString());
		assertEquals("CNY", body.path("currency").asString());
		assertEquals(SKU, body.path("eligibleSkuId").asString());
		assertTrue(body.path("ownerRef").asString().matches("[0-9a-f]{64}"));
		assertTrue(Instant.parse(body.path("expiresAt").asString()).isAfter(Instant.now()));
		assertFalse(response.body().contains("memberId"));
		assertFalse(response.body().contains("buyer"));
		assertEquals(1, body.path("items").size());
		assertEquals("5900", body.path("items").get(0).path("unitPriceMinor").asString());
		assertEquals(2, body.path("items").get(0).path("quantity").asInt());

		// 冻结后重查同一份持久报价，证明已有报价不会绕过门店当前状态。
		assertEquals(1, jdbc.update("UPDATE store_record SET status='FROZEN' WHERE tenant_id=? AND store_id=?", TENANT, STORE));
		assertEquals(409, evidence(checkout, TRADE_TENANT, MACHINE).statusCode());
	}

	private String seed(String tenant, boolean control) throws Exception {
		String admin = token(tenant, "admin", "ADMIN");
		String buyer = token(tenant, "buyer", "MEMBER");
		post("/v1/admin/members", admin, Map.of("memberId", "member-1", "actorId", "buyer", "displayName", "验收会员",
				"memberLevel", "NORMAL"));
		post("/v1/admin/merchants", admin, Map.of("merchantId", MERCHANT, "name", "验收商户"));
		storeSku(admin, STORE, SKU);
		if (control) {
			storeSku(admin, CONTROL, CONTROL_SKU);
			post("/v1/admin/skus", admin, Map.of("skuId", "unselected-sku", "storeId", STORE, "title", "未参与商品",
					"unitPrice", "59.00"));
		}
		return buyer;
	}

	private void storeSku(String admin, String store, String sku) throws Exception {
		post("/v1/admin/stores", admin, Map.of("storeId", store, "merchantId", MERCHANT, "name", "验收门店"));
		post("/v1/admin/skus", admin, Map.of("skuId", sku, "storeId", store, "title", "陶瓷随行杯", "unitPrice", "59.00"));
	}

	private String quote(String buyer, String store, String sku) throws Exception {
		return post("/v1/quotes", buyer,
				Map.of("storeId", store, "items", List.of(Map.of("skuId", sku, "quantity", 2))))
			.path("quoteId").asString();
	}

	private String token(String tenant, String actor, String role) {
		String token = UUID.randomUUID() + "-" + UUID.randomUUID();
		jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,?,?)",
				JsonCodec.hash(token), tenant, actor, role, Timestamp.from(Instant.now().plusSeconds(3600)));
		return token;
	}

	private JsonNode post(String path, String token, Object payload) throws Exception {
		var response = http.send(request(path, token).header("Idempotency-Key", UUID.randomUUID().toString())
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(),
			HttpResponse.BodyHandlers.ofString());
		assertEquals(200, response.statusCode(), response.body());
		return json.readTree(response.body());
	}

	private HttpResponse<String> evidence(String quoteId, String tenant, String token) throws Exception {
		return http.send(request(path(quoteId), token).header(CheckoutPilotSecurity.TRADE_TENANT_HEADER, tenant)
			.GET().build(), HttpResponse.BodyHandlers.ofString());
	}

	private String path(String quoteId) {
		return CheckoutPilotSecurity.PATH.replace("{quoteId}", quoteId);
	}

	private HttpRequest.Builder request(String path, String token) {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
		if (token != null) {
			request.header("Authorization", "Bearer " + token);
		}
		return request;
	}
}
