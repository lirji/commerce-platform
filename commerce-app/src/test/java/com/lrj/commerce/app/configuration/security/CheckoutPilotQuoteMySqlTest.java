package com.lrj.commerce.app.configuration.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
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

	private static final String ORDER = "TRD-ORD-261126-0000000101";
	private static final String TRADE_PATH = "/api/v1/trade/admin/orders/" + ORDER + "/operation-evidence";
	private static final String FUNDING_PATH = "/api/v1/funding/trade-orders/" + TRADE_TENANT + "/101/operation-audits";
	private static final AtomicReference<Reply> TRADE = new AtomicReference<>(new Reply(503, "{}"));
	private static final AtomicReference<Reply> FUNDING = new AtomicReference<>(new Reply(503, "{}"));
	private static final HttpServer SOURCES = sourceFixtures();

	private record Reply(int code, String json) {}

	private static HttpServer sourceFixtures() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext(TRADE_PATH, exchange -> respond(exchange, TRADE.get(), TRADE_PATH));
			server.createContext(FUNDING_PATH, exchange -> respond(exchange, FUNDING.get(), FUNDING_PATH));
			server.start();
			return server;
		} catch (IOException failure) {
			throw new IllegalStateException("无法启动同单外部接口测试替身", failure);
		}
	}

	private static void respond(com.sun.net.httpserver.HttpExchange exchange, Reply reply, String path) throws IOException {
		try (exchange) {
			boolean authorized = path.equals(exchange.getRequestURI().getPath())
					&& ("Bearer " + MACHINE).equals(exchange.getRequestHeaders().getFirst("Authorization"));
			byte[] body = (authorized ? reply.json() : "{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(authorized ? reply.code() : 403, body.length);
			exchange.getResponseBody().write(body);
		}
	}

	@AfterAll
	static void stopSources() {
		SOURCES.stop(0);
	}

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
		registry.add("commerce.order-audit.enabled", () -> true);
		String source = "http://127.0.0.1:" + SOURCES.getAddress().getPort();
		registry.add("commerce.order-audit.trade-url", () -> source);
		registry.add("commerce.order-audit.trade-token", () -> MACHINE);
		registry.add("commerce.order-audit.funding-url", () -> source);
		registry.add("commerce.order-audit.funding-token", () -> MACHINE);
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

		verifyOrderAudit(checkout, outside, buyer);

		// 冻结后重查同一份持久报价，证明已有报价不会绕过门店当前状态。
		assertEquals(1, jdbc.update("UPDATE store_record SET status='FROZEN' WHERE tenant_id=? AND store_id=?", TENANT, STORE));
		assertEquals(409, evidence(checkout, TRADE_TENANT, MACHINE).statusCode());
	}

	private void verifyOrderAudit(String quoteId, String outsideQuote, String buyer) throws Exception {
		String path = "/v1/admin/order-operation-evidence?orderNo=" + ORDER;
		String admin = token(TENANT, "audit-admin", "ADMIN");
		assertEquals(403, get(path, buyer).statusCode());
		assertEquals(403, get(path, token(TENANT + "-other", "other-admin", "ADMIN")).statusCode());

		Map<String, Object> validBinding = Map.of("commerceTenant", TENANT, "merchantId", MERCHANT,
				"storeId", STORE, "scopeVersion", SCOPE_VERSION, "checkoutReference", "approved-reference",
				"commerceQuoteId", quoteId, "fundingApplicationId", "verified-application");
		var trade = new java.util.HashMap<String, Object>(Map.of("tradeTenant", TRADE_TENANT, "orderId", "101",
				"businessNo", ORDER, "binding", validBinding, "financialStatus", "REFUNDED",
				"receipts", List.of(Map.of("businessNo", "TRD-RCP-261126-0000000001", "amountMinor", "1000",
						"currency", "CNY", "disposition", "NORMAL")), "refunds", List.of()));
		TRADE.set(new Reply(200, json.writeValueAsString(trade)));
		var partial = get(path, admin);
		assertEquals(200, partial.statusCode(), partial.body());
		var snapshot = json.readTree(partial.body());
		assertEquals("AVAILABLE", snapshot.path("commerce").path("status").asString());
		assertEquals("1000", snapshot.path("trade").path("data").path("receipts").get(0).path("amountMinor").asString());
		assertEquals("UNAVAILABLE", snapshot.path("marketing").path("status").asString());
		assertFalse(partial.body().contains("memberId"));

		FUNDING.set(new Reply(200, json.writeValueAsString(Map.of("checkoutReference", "approved-reference",
				"applicationId", "verified-application", "applicationNo", "MKT-FAP-261126-0000000101", "generation", 7,
				"history", Map.of("items", List.of(Map.of("action", "funding.reverse", "amountMinor", "1000",
						"currency", "CNY", "result", "SUCCEEDED")))))));
		var recovered = get(path + "&source=MARKETING", admin);
		assertEquals(200, recovered.statusCode(), recovered.body());
		assertEquals("AVAILABLE", json.readTree(recovered.body()).path("marketing").path("status").asString());
		assertEquals(7, json.readTree(recovered.body()).path("marketing").path("data").path("generation").asInt());

		var outside = new java.util.HashMap<>(validBinding);
		outside.put("commerceQuoteId", outsideQuote);
		trade.put("binding", outside);
		TRADE.set(new Reply(200, json.writeValueAsString(trade)));
		assertEquals(404, get(path, admin).statusCode());
		assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM audit_operation_attempt WHERE actor_tenant_id=?"
				+ " AND store_id=? AND action_code='order.evidence.read'", Integer.class, TENANT, STORE));
	}

	private HttpResponse<String> get(String path, String token) throws Exception {
		return http.send(request(path, token).GET().build(), HttpResponse.BodyHandlers.ofString());
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
