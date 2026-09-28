package com.lrj.commerce.app;

import com.lrj.commerce.runtime.api.identity.Actor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import com.lrj.commerce.runtime.command.Commands;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import com.lrj.commerce.ordering.order.infrastructure.persistence.OrderMapper;
import com.lrj.commerce.runtime.work.RetryPolicy;
import com.lrj.commerce.runtime.api.event.EventHandler;
import com.lrj.commerce.runtime.event.EventDispatcher;
import com.lrj.commerce.runtime.event.persistence.EventMapper;
import com.lrj.commerce.payment.charge.application.PaymentService;

/** 真实MySQL与HTTP验证，不用Mock证明事务或身份隔离。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PersistedCommerceTest {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		String url = System.getenv("COMMERCE_TEST_DB_URL");
		if (url == null || !url.contains("/commerce_test_20260923?"))
			throw new IllegalStateException("必须显式指定本项目隔离测试库");
		registry.add("spring.datasource.url", () -> url);
		registry.add("commerce.sandbox-enabled", () -> true);
		registry.add("commerce.workers-enabled", () -> false);
		registry.add("commerce.marketing.coupon-enabled", () -> true);
		registry.add("commerce.marketing.extended-trace-enabled", () -> true);
		registry.add("spring.datasource.username", () -> System.getenv("COMMERCE_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("COMMERCE_DB_PASSWORD"));
	}

	@LocalServerPort
	int port;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	Commands commands;

	@Autowired
	com.lrj.commerce.payment.charge.api.PaymentApi payments;

	@Autowired
	com.lrj.commerce.payment.refund.api.RefundApi refunds;

	@Autowired
	OrderMapper orderMapper;

	@Autowired
	EventMapper eventMapper;

	@Autowired
	PaymentService paymentService;

	@Autowired
	com.lrj.commerce.benefit.coupon.api.CouponApi campaignCoupons;

	@Autowired
	com.lrj.commerce.journey.application.JourneyService journeyService;

	@Autowired
	org.springframework.transaction.PlatformTransactionManager transactions;

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	private final JsonMapper json = JsonMapper.builder().findAndAddModules().build();

	private String tenant, admin, member, other;

	record Reply(int status, JsonNode body) {
	}

	@BeforeEach
	void identities() {
		tenant = "t-" + UUID.randomUUID();
		admin = token(tenant, "admin", "ADMIN");
		member = token(tenant, "buyer", "MEMBER");
		other = token("other-" + UUID.randomUUID(), "buyer", "MEMBER");
	}

	private String token(String tenant, String actor, String role) {
		String token = UUID.randomUUID() + "-" + UUID.randomUUID();
		jdbc.update("INSERT INTO platform_credential(token_hash,tenant_id,actor_id,role,expires_at) VALUES(?,?,?,?,?)",
				JsonCodec.hash(token), tenant, actor, role, java.sql.Timestamp.from(Instant.now().plusSeconds(3600)));
		return token;
	}

	private Reply call(String method, String path, String token, String key, Object body) throws Exception {
		return callAt(port, method, path, token, key, body);
	}

	private Reply callAt(int targetPort, String method, String path, String token, String key, Object body)
			throws Exception {
		var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + targetPort + path))
			.timeout(Duration.ofSeconds(15));
		if (token != null)
			req.header("Authorization", "Bearer " + token);
		if (key != null)
			req.header("Idempotency-Key", key);
		if (body != null)
			req.header("Content-Type", "application/json");
		req.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
				: HttpRequest.BodyPublishers.ofString(JsonCodec.write(body)));
		var reply = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
		return new Reply(reply.statusCode(), json.readTree(reply.body()));
	}

	private JsonNode post(String path, String token, String key, Object body) throws Exception {
		var r = call("POST", path, token, key, body);
		assertEquals(200, r.status(), r.body().toString());
		return r.body();
	}

	private void seed() throws Exception {
		post("/v1/admin/members", admin, "member",
				Map.of("memberId", "m1", "actorId", "buyer", "displayName", "测试会员", "memberLevel", "VIP"));
		post("/v1/admin/merchants", admin, "merchant", Map.of("merchantId", "merchant1", "name", "测试商家"));
		post("/v1/admin/stores", admin, "store",
				Map.of("storeId", "store1", "merchantId", "merchant1", "name", "测试店铺"));
		post("/v1/admin/skus", admin, "sku",
				Map.of("skuId", "sku1", "storeId", "store1", "title", "测试商品", "unitPrice", "25.00"));
	}

	private Map<String, Object> draft(String id, long version, String discount) {
		return Map.of("campaignId", id, "version", version, "storeId", "store1", "name", "会员活动", "validFrom",
				Instant.now().minusSeconds(60).toString(), "validTo", Instant.now().plusSeconds(3600).toString(),
				"minimumSpend", "20.00", "discountAmount", discount, "rule", Map.of("kind", "COMPARE", "field",
						"memberLevel", "operator", "EQ", "valueType", "TEXT", "value", "VIP"));
	}

	private Object basket(int quantity) {
		return Map.of("storeId", "store1", "items", List.of(Map.of("skuId", "sku1", "quantity", quantity)));
	}

	@Test
	void expiredPublishedCampaignsDoNotConsumeTheLiveCandidateBound() throws Exception {
		seed();
		post("/v1/admin/campaigns", admin, "live-create", draft("live", 1, "3.00"));
		post("/v1/admin/campaigns/live/1/publish", admin, "live-publish", Map.of("expectedVersion", 0));
		String rule = jdbc.queryForObject(
				"SELECT rule_json FROM marketing_campaign WHERE tenant_id=? AND campaign_id='live'", String.class, tenant);
		var old = new ArrayList<Object[]>();
		for (int i = 0; i < 101; i++)
			old.add(new Object[] { tenant, "expired-" + i, 1, "store1", "merchant1", "历史活动",
					java.sql.Timestamp.from(Instant.now().minusSeconds(7200)),
					java.sql.Timestamp.from(Instant.now().minusSeconds(3600)), "20.00", "1.00", rule,
					"PUBLISHED" });
		jdbc.batchUpdate("INSERT INTO marketing_campaign(tenant_id,campaign_id,version,store_id,merchant_id,name,"
				+ "valid_from,valid_to,minimum_spend,discount_amount,rule_json,status) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
				old);
		var quote = post("/v1/quotes", member, "filtered-quote", basket(2));
		assertEquals("live", quote.path("campaign").path("campaignId").asString());
	}

	@Test
	void competitivePreviewAndQuoteChooseTheSameBestOfWinner() throws Exception {
		seed();
		post("/v1/admin/campaigns", admin, "best-a-create", draft("best-a", 1, "3.00"));
		post("/v1/admin/campaigns/best-a/1/publish", admin, "best-a-publish", Map.of("expectedVersion", 0));
		post("/v1/admin/campaigns", admin, "best-b-create", draft("best-b", 1, "3.00"));
		post("/v1/admin/campaigns/best-b/1/publish", admin, "best-b-publish", Map.of("expectedVersion", 0));
		var preview = post("/v1/admin/campaigns/best-b/1/preview", admin, null,
				Map.of("memberId", "m1", "items", List.of(Map.of("skuId", "sku1", "quantity", 1)),
						"includePublishedCompetition", true));
		var quote = post("/v1/quotes", member, "best-of-quote", basket(1));
		assertEquals("best-a", preview.path("selected").path("campaignId").asString());
		assertEquals(preview.path("selected"), quote.path("campaign"));
		assertEquals("OUTRANKED_BEST_OF", preview.path("trace").get(1).path("reason").asString());
	}

	@Test
	void authenticationAndPermissionsFailClosed() throws Exception {
		assertEquals(401, call("GET", "/v1/me", null, null, null).status());
		assertEquals(401, call("GET", "/v1/me", "invalid", null, null).status());
		assertEquals(403, call("GET", "/v1/admin/members", member, null, null).status());
		jdbc.update("UPDATE platform_credential SET active=FALSE WHERE token_hash=?", JsonCodec.hash(member));
		assertEquals(401, call("GET", "/v1/me", member, null, null).status());
	}

	@Test
	void publishesAndPersistsAuthoritativeQuoteWithReplay() throws Exception {
		seed();
		post("/v1/admin/campaigns", admin, "campaign", draft("c1", 1, "3.00"));
		post("/v1/admin/campaigns/c1/1/publish", admin, "publish", Map.of("expectedVersion", 0));
		var q = post("/v1/quotes", member, "q1", basket(2));
		assertEquals("50.00", q.path("gross").asString());
		assertEquals("47.00", q.path("payable").asString());
		assertEquals("c1", q.path("campaign").path("campaignId").asString());
		assertEquals(q, post("/v1/quotes", member, "q1", basket(2)));
		assertEquals(q, call("GET", "/v1/quotes/" + q.path("quoteId").asString(), member, null, null).body());
		assertEquals(409, call("POST", "/v1/quotes", member, "q1", basket(3)).status());
		assertEquals(1,
				jdbc.queryForObject("SELECT COUNT(*) FROM trade_quote WHERE tenant_id=?", Integer.class, tenant));
	}

	@Test
	void tenantAndMemberOwnershipAreEnforcedInSql() throws Exception {
		seed();
		var q = post("/v1/quotes", member, "q1", basket(1));
		assertEquals(404, call("GET", "/v1/quotes/" + q.path("quoteId").asString(), other, null, null).status());
		String second = token(tenant, "other-buyer", "MEMBER");
		post("/v1/admin/members", admin, "member2",
				Map.of("memberId", "m2", "actorId", "other-buyer", "displayName", "第二会员", "memberLevel", "VIP"));
		assertEquals(404, call("GET", "/v1/quotes/" + q.path("quoteId").asString(), second, null, null).status());
		assertEquals(404, call("GET", "/v1/catalog?storeId=store1", other, null, null).status());
	}

	@Test
	void simultaneousDuplicateRequestsHaveOneEffect() throws Exception {
		seed();
		var pool = Executors.newFixedThreadPool(6);
		try {
			List<Callable<Reply>> tasks = new ArrayList<>();
			for (int i = 0; i < 6; i++)
				tasks.add(() -> call("POST", "/v1/quotes", member, "parallel", basket(1)));
			var results = pool.invokeAll(tasks);
			Set<String> ids = new HashSet<>();
			for (var future : results) {
				var result = future.get();
				assertEquals(200, result.status(), result.body().toString());
				ids.add(result.body().path("quoteId").asString());
			}
			assertEquals(1, ids.size());
			assertEquals(1,
					jdbc.queryForObject("SELECT COUNT(*) FROM trade_quote WHERE tenant_id=?", Integer.class, tenant));
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void failureRollsBackEffectsCommandAndAuditTogether() {
		var actor = new Actor(tenant, "admin", Actor.Role.ADMIN);
		assertThrows(IllegalStateException.class,
				() -> commands.run(actor, "test.rollback", "rollback", Map.of("value", 1), String.class, () -> {
					jdbc.update("INSERT INTO merchant_record(tenant_id,merchant_id,name) VALUES(?,?,?)", tenant,
							"rollback", "不应提交");
					throw new IllegalStateException("故障注入");
				}));
		assertEquals(0,
				jdbc.queryForObject("SELECT COUNT(*) FROM merchant_record WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(0,
				jdbc.queryForObject("SELECT COUNT(*) FROM platform_command WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(0,
				jdbc.queryForObject("SELECT COUNT(*) FROM platform_audit WHERE tenant_id=?", Integer.class, tenant));
	}

	@Test
	void snapshotSurvivesCampaignChangeAndNewVersionIsAtomic() throws Exception {
		seed();
		post("/v1/admin/campaigns", admin, "c1", draft("c1", 1, "3.00"));
		post("/v1/admin/campaigns/c1/1/publish", admin, "p1", Map.of("expectedVersion", 0));
		var old = post("/v1/quotes", member, "q1", basket(1));
		post("/v1/admin/campaigns", admin, "c2", draft("c1", 2, "5.00"));
		post("/v1/admin/campaigns/c1/2/publish", admin, "p2", Map.of("expectedVersion", 0));
		assertEquals("20.00", post("/v1/quotes", member, "q2", basket(1)).path("payable").asString());
		assertEquals(old, call("GET", "/v1/quotes/" + old.path("quoteId").asString(), member, null, null).body());
		assertEquals(1,
				jdbc.queryForObject("SELECT COUNT(*) FROM marketing_campaign WHERE tenant_id=? AND status='PUBLISHED'",
						Integer.class, tenant));
		assertEquals(409,
				call("POST", "/v1/admin/campaigns/c1/2/pause", admin, "bad-version", Map.of("expectedVersion", 0))
					.status());
	}

	@Test
	void rejectsPriceInjectionInvalidBodiesAndMissingKeys() throws Exception {
		seed();
		assertEquals(400, call("POST", "/v1/quotes", member, null, basket(1)).status());
		assertEquals(400, call("POST", "/v1/quotes", member, "price", Map.of("storeId", "store1", "items",
				List.of(Map.of("skuId", "sku1", "quantity", 1, "unitPrice", "0.01"))))
			.status());
		assertEquals(413, call("POST", "/v1/quotes", member, "huge", Map.of("padding", "x".repeat(66000))).status());
		assertEquals(400, call("GET", "/v1/catalog?storeId=store1&limit=101", member, null, null).status());
	}

	private void stock(String sku, int quantity) throws Exception {
		post("/v1/admin/inventory/receipts", admin, UUID.randomUUID().toString(),
				Map.of("storeId", "store1", "skuId", sku, "quantity", quantity));
	}

	private Object orderInput(JsonNode quote) {
		return Map.of("quoteId", quote.path("quoteId").asString(), "address",
				Map.of("recipient", "收货测试", "phone", "13800000000", "detail", "隔离测试地址123"));
	}

	private long stockValue(String column) {
		// 列名仅来自本测试常量，业务Mapper不允许动态客户端列名。
		return jdbc.queryForObject("SELECT " + column + " FROM inventory_stock WHERE tenant_id=? AND sku_id='sku1'",
				Long.class, tenant);
	}

	@Test
	void orderReservesOnceAndCancellationReleasesOnce() throws Exception {
		seed();
		stock("sku1", 3);
		var q = post("/v1/quotes", member, "quote", basket(2));
		var order = post("/v1/orders", member, "order", orderInput(q));
		String id = order.path("orderId").asString();
		assertEquals("PENDING_PAYMENT", order.path("status").asString());
		assertEquals(order, post("/v1/orders", member, "order", orderInput(q)));
		assertEquals(1, stockValue("available"));
		assertEquals(2, stockValue("held"));
		assertEquals(409, call("POST", "/v1/orders", member, "different-key", orderInput(q)).status());
		byte[] encrypted = jdbc.queryForObject(
				"SELECT address_cipher FROM order_record WHERE tenant_id=? AND order_id=?", byte[].class, tenant, id);
		assertFalse(new String(encrypted, java.nio.charset.StandardCharsets.UTF_8).contains("隔离测试地址"));
		assertFalse(order.has("address"));
		assertEquals("CANCELLED",
				post("/v1/orders/" + id + "/cancel", member, "cancel", null).path("status").asString());
		post("/v1/orders/" + id + "/cancel", member, "cancel-again", null);
		assertEquals(3, stockValue("available"));
		assertEquals(0, stockValue("held"));
		assertEquals(2,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type LIKE 'order.%'",
						Integer.class, tenant));
		assertEquals(404, call("GET", "/v1/orders/" + id, other, null, null).status());
		assertEquals(404, call("POST", "/v1/orders/" + id + "/cancel", other, "foreign-cancel", null).status());
	}

	@Test
	void simultaneousOrdersCannotOversellOrConsumeOneQuoteTwice() throws Exception {
		seed();
		stock("sku1", 1);
		var q1 = post("/v1/quotes", member, "q1", basket(1));
		var q2 = post("/v1/quotes", member, "q2", basket(1));
		try (var pool = Executors.newFixedThreadPool(2)) {
			var latch = new CountDownLatch(1);
			var a = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/orders", member, "o1", orderInput(q1));
			});
			var b = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/orders", member, "o2", orderInput(q2));
			});
			latch.countDown();
			assertEquals(List.of(200, 409),
					java.util.stream.Stream.of(a.get(), b.get()).map(Reply::status).sorted().toList());
		}
		assertEquals(0, stockValue("available"));
		assertEquals(1, stockValue("held"));
		assertEquals(1,
				jdbc.queryForObject("SELECT COUNT(*) FROM order_record WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(1,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM trade_quote WHERE tenant_id=? AND consumed_order_id IS NOT NULL",
						Integer.class, tenant));
	}

	@Test
	void parallelDuplicateOrdersHaveSingleEffect() throws Exception {
		seed();
		stock("sku1", 10);
		var q = post("/v1/quotes", member, "q", basket(2));
		try (var pool = Executors.newFixedThreadPool(4)) {
			List<Callable<Reply>> tasks = new ArrayList<>();
			for (int i = 0; i < 4; i++)
				tasks.add(() -> call("POST", "/v1/orders", member, "o", orderInput(q)));
			Set<String> ids = new HashSet<>();
			for (var f : pool.invokeAll(tasks)) {
				var r = f.get();
				assertEquals(200, r.status(), r.body().toString());
				ids.add(r.body().path("orderId").asString());
			}
			assertEquals(1, ids.size());
		}
		assertEquals(8, stockValue("available"));
		assertEquals(2, stockValue("held"));
	}

	@Test
	void failedSecondSkuRollsBackQuoteInventoryAndEventsAndCanRetry() throws Exception {
		seed();
		stock("sku1", 2);
		post("/v1/admin/skus", admin, "sku2",
				Map.of("skuId", "sku2", "storeId", "store1", "title", "第二商品", "unitPrice", "10.00"));
		var q = post("/v1/quotes", member, "q", Map.of("storeId", "store1", "items",
				List.of(Map.of("skuId", "sku1", "quantity", 1), Map.of("skuId", "sku2", "quantity", 1))));
		assertEquals(409, call("POST", "/v1/orders", member, "o", orderInput(q)).status());
		assertEquals(2, stockValue("available"));
		assertEquals(0, stockValue("held"));
		for (String table : List.of("order_record", "inventory_hold"))
			assertEquals(0,
					jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(0,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type LIKE 'order.%'",
						Integer.class, tenant));
		assertEquals(0,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM trade_quote WHERE tenant_id=? AND consumed_order_id IS NOT NULL",
						Integer.class, tenant));
		stock("sku2", 1);
		assertEquals("PENDING_PAYMENT", post("/v1/orders", member, "o", orderInput(q)).path("status").asString());
	}

	@Test
	void expiredQuoteCannotBecomeOrder() throws Exception {
		seed();
		stock("sku1", 1);
		var q = post("/v1/quotes", member, "q", basket(1));
		jdbc.update("UPDATE trade_quote SET expires_at=? WHERE tenant_id=?",
				java.sql.Timestamp.from(Instant.now().minusSeconds(10)), tenant);
		assertEquals(409, call("POST", "/v1/orders", member, "o", orderInput(q)).status());
		assertEquals(1, stockValue("available"));
	}

	@Test
	void freeOrderConfirmsStockWithoutPretendingChannelPayment() throws Exception {
		seed();
		stock("sku1", 1);
		post("/v1/admin/campaigns", admin, "campaign", draft("free", 1, "100.00"));
		post("/v1/admin/campaigns/free/1/publish", admin, "publish", Map.of("expectedVersion", 0));
		var q = post("/v1/quotes", member, "q", basket(1));
		var order = post("/v1/orders", member, "o", orderInput(q));
		assertEquals("PAID", order.path("status").asString());
		assertEquals("NO_PAYMENT_REQUIRED", order.path("paymentKind").asString());
		assertEquals(0, stockValue("held"));
		assertEquals(1, stockValue("sold"));
		assertEquals(0,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='order.paid.v1'",
						Integer.class, tenant));
		assertEquals(409,
				call("POST", "/v1/orders/" + order.path("orderId").asString() + "/cancel", member, "cancel", null)
					.status());
	}

	private JsonNode pendingOrder() throws Exception {
		seed();
		stock("sku1", 2);
		return post("/v1/orders", member, "o", orderInput(post("/v1/quotes", member, "q", basket(1))));
	}

	private JsonNode startPayment(JsonNode order) throws Exception {
		return post("/v1/orders/" + order.path("orderId").asString() + "/payments", member, "pay", null);
	}

	private void sandbox(JsonNode payment, String status) throws Exception {
		post("/v1/admin/sandbox/payments/" + payment.path("paymentId").asString() + "/fact", admin, "fact-" + status,
				Map.of("status", status));
	}

	private JsonNode reconcile(JsonNode order) throws Exception {
		return post("/v1/orders/" + order.path("orderId").asString() + "/payment/reconcile", member, null, null);
	}

	private JsonNode readOrder(JsonNode order) throws Exception {
		return call("GET", "/v1/orders/" + order.path("orderId").asString(), member, null, null).body();
	}

	private void pump() throws Exception {
		post("/v1/admin/events/pump", admin, null, null);
	}

	@Test
	void unknownPaymentCancellationKeepsStockUntilClosedProof() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		assertEquals("UNKNOWN", payment.path("status").asString());
		assertEquals(payment, startPayment(order));
		post("/v1/orders/" + order.path("orderId").asString() + "/cancel", member, "cancel", null);
		assertEquals("UNKNOWN", reconcile(order).path("status").asString());
		pump();
		assertEquals("CLOSING", readOrder(order).path("status").asString());
		assertEquals(1, stockValue("held"));
		sandbox(payment, "OPEN");
		assertEquals("CLOSED", reconcile(order).path("status").asString());
		pump();
		assertEquals("CANCELLED", readOrder(order).path("status").asString());
		assertEquals(2, stockValue("available"));
		assertEquals(0, stockValue("held"));
		assertEquals(409, call("POST", "/v1/admin/sandbox/payments/" + payment.path("paymentId").asString() + "/fact",
				admin, "late-paid", Map.of("status", "PAID"))
			.status());
	}

	@Test
	void paidFactWinsCancellationAndDuplicateDeliveryDoesNotDoubleConfirm() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		post("/v1/orders/" + order.path("orderId").asString() + "/cancel", member, "cancel", null);
		assertEquals("PAID", reconcile(order).path("status").asString());
		pump();
		assertNotNull(jdbc.queryForObject("SELECT evidence_json FROM payment_attempt WHERE tenant_id=?", String.class,
				tenant));
		var paid = readOrder(order);
		assertEquals("PAID", paid.path("status").asString());
		assertEquals(1, stockValue("sold"));
		// 模拟ACK丢失导致同事件再次可见，Inbox必须阻止重复业务副作用。
		jdbc.update(
				"UPDATE platform_event SET status='PENDING',available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='payment.paid.v1'",
				tenant);
		pump();
		assertEquals(paid, readOrder(order));
		assertEquals(1, stockValue("sold"));
		assertEquals(1,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM platform_inbox WHERE tenant_id=? AND consumer_id='order-payment-v1'",
						Integer.class, tenant));
		assertEquals(409, call("POST", "/v1/admin/sandbox/payments/" + payment.path("paymentId").asString() + "/fact",
				admin, "reverse", Map.of("status", "OPEN"))
			.status());
	}

	@Test
	void paidProviderFactConvergesAfterExpiryStartsClosing() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		jdbc.update("UPDATE order_record SET expires_at=? WHERE tenant_id=?",
				java.sql.Timestamp.from(Instant.now().minusSeconds(1)), tenant);
		// 渠道已收款但本地尚无付款事件；到期只能进入 CLOSING，不能释放预占。
		assertEquals(1, post("/v1/admin/orders/expire", admin, "expire-paid-provider", null).asInt());
		assertEquals("CLOSING", readOrder(order).path("status").asString());
		assertEquals(1, stockValue("held"));
		assertEquals("PAID", reconcile(order).path("status").asString());
		pump();
		assertEquals("PAID", readOrder(order).path("status").asString());
		assertEquals(1, stockValue("sold"));
		assertEquals(0, stockValue("held"));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='payment.paid.v1'",
				Integer.class, tenant));
	}

	@Test
	void twoLiveAppInstancesConvergeWhenExpiryRacesWithPaidRecheck() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		jdbc.update("UPDATE order_record SET expires_at=? WHERE tenant_id=?",
				java.sql.Timestamp.from(Instant.now().minusSeconds(1)), tenant);
		// 第二个 Spring 上下文持有独立连接池与事务管理器，模拟另一应用实例共享同一 MySQL。
		try (var second = new SpringApplicationBuilder(CommerceApplication.class)
			.initializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
					"phase5-second-instance", Map.of("server.port", 0, "spring.datasource.url",
							System.getenv("COMMERCE_TEST_DB_URL"), "spring.datasource.username",
							System.getenv("COMMERCE_DB_USER"), "spring.datasource.password",
							System.getenv("COMMERCE_DB_PASSWORD"), "commerce.sandbox-enabled", true,
							"commerce.workers-enabled", false))))
			.run()) {
			int secondPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
			try (var pool = Executors.newFixedThreadPool(2)) {
				var start = new CountDownLatch(1);
				var expiry = pool.submit(() -> {
					start.await();
					return call("POST", "/v1/admin/orders/expire", admin, "two-instance-expire", null);
				});
				var recheck = pool.submit(() -> {
					start.await();
					return callAt(secondPort, "POST", "/v1/orders/" + order.path("orderId").asString()
							+ "/payment/reconcile", member, null, null);
				});
				start.countDown();
				assertEquals(200, expiry.get().status());
				assertEquals("PAID", recheck.get().body().path("status").asString());
			}
		}
		pump();
		assertEquals("PAID", readOrder(order).path("status").asString());
		assertEquals(1, stockValue("sold"));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='payment.paid.v1'",
				Integer.class, tenant));
	}

	@Test
	void staleExpiryCannotCancelAnAlreadyPaidOrder() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		reconcile(order);
		pump();
		assertEquals("PAID", readOrder(order).path("status").asString());
		jdbc.update("UPDATE order_record SET expires_at=? WHERE tenant_id=?",
				java.sql.Timestamp.from(Instant.now().minusSeconds(1)), tenant);
		assertEquals(0, post("/v1/admin/orders/expire", admin, "stale-expire", null).asInt());
		assertEquals("PAID", readOrder(order).path("status").asString());
		assertEquals(1, stockValue("sold"));
	}

	@Test
	void paymentAfterExpiryDiscoveryIsRecheckedUnderTheOrderLock() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		String id = order.path("orderId").asString();
		jdbc.update("UPDATE order_record SET expires_at=? WHERE tenant_id=?",
				java.sql.Timestamp.from(Instant.now().minusSeconds(1)), tenant);
		int poisonBudget = RetryPolicy.POISON.budget();
		int transientBudget = RetryPolicy.TRANSIENT.budget();
		// 候选发现不加锁；在它与事务内领取之间，可信付款先提交。
		assertEquals(id, orderMapper.expiryDue(tenant, Instant.now(), 1, poisonBudget, transientBudget).getFirst()
			.orderId());
		reconcile(order);
		pump();
		var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
		assertNull(transaction.execute(status -> orderMapper.expiredLock(tenant, id, Instant.now(), poisonBudget,
				transientBudget)));
		assertEquals(0, post("/v1/admin/orders/expire", admin, "post-payment-expire", null).asInt());
		assertEquals("PAID", readOrder(order).path("status").asString());
	}

	@Test
	void concurrentPaymentRechecksCommitOnePaidFactAndOneOrderEffect() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		try (var pool = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var first = pool.submit(() -> {
				start.await();
				return reconcile(order);
			});
			var second = pool.submit(() -> {
				start.await();
				return reconcile(order);
			});
			start.countDown();
			assertEquals("PAID", first.get().path("status").asString());
			assertEquals("PAID", second.get().path("status").asString());
		}
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='payment.paid.v1'",
				Integer.class, tenant));
		pump();
		assertEquals("PAID", readOrder(order).path("status").asString());
		assertEquals(1, stockValue("sold"));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_inbox WHERE tenant_id=? AND consumer_id='order-payment-v1'",
				Integer.class, tenant));
	}

	@Test
	void oneProviderTransactionCannotPayTwoOrders() throws Exception {
		var firstOrder = pendingOrder();
		var secondQuote = post("/v1/quotes", member, "second-quote", basket(1));
		var secondOrder = post("/v1/orders", member, "second-order", orderInput(secondQuote));
		var firstPayment = startPayment(firstOrder);
		var secondPayment = post("/v1/orders/" + secondOrder.path("orderId").asString() + "/payments", member,
				"second-payment", null);
		sandbox(firstPayment, "PAID");
		post("/v1/admin/sandbox/payments/" + secondPayment.path("paymentId").asString() + "/fact", admin,
				"second-paid", Map.of("status", "PAID"));
		String transaction = jdbc.queryForObject(
				"SELECT transaction_id FROM payment_sandbox_ledger WHERE tenant_id=? AND payment_id=?", String.class,
				tenant, firstPayment.path("paymentId").asString());
		jdbc.update("UPDATE payment_sandbox_ledger SET transaction_id=? WHERE tenant_id=? AND payment_id=?", transaction,
				tenant, secondPayment.path("paymentId").asString());
		assertEquals("PAID", reconcile(firstOrder).path("status").asString());
		assertEquals(409, call("POST", "/v1/orders/" + secondOrder.path("orderId").asString()
				+ "/payment/reconcile", member, null, null).status());
		assertEquals("UNKNOWN", jdbc.queryForObject(
				"SELECT status FROM payment_attempt WHERE tenant_id=? AND payment_id=?", String.class, tenant,
				secondPayment.path("paymentId").asString()));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='payment.paid.v1'",
				Integer.class, tenant));
	}

	@Test
	void concurrentCloseAndChannelSuccessChooseOneDurableFact() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		sandbox(payment, "OPEN");
		post("/v1/orders/" + order.path("orderId").asString() + "/cancel", member, "cancel", null);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var latch = new CountDownLatch(1);
			var close = pool.submit(() -> {
				latch.await();
				return reconcile(order);
			});
			var paid = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/admin/sandbox/payments/" + payment.path("paymentId").asString() + "/fact",
						admin, "race-paid", Map.of("status", "PAID"));
			});
			latch.countDown();
			var result = close.get();
			assertTrue(Set.of("PAID", "CLOSED").contains(result.path("status").asString()));
			assertTrue(Set.of(200, 409).contains(paid.get().status()));
		}
		reconcile(order);
		pump();
		String state = readOrder(order).path("status").asString();
		assertTrue(Set.of("PAID", "CANCELLED").contains(state));
		assertEquals(0, stockValue("held"));
		assertEquals(state.equals("PAID") ? 1 : 0, stockValue("sold"));
	}

	@Test
	void mismatchedChannelAmountCannotGeneratePaidEvent() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		jdbc.update("UPDATE payment_sandbox_ledger SET amount=amount+1 WHERE tenant_id=?", tenant);
		assertEquals(409, call("POST", "/v1/orders/" + order.path("orderId").asString() + "/payment/reconcile", member,
				null, null)
			.status());
		assertEquals(0,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='payment.paid.v1'",
						Integer.class, tenant));
		assertEquals(1, stockValue("held"));
	}

	@Test
	void failedConsumerRollsBackInboxThenIsolatesAndAuditedRetryRecovers() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		reconcile(order);
		String event = jdbc.queryForObject(
				"SELECT event_id FROM platform_event WHERE tenant_id=? AND event_type='payment.paid.v1'", String.class,
				tenant);
		String original = jdbc.queryForObject("SELECT payload_json FROM platform_event WHERE event_id=?", String.class,
				event);
		jdbc.update("UPDATE platform_event SET payload_json='{}' WHERE event_id=?", event);
		for (int i = 0; i < 5; i++) {
			pump();
			jdbc.update("UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(3) WHERE event_id=?", event);
		}
		assertEquals("ISOLATED",
				jdbc.queryForObject("SELECT status FROM platform_event WHERE event_id=?", String.class, event));
		assertEquals(0,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM platform_inbox WHERE tenant_id=? AND consumer_id='order-payment-v1'",
						Integer.class, tenant));
		assertEquals(1, stockValue("held"));
		assertEquals(403, call("POST", "/v1/admin/events/" + event + "/retry", member, "retry", null).status());
		jdbc.update("UPDATE platform_event SET payload_json=? WHERE event_id=?", original, event);
		post("/v1/admin/events/" + event + "/retry", admin, "retry", null);
		pump();
		assertEquals("PAID", readOrder(order).path("status").asString());
		assertEquals(1, stockValue("sold"));
	}

	@Test
	void crashAfterPaymentConsumerEffectBeforeCommitRollsBackAndRetriesOnce() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		reconcile(order);
		var failing = new EventHandler() {
			public String consumer() {
				return paymentService.consumer();
			}

			public Set<String> types() {
				return Set.of("payment.paid.v1");
			}

			public void handle(Event event) {
				paymentService.handle(event);
				throw new IllegalStateException("injected crash before consumer commit");
			}
		};
		var dispatcher = new EventDispatcher(eventMapper, List.of(failing), transactions, commands);
		assertEquals(0, dispatcher.pump(new Actor(tenant, "admin", Actor.Role.ADMIN)));
		assertEquals("PAYMENT_IN_PROGRESS", readOrder(order).path("status").asString());
		assertEquals(1, stockValue("held"));
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_inbox WHERE tenant_id=? AND consumer_id='order-payment-v1'",
				Integer.class, tenant));
		jdbc.update("UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='payment.paid.v1'",
				tenant);
		pump();
		assertEquals("PAID", readOrder(order).path("status").asString());
		assertEquals(1, stockValue("sold"));
		jdbc.update("UPDATE platform_event SET status='PENDING',available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='payment.paid.v1'",
				tenant);
		pump();
		assertEquals(1, stockValue("sold"));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_inbox WHERE tenant_id=? AND consumer_id='order-payment-v1'",
				Integer.class, tenant));
	}

	@Test
	void expiredPaymentInProgressCannotReleaseUnknownFunds() throws Exception {
		var order = pendingOrder();
		startPayment(order);
		jdbc.update("UPDATE order_record SET expires_at=? WHERE tenant_id=?",
				java.sql.Timestamp.from(Instant.now().minusSeconds(1)), tenant);
		assertEquals(1, post("/v1/admin/orders/expire", admin, "expire", null).asInt());
		assertEquals("CLOSING", readOrder(order).path("status").asString());
		assertEquals(1, stockValue("held"));
		assertEquals(0, post("/v1/admin/orders/expire", admin, "expire-again", null).asInt());
	}

	@Test
	void paymentAndSandboxPermissionsAreTenantScoped() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		String id = order.path("orderId").asString();
		assertEquals(404, call("GET", "/v1/orders/" + id + "/payment", other, null, null).status());
		assertEquals(404, call("POST", "/v1/orders/" + id + "/payment/reconcile", other, null, null).status());
		assertEquals(403, call("POST", "/v1/admin/sandbox/payments/" + payment.path("paymentId").asString() + "/fact",
				member, "fact", Map.of("status", "PAID"))
			.status());
	}

	@Test
	void backgroundReconciliationRecoversAfterChannelSuccessWithoutClientReturn() throws Exception {
		var order = pendingOrder();
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		// 后台有界轮转，模拟浏览器关闭后没有调用reconcile；不改动其他租户数据。
		for (int i = 0; i < 100; i++) {
			payments.tick();
			String status = jdbc.queryForObject("SELECT status FROM payment_attempt WHERE tenant_id=?", String.class,
					tenant);
			if (status.equals("PAID"))
				break;
		}
		assertEquals("PAID",
				jdbc.queryForObject("SELECT status FROM payment_attempt WHERE tenant_id=?", String.class, tenant));
		pump();
		assertEquals("PAID", readOrder(order).path("status").asString());
	}

	@Test
	void expiryWithoutPaymentCanReleaseAndRejectLaterPayment() throws Exception {
		var order = pendingOrder();
		jdbc.update("UPDATE order_record SET expires_at=? WHERE tenant_id=?",
				java.sql.Timestamp.from(Instant.now().minusSeconds(1)), tenant);
		post("/v1/admin/orders/expire", admin, "expire", null);
		assertEquals("CANCELLED", readOrder(order).path("status").asString());
		assertEquals(2, stockValue("available"));
		assertEquals(409,
				call("POST", "/v1/orders/" + order.path("orderId").asString() + "/payments", member, "late", null)
					.status());
	}

	private JsonNode payOrder(JsonNode order) throws Exception {
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		reconcile(order);
		pump();
		return readOrder(order);
	}

	private void ship(JsonNode order) throws Exception {
		post("/v1/admin/fulfillments/" + order.path("orderId").asString() + "/ship", admin, "ship",
				Map.of("trackingNo", "SANDBOX-TRACKING"));
	}

	private JsonNode requestReturn(JsonNode order, int quantity, String key) throws Exception {
		return post("/v1/aftersales", member, key, Map.of("orderId", order.path("orderId").asString(), "reason",
				"隔离测试退货", "items", List.of(Map.of("skuId", "sku1", "quantity", quantity))));
	}

	private JsonNode approve(JsonNode request, String key) throws Exception {
		return post("/v1/admin/aftersales/" + request.path("caseId").asString() + "/approve", admin, key, null);
	}

	private JsonNode finishRefund(JsonNode request, String key) throws Exception {
		String refund = request.path("refundId").asString();
		post("/v1/admin/sandbox/refunds/" + refund + "/success", admin, key, null);
		post("/v1/admin/refunds/" + refund + "/reconcile", admin, null, null);
		pump();
		return call("GET", "/v1/aftersales/" + request.path("caseId").asString(), member, null, null).body();
	}

	@Test
	void shipmentAndDeliveryAdvanceOrderAndRejectTrackingReplacement() throws Exception {
		var order = payOrder(pendingOrder());
		ship(order);
		assertEquals("FULFILLING", readOrder(order).path("status").asString());
		assertEquals(409, call("POST", "/v1/admin/fulfillments/" + order.path("orderId").asString() + "/ship", admin,
				"replace", Map.of("trackingNo", "CHANGED"))
			.status());
		post("/v1/admin/fulfillments/" + order.path("orderId").asString() + "/deliver", admin, "deliver", null);
		assertEquals("COMPLETED", readOrder(order).path("status").asString());
		post("/v1/admin/fulfillments/" + order.path("orderId").asString() + "/deliver", admin, "duplicate-delivery",
				null);
		assertEquals(404,
				call("GET", "/v1/orders/" + order.path("orderId").asString() + "/fulfillment", other, null, null)
					.status());
	}

	@Test
	void duplicateConcurrentShipmentCommandsCreateOneShipmentTransition() throws Exception {
		var order = payOrder(pendingOrder());
		String id = order.path("orderId").asString();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var first = pool.submit(() -> {
				start.await();
				return call("POST", "/v1/admin/fulfillments/" + id + "/ship", admin, "ship-first",
						Map.of("trackingNo", "ONE-TRACK"));
			});
			var second = pool.submit(() -> {
				start.await();
				return call("POST", "/v1/admin/fulfillments/" + id + "/ship", admin, "ship-second",
						Map.of("trackingNo", "ONE-TRACK"));
			});
			start.countDown();
			assertEquals(200, first.get().status());
			assertEquals(200, second.get().status());
		}
		assertEquals(1, jdbc.queryForObject("SELECT version FROM fulfillment_record WHERE tenant_id=? AND order_id=?",
				Integer.class, tenant, id));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='order.fulfilling.v1'",
				Integer.class, tenant));
		assertEquals("FULFILLING", readOrder(order).path("status").asString());
	}

	@Test
	void beforeShipmentRefundBlocksShippingAndWaitsForRealRefundFact() throws Exception {
		var order = payOrder(pendingOrder());
		var request = requestReturn(order, 1, "r");
		String id = request.path("caseId").asString();
		assertEquals(409, call("POST", "/v1/admin/fulfillments/" + order.path("orderId").asString() + "/ship", admin,
				"blocked", Map.of("trackingNo", "TRACK"))
			.status());
		var refunding = approve(request, "approve");
		assertEquals("REFUNDING", refunding.path("status").asString());
		assertEquals(2, stockValue("available"));
		assertEquals(0, stockValue("sold"));
		var unknown = post("/v1/admin/refunds/" + refunding.path("refundId").asString() + "/reconcile", admin, null,
				null);
		assertEquals("UNKNOWN", unknown.path("status").asString());
		assertEquals("REFUNDING",
				call("GET", "/v1/aftersales/" + id, member, null, null).body().path("status").asString());
		assertEquals("COMPLETED", finishRefund(refunding, "refund").path("status").asString());
		assertEquals("CANCELLED",
				call("GET", "/v1/orders/" + order.path("orderId").asString() + "/fulfillment", member, null, null)
					.body()
					.path("status")
					.asString());
		// 模拟退款事件重复投递，库存与累计金额都不能再增加。
		jdbc.update(
				"UPDATE platform_event SET status='PENDING',available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='refund.succeeded.v1'",
				tenant);
		pump();
		assertEquals(2, stockValue("available"));
		assertEquals(1,
				jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund WHERE tenant_id=?", Integer.class, tenant));
	}

	@Test
	void shipmentAndAftersaleRaceKeepsOneConsistentFulfillmentState() throws Exception {
		var order = payOrder(pendingOrder());
		String id = order.path("orderId").asString();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var shipment = pool.submit(() -> {
				start.await();
				return call("POST", "/v1/admin/fulfillments/" + id + "/ship", admin, "racing-ship",
						Map.of("trackingNo", "RACING-TRACK"));
			});
			var aftersale = pool.submit(() -> {
				start.await();
				return call("POST", "/v1/aftersales", member, "racing-return",
						Map.of("orderId", id, "reason", "并发退货", "items",
								List.of(Map.of("skuId", "sku1", "quantity", 1))));
			});
			start.countDown();
			var shipped = shipment.get();
			var returned = aftersale.get();
			assertEquals(200, returned.status(), returned.body().toString());
			assertTrue(Set.of(200, 409).contains(shipped.status()));
			var fulfillment = call("GET", "/v1/orders/" + id + "/fulfillment", member, null, null).body();
			assertTrue(fulfillment.path("blocked").asBoolean());
			assertEquals(shipped.status() == 200 ? "SHIPPED" : "READY", fulfillment.path("status").asString());
			assertEquals(shipped.status() == 200, returned.body().path("returnRequired").asBoolean());
		}
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM aftersales_case WHERE tenant_id=?", Integer.class,
				tenant));
	}

	@Test
	void partialReturnsUseOriginalAllocationAndNeverOverRefund() throws Exception {
		seed();
		stock("sku1", 3);
		post("/v1/admin/campaigns", admin, "campaign", draft("partial", 1, "1.00"));
		post("/v1/admin/campaigns/partial/1/publish", admin, "publish", Map.of("expectedVersion", 0));
		var order = post("/v1/orders", member, "o", orderInput(post("/v1/quotes", member, "q", basket(3))));
		payOrder(order);
		ship(order);
		String[] amounts = { "24.66", "24.67", "24.67" };
		for (int n = 0; n < 3; n++) {
			var request = requestReturn(order, 1, "return-" + n);
			assertEquals(amounts[n], request.path("refundAmount").asString());
			var approved = approve(request, "approve-" + n);
			assertEquals("WAIT_RETURN", approved.path("status").asString());
			assertEquals(3 - n, stockValue("sold"));
			var refunding = post("/v1/admin/aftersales/" + request.path("caseId").asString() + "/receive-return", admin,
					"receive-" + n, null);
			assertEquals("REFUNDING", refunding.path("status").asString());
			assertEquals("COMPLETED", finishRefund(refunding, "refund-" + n).path("status").asString());
		}
		assertEquals(new java.math.BigDecimal("74.00"), jdbc.queryForObject(
				"SELECT SUM(amount) FROM payment_refund WHERE tenant_id=?", java.math.BigDecimal.class, tenant));
		assertEquals(3, stockValue("available"));
		assertEquals(0, stockValue("sold"));
		assertEquals(400,
				call("POST", "/v1/aftersales", member, "over-return",
						Map.of("orderId", order.path("orderId").asString(), "reason", "多退", "items",
								List.of(Map.of("skuId", "sku1", "quantity", 1))))
					.status());
	}

	@Test
	void onlyOneActiveCaseAndRejectionReleasesShipmentHold() throws Exception {
		var order = payOrder(pendingOrder());
		var request = requestReturn(order, 1, "r");
		assertEquals(409,
				call("POST", "/v1/aftersales", member, "second", Map.of("orderId", order.path("orderId").asString(),
						"reason", "重复申请", "items", List.of(Map.of("skuId", "sku1", "quantity", 1))))
					.status());
		post("/v1/admin/aftersales/" + request.path("caseId").asString() + "/reject", admin, "reject", null);
		ship(order);
		assertEquals("FULFILLING", readOrder(order).path("status").asString());
		assertEquals(0,
				jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund WHERE tenant_id=?", Integer.class, tenant));
	}

	@Test
	void returnApprovalCannotBypassPhysicalReceiptAndRejectsOverReturn() throws Exception {
		var order = payOrder(pendingOrder());
		ship(order);
		var request = requestReturn(order, 1, "r");
		assertEquals(409, call("POST", "/v1/admin/aftersales/" + request.path("caseId").asString() + "/receive-return",
				admin, "early", null)
			.status());
		approve(request, "approve");
		assertEquals(1, stockValue("sold"));
		assertEquals(0,
				jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(403, call("POST", "/v1/admin/aftersales/" + request.path("caseId").asString() + "/receive-return",
				member, "forged", null)
			.status());
		assertEquals(404,
				call("GET", "/v1/aftersales/" + request.path("caseId").asString(), other, null, null).status());
	}

	@Test
	void zeroValueReturnCompletesWithoutChannelRefund() throws Exception {
		seed();
		stock("sku1", 1);
		post("/v1/admin/campaigns", admin, "c", draft("free", 1, "100.00"));
		post("/v1/admin/campaigns/free/1/publish", admin, "p", Map.of("expectedVersion", 0));
		var order = post("/v1/orders", member, "o", orderInput(post("/v1/quotes", member, "q", basket(1))));
		var request = requestReturn(order, 1, "r");
		var approved = approve(request, "a");
		pump();
		assertEquals("COMPLETED",
				call("GET", "/v1/aftersales/" + request.path("caseId").asString(), member, null, null).body()
					.path("status")
					.asString());
		assertEquals("NO_PAYMENT_REQUIRED",
				jdbc.queryForObject("SELECT provider FROM payment_refund WHERE tenant_id=?", String.class, tenant));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund_sandbox WHERE tenant_id=?",
				Integer.class, tenant));
		assertEquals(1, stockValue("available"));
	}

	@Test
	void refundEvidenceMismatchKeepsCaseOpenAndDoesNotRestoreInventoryTwice() throws Exception {
		var order = payOrder(pendingOrder());
		var approved = approve(requestReturn(order, 1, "r"), "a");
		String id = approved.path("refundId").asString();
		post("/v1/admin/sandbox/refunds/" + id + "/success", admin, "success", null);
		jdbc.update("UPDATE payment_refund_sandbox SET amount=amount+1 WHERE tenant_id=?", tenant);
		assertEquals(409, call("POST", "/v1/admin/refunds/" + id + "/reconcile", admin, null, null).status());
		assertEquals("UNKNOWN",
				jdbc.queryForObject("SELECT status FROM payment_refund WHERE tenant_id=?", String.class, tenant));
		assertEquals(2, stockValue("available"));
		assertEquals("REFUNDING",
				call("GET", "/v1/aftersales/" + approved.path("caseId").asString(), member, null, null).body()
					.path("status")
					.asString());
	}

	@Test
	void concurrentRefundReservationsCannotExceedReceivedAmount() throws Exception {
		var order = payOrder(pendingOrder());
		String orderId = order.path("orderId").asString();
		var actor = new Actor(tenant, "admin", Actor.Role.ADMIN);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var latch = new CountDownLatch(1);
			List<Callable<Boolean>> tasks = new ArrayList<>();
			for (int n = 0; n < 2; n++) {
				String caseId = "capacity-" + n;
				tasks.add(() -> {
					latch.await();
					try {
						commands.run(actor, "test.refund.capacity", caseId, caseId, String.class, () -> {
							refunds.request(tenant, caseId, orderId, "25.00");
							return "ok";
						});
						return true;
					}
					catch (com.lrj.commerce.kernel.DomainException conflict) {
						return false;
					}
				});
			}
			var first = pool.submit(tasks.get(0));
			var second = pool.submit(tasks.get(1));
			latch.countDown();
			assertNotEquals(first.get(), second.get());
		}
		assertEquals(new java.math.BigDecimal("25.00"), jdbc.queryForObject(
				"SELECT refund_reserved FROM payment_attempt WHERE tenant_id=?", java.math.BigDecimal.class, tenant));
		assertEquals(1,
				jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund WHERE tenant_id=?", Integer.class, tenant));
	}

	@Test
	void duplicateConcurrentRefundRequestsReserveThePaymentOnlyOnce() throws Exception {
		var order = payOrder(pendingOrder());
		String id = order.path("orderId").asString();
		var actor = new Actor(tenant, "admin", Actor.Role.ADMIN);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var first = pool.submit(() -> {
				start.await();
				return commands.run(actor, "test.refund.duplicate", "first", id, String.class,
						() -> refunds.request(tenant, "same-case", id, "25.00").refundId());
			});
			var second = pool.submit(() -> {
				start.await();
				return commands.run(actor, "test.refund.duplicate", "second", id, String.class,
						() -> refunds.request(tenant, "same-case", id, "25.00").refundId());
			});
			start.countDown();
			assertEquals(first.get(), second.get());
		}
		assertEquals(new java.math.BigDecimal("25.00"), jdbc.queryForObject(
				"SELECT refund_reserved FROM payment_attempt WHERE tenant_id=?", java.math.BigDecimal.class, tenant));
		assertEquals(1,
				jdbc.queryForObject("SELECT COUNT(*) FROM payment_refund WHERE tenant_id=?", Integer.class, tenant));
	}

	@Test
	void concurrentRefundRechecksEmitOneSuccessAndCompleteOneCase() throws Exception {
		var order = payOrder(pendingOrder());
		var refunding = approve(requestReturn(order, 1, "refund-race"), "approve-race");
		String refundId = refunding.path("refundId").asString();
		post("/v1/admin/sandbox/refunds/" + refundId + "/success", admin, "provider-success", null);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var first = pool.submit(() -> {
				start.await();
				return post("/v1/admin/refunds/" + refundId + "/reconcile", admin, null, null);
			});
			var second = pool.submit(() -> {
				start.await();
				return post("/v1/admin/refunds/" + refundId + "/reconcile", admin, null, null);
			});
			start.countDown();
			assertEquals("SUCCEEDED", first.get().path("status").asString());
			assertEquals("SUCCEEDED", second.get().path("status").asString());
		}
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='refund.succeeded.v1'",
				Integer.class, tenant));
		pump();
		assertEquals("COMPLETED", call("GET", "/v1/aftersales/" + refunding.path("caseId").asString(), member,
				null, null).body().path("status").asString());
	}

	@Test
	void simultaneousAftersaleApplicationsLeaveOneActiveCase() throws Exception {
		var order = payOrder(pendingOrder());
		Object input = Map.of("orderId", order.path("orderId").asString(), "reason", "并发申请", "items",
				List.of(Map.of("skuId", "sku1", "quantity", 1)));
		try (var pool = Executors.newFixedThreadPool(2)) {
			var latch = new CountDownLatch(1);
			var a = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/aftersales", member, "r1", input);
			});
			var b = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/aftersales", member, "r2", input);
			});
			latch.countDown();
			assertEquals(List.of(200, 409),
					java.util.stream.Stream.of(a.get(), b.get()).map(Reply::status).sorted().toList());
		}
		assertEquals(1,
				jdbc.queryForObject("SELECT COUNT(*) FROM aftersales_case WHERE tenant_id=?", Integer.class, tenant));
	}

	private void audience(String id, long version, List<String> members) throws Exception {
		post("/v1/admin/audiences", admin, "audience-" + id + "-" + version,
				Map.of("audienceId", id, "version", version, "name", "可信人群", "source", "approved-test-import",
						"watermark", Instant.now().minusSeconds(1).toString(), "validUntil",
						Instant.now().plusSeconds(3600).toString(), "memberIds", members));
	}

	private Map<String, Object> governed(String id, Object policy) {
		var result = new HashMap<>(draft(id, 1, "3.00"));
		result.put("policy", policy);
		return result;
	}

	private void approveAndPublish(String id) throws Exception {
		post("/v1/admin/campaigns/" + id + "/1/submit", admin, "submit-" + id, Map.of("expectedVersion", 0));
		post("/v1/admin/campaigns/" + id + "/1/approve", admin, "approve-" + id, Map.of("expectedVersion", 1));
		post("/v1/admin/campaigns/" + id + "/1/publish", admin, "publish-" + id, Map.of("expectedVersion", 2));
	}

	@Test
	void governedCampaignRequiresApprovalAndBindsAudienceSource() throws Exception {
		seed();
		audience("vip", 1, List.of("m1"));
		post("/v1/admin/campaigns", admin, "c",
				governed("governed", Map.of("audience", Map.of("id", "vip", "version", 1))));
		assertEquals(409, call("POST", "/v1/admin/campaigns/governed/1/publish", admin, "skip-review",
				Map.of("expectedVersion", 0))
			.status());
		approveAndPublish("governed");
		var quote = post("/v1/quotes", member, "q", basket(1));
		assertEquals("22.00", quote.path("payable").asString());
		assertEquals("HIT", quote.path("sources").get(0).path("match").asString());
		assertEquals("approved-test-import", quote.path("sources").get(0).path("source").asString());
		audience("vip", 2, List.of());
		assertEquals("22.00", post("/v1/quotes", member, "q2", basket(1)).path("payable").asString());
		assertEquals(1, quote.path("sources").get(0).path("version").asInt());
	}

	@Test
	void staleAudienceIsUnknownAndCannotBeRescuedByNot() throws Exception {
		seed();
		audience("fresh", 1, List.of("m1"));
		var campaign = governed("fresh", Map.of("audience", Map.of("id", "fresh", "version", 1)));
		campaign.put("rule", Map.of("kind", "NOT", "children", List.of(Map.of("kind", "COMPARE", "field", "memberLevel",
				"operator", "EQ", "valueType", "TEXT", "value", "BASIC"))));
		post("/v1/admin/campaigns", admin, "c", campaign);
		approveAndPublish("fresh");
		var old = post("/v1/quotes", member, "q1", basket(1));
		assertEquals("22.00", old.path("payable").asString());
		jdbc.update("UPDATE marketing_audience_snapshot SET watermark=?,valid_until=? WHERE tenant_id=?",
				java.sql.Timestamp.from(Instant.now().minusSeconds(120)),
				java.sql.Timestamp.from(Instant.now().minusSeconds(60)), tenant);
		var current = post("/v1/quotes", member, "q2", basket(1));
		assertEquals("25.00", current.path("payable").asString());
		assertEquals("UNKNOWN", current.path("sources").get(0).path("match").asString());
		assertEquals("CONDITION_UNKNOWN", current.path("trace").get(0).path("reason").asString());
		assertEquals(old, call("GET", "/v1/quotes/" + old.path("quoteId").asString(), member, null, null).body());
	}

	@Test
	void audienceMissAndCrossTenantReferenceFailClosed() throws Exception {
		seed();
		audience("empty", 1, List.of());
		post("/v1/admin/campaigns", admin, "c",
				governed("miss", Map.of("audience", Map.of("id", "empty", "version", 1))));
		approveAndPublish("miss");
		var quote = post("/v1/quotes", member, "q", basket(1));
		assertEquals("25.00", quote.path("payable").asString());
		assertEquals("MISS", quote.path("sources").get(0).path("match").asString());
		assertEquals(404,
				call("POST", "/v1/admin/campaigns", admin, "missing",
						governed("missing", Map.of("audience", Map.of("id", "other-tenant-asset", "version", 1))))
					.status());
		assertEquals(403, call("GET", "/v1/admin/audiences", member, null, null).status());
	}

	@Test
	void reusableRuleMustBePublishedAndCannotChangeFrozenCampaign() throws Exception {
		seed();
		Object rule = Map.of("kind", "COMPARE", "field", "orderAmount", "operator", "GTE", "valueType", "DECIMAL",
				"value", "20.00");
		post("/v1/admin/rules", admin, "rule1", Map.of("ruleId", "spend", "version", 1, "name", "消费门槛", "rule", rule));
		var campaign = governed("rule-bound", Map.of("rule", Map.of("id", "spend", "version", 1)));
		campaign.remove("rule");
		assertEquals(409, call("POST", "/v1/admin/campaigns", admin, "before-publish", campaign).status());
		post("/v1/admin/rules/spend/1/publish", admin, "rule-publish", null);
		post("/v1/admin/campaigns", admin, "c", campaign);
		approveAndPublish("rule-bound");
		assertEquals("22.00", post("/v1/quotes", member, "q", basket(1)).path("payable").asString());
		post("/v1/admin/rules", admin, "rule2",
				Map.of("ruleId", "spend", "version", 2, "name", "新门槛", "rule", Map.of("kind", "COMPARE", "field",
						"orderAmount", "operator", "GTE", "valueType", "DECIMAL", "value", "100.00")));
		post("/v1/admin/rules/spend/2/publish", admin, "rule-publish2", null);
		assertEquals("22.00", post("/v1/quotes", member, "q2", basket(1)).path("payable").asString());
		assertEquals(400, call("POST", "/v1/admin/rules", admin, "untrusted", Map.of("ruleId", "bad", "version", 1,
				"name", "未知字段", "rule",
				Map.of("kind", "COMPARE", "field", "clientVip", "operator", "EQ", "valueType", "TEXT", "value", "yes")))
			.status());
	}

	@Test
	void governanceRejectAndOptimisticVersionCannotBeBypassed() throws Exception {
		seed();
		audience("vip", 1, List.of("m1"));
		post("/v1/admin/campaigns", admin, "c",
				governed("rejected", Map.of("audience", Map.of("id", "vip", "version", 1))));
		post("/v1/admin/campaigns/rejected/1/submit", admin, "submit", Map.of("expectedVersion", 0));
		assertEquals(409,
				call("POST", "/v1/admin/campaigns/rejected/1/approve", admin, "stale", Map.of("expectedVersion", 0))
					.status());
		post("/v1/admin/campaigns/rejected/1/reject", admin, "reject", Map.of("expectedVersion", 1));
		assertEquals(409,
				call("POST", "/v1/admin/campaigns/rejected/1/publish", admin, "publish", Map.of("expectedVersion", 2))
					.status());
		assertEquals(403,
				call("POST", "/v1/admin/campaigns/rejected/1/approve", member, "bad-role", Map.of("expectedVersion", 2))
					.status());
	}

	private void couponDefinition(String discount, boolean stackable, int quota) throws Exception {
		post("/v1/admin/coupon-definitions", admin, "definition",
				Map.of("definitionId", "coupon-def", "version", 1, "storeId", "store1", "name", "测试券", "minimumSpend",
						"0.00", "discountAmount", discount, "validFrom", Instant.now().minusSeconds(10).toString(),
						"validTo", Instant.now().plusSeconds(3600).toString(), "quota", quota, "stackable", stackable));
	}

	private JsonNode claimCoupon(String token, String key) throws Exception {
		return post("/v1/coupons/coupon-def/1/claim", token, key, null);
	}

	private Object couponBasket(JsonNode coupon, int quantity) {
		return Map.of("storeId", "store1", "items", List.of(Map.of("skuId", "sku1", "quantity", quantity)), "couponId",
				coupon.path("couponId").asString());
	}

	private String couponState(JsonNode coupon) {
		return jdbc.queryForObject("SELECT status FROM benefit_coupon WHERE tenant_id=? AND coupon_id=?", String.class,
				tenant, coupon.path("couponId").asString());
	}

	@Test
	void couponStacksAndCancellationReleasesOnlyOnce() throws Exception {
		seed();
		stock("sku1", 3);
		couponDefinition("5.00", true, 10);
		var coupon = claimCoupon(member, "claim");
		assertEquals(coupon, claimCoupon(member, "claim-again"));
		assertEquals(1, jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=?",
				Integer.class, tenant));
		post("/v1/admin/campaigns", admin, "c", draft("stack", 1, "3.00"));
		post("/v1/admin/campaigns/stack/1/publish", admin, "p", Map.of("expectedVersion", 0));
		var q = post("/v1/quotes", member, "q", couponBasket(coupon, 1));
		assertEquals("17.00", q.path("payable").asString());
		assertEquals("3.00", q.path("campaignDiscount").asString());
		assertEquals("5.00", q.path("coupon").path("discount").asString());
		var order = post("/v1/orders", member, "o", orderInput(q));
		assertEquals("HELD", couponState(coupon));
		post("/v1/orders/" + order.path("orderId").asString() + "/cancel", member, "cancel", null);
		assertEquals("AVAILABLE", couponState(coupon));
		post("/v1/orders/" + order.path("orderId").asString() + "/cancel", member, "cancel2", null);
		assertEquals("AVAILABLE", couponState(coupon));
	}

	@Test
	void oneCouponCannotBeReservedByTwoConcurrentOrders() throws Exception {
		seed();
		stock("sku1", 2);
		couponDefinition("5.00", true, 10);
		var coupon = claimCoupon(member, "claim");
		var q1 = post("/v1/quotes", member, "q1", couponBasket(coupon, 1));
		var q2 = post("/v1/quotes", member, "q2", couponBasket(coupon, 1));
		try (var pool = Executors.newFixedThreadPool(2)) {
			var latch = new CountDownLatch(1);
			var a = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/orders", member, "o1", orderInput(q1));
			});
			var b = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/orders", member, "o2", orderInput(q2));
			});
			latch.countDown();
			assertEquals(List.of(200, 409),
					java.util.stream.Stream.of(a.get(), b.get()).map(Reply::status).sorted().toList());
		}
		assertEquals(1, stockValue("held"));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon_hold WHERE tenant_id=?", Integer.class,
				tenant));
		assertEquals(1,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM trade_quote WHERE tenant_id=? AND consumed_order_id IS NOT NULL",
						Integer.class, tenant));
	}

	@Test
	void inventoryFailureRollsBackCouponAndCanRetrySameOrderCommand() throws Exception {
		seed();
		couponDefinition("5.00", true, 10);
		var coupon = claimCoupon(member, "claim");
		var quote = post("/v1/quotes", member, "q", couponBasket(coupon, 1));
		assertEquals(409, call("POST", "/v1/orders", member, "o", orderInput(quote)).status());
		assertEquals("AVAILABLE", couponState(coupon));
		stock("sku1", 1);
		var order = post("/v1/orders", member, "o", orderInput(quote));
		startPayment(order);
		post("/v1/orders/" + order.path("orderId").asString() + "/cancel", member, "cancel", null);
		reconcile(order);
		pump();
		assertEquals("HELD", couponState(coupon));
	}

	@Test
	void couponQuotaIsSafeAcrossDifferentMembers() throws Exception {
		seed();
		couponDefinition("5.00", true, 1);
		String second = token(tenant, "second", "MEMBER");
		post("/v1/admin/members", admin, "m2",
				Map.of("memberId", "m2", "actorId", "second", "displayName", "第二会员", "memberLevel", "VIP"));
		try (var pool = Executors.newFixedThreadPool(2)) {
			var latch = new CountDownLatch(1);
			var a = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/coupons/coupon-def/1/claim", member, "claim", null);
			});
			var b = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/coupons/coupon-def/1/claim", second, "claim", null);
			});
			latch.countDown();
			assertEquals(List.of(200, 409),
					java.util.stream.Stream.of(a.get(), b.get()).map(Reply::status).sorted().toList());
		}
		assertEquals(1, jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=?",
				Integer.class, tenant));
		assertEquals(1,
				jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon WHERE tenant_id=?", Integer.class, tenant));
	}

	@Test
	void exclusiveCouponLosesToBetterCampaignWithoutBeingConsumed() throws Exception {
		seed();
		couponDefinition("5.00", false, 10);
		var coupon = claimCoupon(member, "claim");
		post("/v1/admin/campaigns", admin, "c", draft("better", 1, "10.00"));
		post("/v1/admin/campaigns/better/1/publish", admin, "p", Map.of("expectedVersion", 0));
		var q = post("/v1/quotes", member, "q", couponBasket(coupon, 1));
		assertEquals("15.00", q.path("payable").asString());
		assertEquals("NOT_SELECTED", q.path("couponStatus").asString());
		assertEquals("AVAILABLE", couponState(coupon));
		assertEquals(404, call("POST", "/v1/quotes", other, "foreign", couponBasket(coupon, 1)).status());
	}

	@Test
	void partialRefundKeepsCouponUsedFullRefundReturnsAndOldEventCannotReleaseNewHold() throws Exception {
		seed();
		stock("sku1", 3);
		couponDefinition("5.00", true, 10);
		var coupon = claimCoupon(member, "claim");
		var order = post("/v1/orders", member, "o",
				orderInput(post("/v1/quotes", member, "q", couponBasket(coupon, 2))));
		payOrder(order);
		ship(order);
		assertEquals("USED", couponState(coupon));
		for (int n = 0; n < 2; n++) {
			var request = requestReturn(order, 1, "r" + n);
			approve(request, "a" + n);
			var receiving = post("/v1/admin/aftersales/" + request.path("caseId").asString() + "/receive-return", admin,
					"receive" + n, null);
			finishRefund(receiving, "refund" + n);
			pump();
			assertEquals(n == 0 ? "USED" : "AVAILABLE", couponState(coupon));
		}
		var second = post("/v1/orders", member, "second-order",
				orderInput(post("/v1/quotes", member, "second-quote", couponBasket(coupon, 1))));
		assertEquals("HELD", couponState(coupon));
		jdbc.update(
				"UPDATE platform_event SET status='PENDING',available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='aftersales.completed.v1'",
				tenant);
		pump();
		assertEquals("HELD", couponState(coupon));
		assertEquals(second.path("orderId").asString(),
				jdbc.queryForObject("SELECT order_id FROM benefit_coupon WHERE tenant_id=?", String.class, tenant));
	}

	@Test
	void couponCanMakeOrderFreeAndReturnWithoutFabricatedPayment() throws Exception {
		seed();
		stock("sku1", 1);
		couponDefinition("100.00", true, 1);
		var coupon = claimCoupon(member, "claim");
		var order = post("/v1/orders", member, "o",
				orderInput(post("/v1/quotes", member, "q", couponBasket(coupon, 1))));
		assertEquals("PAID", order.path("status").asString());
		assertEquals("USED", couponState(coupon));
		assertEquals(0,
				jdbc.queryForObject("SELECT COUNT(*) FROM payment_attempt WHERE tenant_id=?", Integer.class, tenant));
		approve(requestReturn(order, 1, "r"), "a");
		pump();
		pump();
		assertEquals("AVAILABLE", couponState(coupon));
	}

	private void budgetCampaign(String cap, int percentage, int funding, String maximumDiscount) throws Exception {
		audience("budget-audience", 1, List.of("m1"));
		var campaign = new HashMap<>(draft("budget", 1, maximumDiscount));
		campaign.put("policy", Map.of("audience", Map.of("id", "budget-audience", "version", 1), "terms",
				Map.of("percentageBps", percentage, "platformFundingBps", funding, "budget", cap)));
		post("/v1/admin/campaigns", admin, "budget-create", campaign);
		approveAndPublish("budget");
	}

	private java.math.BigDecimal budgetValue(String field) {
		return jdbc.queryForObject(
				"SELECT " + field + " FROM marketing_budget WHERE tenant_id=? AND campaign_id='budget'",
				java.math.BigDecimal.class, tenant);
	}

	@Test
	void percentageCapAndFundingComponentsConserveEveryCent() throws Exception {
		seed();
		budgetCampaign("100.00", 2500, 3333, "5.00");
		var definition = new HashMap<String, Object>(
				Map.of("definitionId", "coupon-def", "version", 1, "storeId", "store1", "name", "资方券", "minimumSpend",
						"0.00", "discountAmount", "2.00", "validFrom", Instant.now().minusSeconds(10).toString(),
						"validTo", Instant.now().plusSeconds(3600).toString(), "quota", 10, "stackable", true));
		definition.put("platformFundingBps", 5000);
		post("/v1/admin/coupon-definitions", admin, "definition", definition);
		var coupon = claimCoupon(member, "claim");
		var quote = post("/v1/quotes", member, "q", couponBasket(coupon, 1));
		assertEquals("5.00", quote.path("campaignDiscount").asString());
		assertEquals("18.00", quote.path("payable").asString());
		assertEquals("2.66", quote.path("funding").path("platformFunding").asString());
		assertEquals("4.34", quote.path("funding").path("merchantFunding").asString());
		var line = quote.path("funding").path("items").get(0);
		assertEquals("5.00", line.path("campaignDiscount").asString());
		assertEquals("2.00", line.path("couponDiscount").asString());
		assertEquals(new java.math.BigDecimal("7.00"), new java.math.BigDecimal(line.path("platformFunding").asString())
			.add(new java.math.BigDecimal(line.path("merchantFunding").asString())));
	}

	@Test
	void budgetRaceCannotOverspendAndCancellationReleasesBeforeRetry() throws Exception {
		seed();
		stock("sku1", 3);
		budgetCampaign("3.00", 0, 5000, "3.00");
		var q1 = post("/v1/quotes", member, "q1", basket(1));
		var q2 = post("/v1/quotes", member, "q2", basket(1));
		Reply first, second;
		try (var pool = Executors.newFixedThreadPool(2)) {
			var latch = new CountDownLatch(1);
			var a = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/orders", member, "o1", orderInput(q1));
			});
			var b = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/orders", member, "o2", orderInput(q2));
			});
			latch.countDown();
			first = a.get();
			second = b.get();
			assertEquals(List.of(200, 409),
					java.util.stream.Stream.of(first, second).map(Reply::status).sorted().toList());
		}
		assertEquals(new java.math.BigDecimal("3.00"), budgetValue("held"));
		var winner = first.status() == 200 ? first.body() : second.body();
		post("/v1/orders/" + winner.path("orderId").asString() + "/cancel", member, "cancel", null);
		assertEquals(new java.math.BigDecimal("0.00"), budgetValue("held"));
		var retried = post("/v1/orders", member, first.status() == 200 ? "o2" : "o1",
				orderInput(first.status() == 200 ? q2 : q1));
		payOrder(retried);
		assertEquals(new java.math.BigDecimal("0.00"), budgetValue("held"));
		assertEquals(new java.math.BigDecimal("3.00"), budgetValue("spent"));
		var refund = approve(requestReturn(retried, 1, "r"), "a");
		finishRefund(refund, "refund");
		assertEquals(new java.math.BigDecimal("3.00"), budgetValue("spent"));
	}

	@Test
	void insufficientBudgetRollsBackPreviouslyReservedCouponAndQuote() throws Exception {
		seed();
		stock("sku1", 1);
		budgetCampaign("1.00", 0, 0, "3.00");
		couponDefinition("5.00", true, 10);
		var coupon = claimCoupon(member, "claim");
		var q = post("/v1/quotes", member, "q", couponBasket(coupon, 1));
		assertEquals(409, call("POST", "/v1/orders", member, "o", orderInput(q)).status());
		assertEquals("AVAILABLE", couponState(coupon));
		assertEquals(1, stockValue("available"));
		assertEquals(new java.math.BigDecimal("0.00"), budgetValue("held"));
		assertEquals(0,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM trade_quote WHERE tenant_id=? AND consumed_order_id IS NOT NULL",
						Integer.class, tenant));
	}

	@Test
	void unknownPaymentRetainsBudgetAndFundingSnapshotSurvivesPause() throws Exception {
		seed();
		stock("sku1", 1);
		budgetCampaign("3.00", 0, 5000, "3.00");
		var q = post("/v1/quotes", member, "q", basket(1));
		post("/v1/admin/campaigns/budget/1/pause", admin, "pause", Map.of("expectedVersion", 3));
		var order = post("/v1/orders", member, "o", orderInput(q));
		startPayment(order);
		post("/v1/orders/" + order.path("orderId").asString() + "/cancel", member, "cancel", null);
		reconcile(order);
		pump();
		assertEquals(new java.math.BigDecimal("3.00"), budgetValue("held"));
		assertEquals(new java.math.BigDecimal("1.50"),
				jdbc.queryForObject("SELECT platform_funding FROM marketing_budget_hold WHERE tenant_id=?",
						java.math.BigDecimal.class, tenant));
		assertEquals(q, call("GET", "/v1/quotes/" + q.path("quoteId").asString(), member, null, null).body());
	}

	@Test
	void releaseLocksSharedResourcesInCheckoutOrderSoConcurrentCheckoutCannotDeadlock() throws Exception {
		seed();
		stock("sku1", 3);
		budgetCampaign("100.00", 0, 5000, "3.00");
		var order = post("/v1/orders", member, "o", orderInput(post("/v1/quotes", member, "q", basket(1))));
		String id = order.path("orderId").asString();
		assertEquals(new java.math.BigDecimal("3.00"), budgetValue("held"));
		var cancel = new CompletableFuture<Reply>();
		// 模拟另一笔下单：已按下单顺序锁住活动预算，随后请求同一SKU库存。
		new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s -> {
			jdbc.queryForObject(
					"SELECT held FROM marketing_budget WHERE tenant_id=? AND campaign_id='budget' FOR UPDATE",
					java.math.BigDecimal.class, tenant);
			Thread.ofVirtual().start(() -> {
				try {
					cancel.complete(call("POST", "/v1/orders/" + id + "/cancel", member, "cancel", null));
				}
				catch (Exception e) {
					cancel.completeExceptionally(e);
				}
			});
			try {
				Thread.sleep(1000);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(e);
			}
			jdbc.queryForObject("SELECT available FROM inventory_stock WHERE tenant_id=? AND sku_id='sku1' FOR UPDATE",
					Long.class, tenant);
		});
		var reply = cancel.get(15, TimeUnit.SECONDS);
		assertEquals(200, reply.status(), reply.body().toString());
		assertEquals("CANCELLED", readOrder(order).path("status").asString());
		assertEquals(3, stockValue("available"));
		assertEquals(0, budgetValue("held").signum());
	}

	@Test
	void fundingAcrossMultipleSkusHasNoNegativeLineOrLostCent() throws Exception {
		seed();
		post("/v1/admin/skus", admin, "sku2",
				Map.of("skuId", "sku2", "storeId", "store1", "title", "小额商品", "unitPrice", "1.00"));
		budgetCampaign("10.00", 0, 3333, "3.00");
		couponDefinition("2.00", true, 10);
		var coupon = claimCoupon(member, "claim");
		var quote = post("/v1/quotes", member, "q",
				Map.of("storeId", "store1", "couponId", coupon.path("couponId").asString(), "items",
						List.of(Map.of("skuId", "sku1", "quantity", 1), Map.of("skuId", "sku2", "quantity", 1))));
		java.math.BigDecimal platform = java.math.BigDecimal.ZERO, merchant = java.math.BigDecimal.ZERO,
				discount = java.math.BigDecimal.ZERO, payable = java.math.BigDecimal.ZERO;
		for (var line : quote.path("funding").path("items")) {
			var p1 = new java.math.BigDecimal(line.path("platformFunding").asString());
			var m1 = new java.math.BigDecimal(line.path("merchantFunding").asString());
			var c1 = new java.math.BigDecimal(line.path("campaignDiscount").asString());
			var c2 = new java.math.BigDecimal(line.path("couponDiscount").asString());
			assertEquals(c1.add(c2), p1.add(m1));
			assertTrue(p1.signum() >= 0 && m1.signum() >= 0);
			platform = platform.add(p1);
			merchant = merchant.add(m1);
		}
		for (var line : quote.path("items")) {
			discount = discount.add(new java.math.BigDecimal(line.path("discount").asString()));
			var p1 = new java.math.BigDecimal(line.path("payable").asString());
			assertTrue(p1.signum() >= 0);
			payable = payable.add(p1);
		}
		assertEquals(new java.math.BigDecimal("0.99"), platform);
		assertEquals(new java.math.BigDecimal("4.01"), merchant);
		assertEquals(new java.math.BigDecimal("5.00"), discount);
		assertEquals(new java.math.BigDecimal("21.00"), payable);
	}

	private void entitlementCampaign(int quota) throws Exception {
		post("/v1/admin/entitlement-definitions", admin, "benefit-definition",
				Map.of("benefitId", "credit", "version", 1, "storeId", "store1", "name", "体验权益", "units", 3, "quota",
						quota, "validFrom", Instant.now().minusSeconds(120).toString(), "validTo",
						Instant.now().plusSeconds(7200).toString(), "validityDays", 1));
		audience("benefit-audience", 1, List.of("m1"));
		var campaign = new HashMap<>(draft("benefit-campaign", 1, "1.00"));
		campaign.put("policy",
				Map.of("audience", Map.of("id", "benefit-audience", "version", 1), "terms",
						Map.of("percentageBps", 0, "platformFundingBps", 0, "budget", "20.00", "grant",
								Map.of("benefitId", "credit", "version", 1))));
		post("/v1/admin/campaigns", admin, "benefit-campaign", campaign);
		approveAndPublish("benefit-campaign");
	}

	private void campaignCoupon(int quota) throws Exception {
		post("/v1/admin/coupon-definitions", admin, "campaign-coupon-definition",
				Map.ofEntries(Map.entry("definitionId", "campaign-coupon"), Map.entry("version", 1),
						Map.entry("storeId", "store1"), Map.entry("name", "支付赠券"),
						Map.entry("minimumSpend", "0.00"), Map.entry("discountAmount", "2.00"),
						Map.entry("validFrom", Instant.now().minusSeconds(120).toString()),
						Map.entry("validTo", Instant.now().plusSeconds(7200).toString()), Map.entry("quota", quota),
						Map.entry("stackable", false), Map.entry("issuanceMode", "SOURCE_ONLY"),
						Map.entry("validityDays", 1)));
		audience("coupon-audience", 1, List.of("m1"));
		var campaign = new HashMap<>(draft("coupon-campaign", 1, "1.00"));
		campaign.put("policy", Map.of("audience", Map.of("id", "coupon-audience", "version", 1), "terms",
				Map.of("percentageBps", 0, "platformFundingBps", 0, "budget", "20.00", "coupon",
						Map.of("definitionId", "campaign-coupon", "version", 1))));
		post("/v1/admin/campaigns", admin, "campaign-coupon-create", campaign);
		approveAndPublish("coupon-campaign");
	}

	@Test
	void campaignCouponReservesQuotaAndIssuesOnceAfterPayment() throws Exception {
		seed();
		stock("sku1", 2);
		campaignCoupon(1);
		var firstQuote = post("/v1/quotes", member, "coupon-q1", basket(1));
		var first = post("/v1/orders", member, "coupon-o1", orderInput(firstQuote));
		var before = execution(first, "coupon-campaign");
		assertEquals("COUPON", before.path("benefitType").asString());
		assertEquals("RESERVED", before.path("status").asString());
		assertEquals("HELD", before.path("grantStatus").asString());
		assertEquals(1, jdbc.queryForObject("SELECT reserved FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='campaign-coupon'", Integer.class, tenant));
		assertEquals(0, jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='campaign-coupon'", Integer.class, tenant));
		var secondQuote = post("/v1/quotes", member, "coupon-q2", basket(1));
		assertEquals(409, call("POST", "/v1/orders", member, "coupon-o2", orderInput(secondQuote)).status());
		payOrder(first);
		pump();
		var after = execution(first, "coupon-campaign");
		assertEquals("GRANTED", after.path("status").asString());
		assertEquals("ISSUED", after.path("grantStatus").asString());
		assertEquals(0, jdbc.queryForObject("SELECT reserved FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='campaign-coupon'", Integer.class, tenant));
		assertEquals(1, jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='campaign-coupon'", Integer.class, tenant));
		assertEquals(after.path("grantId").asString(), jdbc.queryForObject("SELECT coupon_id FROM benefit_coupon WHERE tenant_id=? AND source_type='CAMPAIGN' AND source_id=?", String.class, tenant, first.path("orderId").asString()));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon WHERE tenant_id=? AND source_type='CAMPAIGN'", Integer.class, tenant));
	}

	@Test
	void cancelledCampaignCouponReleasesQuotaWithoutIssuing() throws Exception {
		seed();
		stock("sku1", 2);
		campaignCoupon(1);
		var first = post("/v1/orders", member, "cancel-coupon-o1",
				orderInput(post("/v1/quotes", member, "cancel-coupon-q1", basket(1))));
		post("/v1/orders/" + first.path("orderId").asString() + "/cancel", member, "cancel-coupon", null);
		assertEquals("RELEASED", execution(first, "coupon-campaign").path("status").asString());
		assertEquals(0, jdbc.queryForObject("SELECT reserved FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='campaign-coupon'", Integer.class, tenant));
		assertEquals(0, jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='campaign-coupon'", Integer.class, tenant));
		var second = post("/v1/orders", member, "cancel-coupon-o2",
				orderInput(post("/v1/quotes", member, "cancel-coupon-q2", basket(1))));
		assertEquals("RESERVED", execution(second, "coupon-campaign").path("status").asString());
	}

	@Test
	void campaignCouponLastUnitRaceCannotOverReserve() throws Exception {
		seed();
		stock("sku1", 2);
		campaignCoupon(1);
		var q1 = post("/v1/quotes", member, "race-coupon-q1", basket(1));
		var q2 = post("/v1/quotes", member, "race-coupon-q2", basket(1));
		try (var pool = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var one = pool.submit(() -> {
				start.await();
				return call("POST", "/v1/orders", member, "race-coupon-o1", orderInput(q1));
			});
			var two = pool.submit(() -> {
				start.await();
				return call("POST", "/v1/orders", member, "race-coupon-o2", orderInput(q2));
			});
			start.countDown();
			var outcomes = List.of(one.get(), two.get());
			assertEquals(List.of(200, 409), outcomes.stream().map(Reply::status).sorted().toList());
			var winner = outcomes.stream().filter(result -> result.status() == 200).findFirst().orElseThrow().body();
			assertEquals(1, jdbc.queryForObject("SELECT reserved FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='campaign-coupon'", Integer.class, tenant));
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_campaign_coupon_hold WHERE tenant_id=?", Integer.class, tenant));
			payOrder(winner);
			assertEquals("GRANTED", execution(winner, "coupon-campaign").path("status").asString());
			assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon WHERE tenant_id=? AND source_type='CAMPAIGN'", Integer.class, tenant));
		}
	}

	@Test
	void campaignCouponGrantRollsBackWithTransactionAndThenRecovers() throws Exception {
		seed();
		stock("sku1", 1);
		campaignCoupon(1);
		var order = post("/v1/orders", member, "coupon-fault-order",
				orderInput(post("/v1/quotes", member, "coupon-fault-quote", basket(1))));
		String orderId = order.path("orderId").asString();
		var transaction = new org.springframework.transaction.support.TransactionTemplate(transactions);
		assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
			campaignCoupons.confirmCampaign(tenant, orderId);
			throw new IllegalStateException("注入发券后提交前崩溃");
		}));
		assertEquals(1, jdbc.queryForObject("SELECT reserved FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='campaign-coupon'", Integer.class, tenant));
		assertEquals(0, jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='campaign-coupon'", Integer.class, tenant));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon WHERE tenant_id=? AND source_type='CAMPAIGN'", Integer.class, tenant));
		assertEquals("HELD", execution(order, "coupon-campaign").path("grantStatus").asString());
		payOrder(order);
		assertEquals("GRANTED", execution(order, "coupon-campaign").path("status").asString());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon WHERE tenant_id=? AND source_type='CAMPAIGN'", Integer.class, tenant));
	}

	@Test
	void twoNewInstancesIssueOneCampaignCoupon() throws Exception {
		seed();
		stock("sku1", 1);
		campaignCoupon(1);
		var order = post("/v1/orders", member, "two-coupon-order",
				orderInput(post("/v1/quotes", member, "two-coupon-quote", basket(1))));
		var payment = startPayment(order);
		sandbox(payment, "PAID");
		reconcile(order);
		try (var second = new SpringApplicationBuilder(CommerceApplication.class)
			.initializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
					"phase7-coupon-second-instance", Map.of("server.port", 0, "spring.datasource.url",
							System.getenv("COMMERCE_TEST_DB_URL"), "spring.datasource.username",
							System.getenv("COMMERCE_DB_USER"), "spring.datasource.password",
							System.getenv("COMMERCE_DB_PASSWORD"), "commerce.sandbox-enabled", true,
							"commerce.workers-enabled", false, "commerce.marketing.coupon-enabled", true))))
			.run()) {
			int otherPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
			try (var pool = Executors.newFixedThreadPool(2)) {
				var start = new CountDownLatch(1);
				var one = pool.submit(() -> {
					start.await();
					return call("POST", "/v1/admin/events/pump", admin, null, null);
				});
				var two = pool.submit(() -> {
					start.await();
					return callAt(otherPort, "POST", "/v1/admin/events/pump", admin, null, null);
				});
				start.countDown();
				assertEquals(200, one.get().status());
				assertEquals(200, two.get().status());
			}
		}
		pump();
		pump();
		assertEquals("GRANTED", execution(order, "coupon-campaign").path("status").asString());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon WHERE tenant_id=? AND source_type='CAMPAIGN'", Integer.class, tenant));
		assertEquals(1, jdbc.queryForObject("SELECT issued FROM benefit_coupon_definition WHERE tenant_id=? AND definition_id='campaign-coupon'", Integer.class, tenant));
	}

	private JsonNode entitlementOrder() throws Exception {
		seed();
		stock("sku1", 3);
		entitlementCampaign(1);
		return post("/v1/orders", member, "o", orderInput(post("/v1/quotes", member, "q", basket(1))));
	}

	private JsonNode granted(JsonNode order) throws Exception {
		payOrder(order);
		pump();
		return call("GET", "/v1/entitlements", member, null, null).body().get(0);
	}

	private JsonNode execution(JsonNode order, String campaign) throws Exception {
		return call("GET", "/v1/admin/marketing-executions/" + order.path("orderId").asString() + "/" + campaign,
				admin, null, null).body();
	}

	@Test
	void marketingExecutionTracksRealOrderGrantAndTenantBoundary() throws Exception {
		var order = entitlementOrder();
		var first = execution(order, "benefit-campaign");
		assertEquals("RESERVED", first.path("status").asString());
		assertEquals("ELIGIBLE", first.path("reasonCode").asString());
		assertEquals(1, first.path("campaignVersion").asLong());
		assertEquals("benefit-audience", first.path("audienceId").asString());
		assertEquals(1, first.path("audienceVersion").asLong());
		assertEquals("credit", first.path("benefitId").asString());
		assertEquals("RESERVED", first.path("grantStatus").asString());
		assertEquals(403, call("GET", "/v1/admin/marketing-executions/" + order.path("orderId").asString()
				+ "/benefit-campaign", member, null, null).status());
		String foreignAdmin = token("other-" + UUID.randomUUID(), "admin", "ADMIN");
		assertEquals(404, call("GET", "/v1/admin/marketing-executions/" + order.path("orderId").asString()
				+ "/benefit-campaign", foreignAdmin, null, null).status());
		assertEquals(order,
				post("/v1/orders", member, "o", orderInput(call("GET", "/v1/quotes/"
						+ first.path("quoteId").asString(), member, null, null).body())));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_execution WHERE tenant_id=?", Integer.class,
				tenant));
		assertEquals(1, call("GET", "/v1/admin/marketing-executions", admin, null, null).body().size());
		payOrder(order);
		assertEquals("GRANT_REQUESTED", execution(order, "benefit-campaign").path("status").asString());
		pump();
		pump();
		var last = execution(order, "benefit-campaign");
		assertEquals("GRANTED", last.path("status").asString());
		assertEquals("AVAILABLE", last.path("grantStatus").asString());
		assertEquals("DELIVERED", last.path("eventStatus").asString());
		assertEquals("GRANTED", jdbc.queryForObject(
				"SELECT status FROM marketing_execution WHERE tenant_id=? AND order_id=?", String.class, tenant,
				order.path("orderId").asString()));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='GRANT'", Integer.class, tenant));
	}

	@Test
	void draftPreviewUsesProductionDecisionWithoutParticipationOrQuotaWrites() throws Exception {
		seed();
		stock("sku1", 2);
		post("/v1/admin/entitlement-definitions", admin, "preview-benefit",
				Map.of("benefitId", "preview-credit", "version", 1, "storeId", "store1", "name", "预览权益",
						"units", 1, "quota", 2, "validFrom", Instant.now().minusSeconds(120).toString(),
						"validTo", Instant.now().plusSeconds(7200).toString(), "validityDays", 1));
		audience("preview-audience", 1, List.of("m1"));
		var draft = new HashMap<>(draft("preview-campaign", 1, "1.00"));
		draft.put("policy", Map.of("audience", Map.of("id", "preview-audience", "version", 1), "terms",
				Map.of("percentageBps", 0, "platformFundingBps", 0, "budget", "20.00", "grant",
						Map.of("benefitId", "preview-credit", "version", 1))));
		post("/v1/admin/campaigns", admin, "preview-create", draft);
		var input = Map.of("memberId", "m1", "items", List.of(Map.of("skuId", "sku1", "quantity", 1)));
		var first = post("/v1/admin/campaigns/preview-campaign/1/preview", admin, null, input);
		assertEquals(first, post("/v1/admin/campaigns/preview-campaign/1/preview", admin, null, input));
		assertEquals("1.00", first.path("discount").asString());
		assertEquals("ELIGIBLE", first.path("trace").get(0).path("reason").asString());
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_execution WHERE tenant_id=?", Integer.class,
				tenant));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_grant WHERE tenant_id=?", Integer.class,
				tenant));
		assertEquals(0, jdbc.queryForObject("SELECT reserved FROM benefit_definition WHERE tenant_id=? AND benefit_id='preview-credit'",
				Integer.class, tenant));
		assertEquals(new java.math.BigDecimal("0.00"), jdbc.queryForObject(
				"SELECT held FROM marketing_budget WHERE tenant_id=? AND campaign_id='preview-campaign'",
				java.math.BigDecimal.class, tenant));
	}

	@Test
	void publicationRechecksAudienceAndRejectsDuplicateRuleBranches() throws Exception {
		seed();
		audience("publish-audience", 1, List.of("m1"));
		post("/v1/admin/campaigns", admin, "publication-create",
				governed("publish-guard", Map.of("audience", Map.of("id", "publish-audience", "version", 1))));
		post("/v1/admin/campaigns/publish-guard/1/submit", admin, "publication-submit",
				Map.of("expectedVersion", 0));
		post("/v1/admin/campaigns/publish-guard/1/approve", admin, "publication-approve",
				Map.of("expectedVersion", 1));
		jdbc.update("UPDATE marketing_audience_snapshot SET valid_until=? WHERE tenant_id=? AND audience_id='publish-audience'",
				java.sql.Timestamp.from(Instant.now().minusSeconds(1)), tenant);
		assertEquals(409, call("POST", "/v1/admin/campaigns/publish-guard/1/publish", admin,
				"publication-invalid", Map.of("expectedVersion", 2)).status());
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM marketing_campaign WHERE tenant_id=? AND campaign_id='publish-guard' AND status='PUBLISHED'",
				Integer.class, tenant));
		var condition = Map.of("kind", "COMPARE", "field", "orderAmount", "operator", "GTE",
				"valueType", "DECIMAL", "value", "20.00");
		var invalid = new HashMap<>(draft("duplicate-rule", 1, "1.00"));
		invalid.put("rule", Map.of("kind", "ALL", "children", List.of(condition, condition)));
		assertEquals(400, call("POST", "/v1/admin/campaigns", admin, "duplicate-rule", invalid).status());
	}

	@Test
	void marketingExecutionKeepsVersionsAcrossPublishAndRollback() throws Exception {
		seed();
		stock("sku1", 5);
		entitlementCampaign(3);
		var first = post("/v1/orders", member, "version-o1",
				orderInput(post("/v1/quotes", member, "version-q1", basket(1))));
		var secondDraft = new HashMap<>(draft("benefit-campaign", 2, "2.00"));
		secondDraft.put("policy", Map.of("audience", Map.of("id", "benefit-audience", "version", 1), "terms",
				Map.of("percentageBps", 0, "platformFundingBps", 0, "budget", "20.00", "grant",
						Map.of("benefitId", "credit", "version", 1))));
		post("/v1/admin/campaigns", admin, "version-v2", secondDraft);
		post("/v1/admin/campaigns/benefit-campaign/2/submit", admin, "version-submit", Map.of("expectedVersion", 0));
		post("/v1/admin/campaigns/benefit-campaign/2/approve", admin, "version-approve", Map.of("expectedVersion", 1));
		post("/v1/admin/campaigns/benefit-campaign/2/publish", admin, "version-publish", Map.of("expectedVersion", 2));
		var second = post("/v1/orders", member, "version-o2",
				orderInput(post("/v1/quotes", member, "version-q2", basket(1))));
		post("/v1/admin/campaigns/benefit-campaign/1/publish", admin, "version-rollback",
				Map.of("expectedVersion", 4));
		var third = post("/v1/orders", member, "version-o3",
				orderInput(post("/v1/quotes", member, "version-q3", basket(1))));
		assertEquals(List.of(1L, 2L, 1L), List.of(execution(first, "benefit-campaign").path("campaignVersion").asLong(),
				execution(second, "benefit-campaign").path("campaignVersion").asLong(),
				execution(third, "benefit-campaign").path("campaignVersion").asLong()));
		assertEquals(List.of("1.00", "2.00", "1.00"), List.of(execution(first, "benefit-campaign").path("discountAmount").asString(),
				execution(second, "benefit-campaign").path("discountAmount").asString(),
				execution(third, "benefit-campaign").path("discountAmount").asString()));
		assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_execution WHERE tenant_id=?", Integer.class,
				tenant));
	}

	@Test
	void marketingExecutionShowsIsolatedGrantAndUsesExistingRecovery() throws Exception {
		var order = entitlementOrder();
		payOrder(order);
		var pending = execution(order, "benefit-campaign");
		String eventId = pending.path("eventId").asString();
		assertFalse(eventId.isBlank());
		jdbc.update("UPDATE platform_event SET status='ISOLATED',attempts=5,failure_class='BUSINESS_REJECTED',"
				+ "last_error='test-injected' WHERE event_id=? AND tenant_id=?", eventId, tenant);
		assertEquals("GRANT_FAILED", execution(order, "benefit-campaign").path("status").asString());
		assertEquals("REQUESTED", execution(order, "benefit-campaign").path("grantStatus").asString());
		var recovered = post("/v1/admin/runtime/recoveries", admin, "grant-retry",
				Map.of("workType", "event", "action", "RETRY", "workIds", List.of(eventId),
						"expectedFailureClass", "BUSINESS_REJECTED", "reason", "验证营销发放恢复"));
		assertEquals(1, recovered.path("applied").asInt());
		pump();
		pump();
		assertEquals("GRANTED", execution(order, "benefit-campaign").path("status").asString());
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='GRANT'", Integer.class, tenant));
	}

	@Test
	void twoAppInstancesCompleteOneMarketingGrant() throws Exception {
		var order = entitlementOrder();
		payOrder(order);
		try (var second = new SpringApplicationBuilder(CommerceApplication.class)
			.initializers(context -> context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
					"phase6-second-instance", Map.of("server.port", 0, "spring.datasource.url",
							System.getenv("COMMERCE_TEST_DB_URL"), "spring.datasource.username",
							System.getenv("COMMERCE_DB_USER"), "spring.datasource.password",
							System.getenv("COMMERCE_DB_PASSWORD"), "commerce.sandbox-enabled", true,
							"commerce.workers-enabled", false))))
			.run()) {
			int secondPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
			try (var pool = Executors.newFixedThreadPool(2)) {
				var start = new CountDownLatch(1);
				var first = pool.submit(() -> {
					start.await();
					return call("POST", "/v1/admin/events/pump", admin, null, null);
				});
				var otherInstance = pool.submit(() -> {
					start.await();
					return callAt(secondPort, "POST", "/v1/admin/events/pump", admin, null, null);
				});
				start.countDown();
				assertEquals(200, first.get().status());
				assertEquals(200, otherInstance.get().status());
			}
		}
		pump();
		assertEquals("GRANTED", execution(order, "benefit-campaign").path("status").asString());
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='GRANT'", Integer.class, tenant));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM marketing_execution WHERE tenant_id=?", Integer.class, tenant));
	}

	@Test
	void concurrentCampaignPublicationKeepsOneEffectiveVersion() throws Exception {
		seed();
		post("/v1/admin/campaigns", admin, "race-create", draft("publish-race", 1, "3.00"));
		try (var pool = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var first = pool.submit(() -> {
				start.await();
				return call("POST", "/v1/admin/campaigns/publish-race/1/publish", admin, "race-a",
						Map.of("expectedVersion", 0));
			});
			var second = pool.submit(() -> {
				start.await();
				return call("POST", "/v1/admin/campaigns/publish-race/1/publish", admin, "race-b",
						Map.of("expectedVersion", 0));
			});
			start.countDown();
			assertEquals(List.of(200, 409),
					java.util.stream.Stream.of(first.get(), second.get()).map(Reply::status).sorted().toList());
		}
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM marketing_campaign WHERE tenant_id=? AND campaign_id='publish-race' AND status='PUBLISHED'",
				Integer.class, tenant));
	}

	@Test
	void entitlementIsReservedThenGrantedOnceAfterTrustedPayment() throws Exception {
		var order = entitlementOrder();
		assertEquals("RESERVED",
				jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?", String.class, tenant));
		assertEquals(1, jdbc.queryForObject("SELECT reserved FROM benefit_definition WHERE tenant_id=?", Integer.class,
				tenant));
		payOrder(order);
		assertEquals("REQUESTED",
				jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?", String.class, tenant));
		pump();
		assertEquals("AVAILABLE",
				jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?", String.class, tenant));
		assertEquals(3, jdbc.queryForObject("SELECT remaining_units FROM benefit_grant WHERE tenant_id=?",
				Integer.class, tenant));
		jdbc.update(
				"UPDATE platform_event SET status='PENDING',available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='benefit.grant.requested.v1'",
				tenant);
		pump();
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='GRANT'",
				Integer.class, tenant));
		assertEquals(1,
				jdbc.queryForObject("SELECT issued FROM benefit_definition WHERE tenant_id=?", Integer.class, tenant));
	}

	@Test
	void entitlementQuotaFailureRollsBackOrderBudgetAndInventory() throws Exception {
		var first = entitlementOrder();
		var secondQuote = post("/v1/quotes", member, "q2", basket(1));
		assertEquals(409, call("POST", "/v1/orders", member, "o2", orderInput(secondQuote)).status());
		assertEquals(1, stockValue("held"));
		assertEquals(1,
				jdbc.queryForObject("SELECT COUNT(*) FROM order_record WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(new java.math.BigDecimal("1.00"), jdbc
			.queryForObject("SELECT held FROM marketing_budget WHERE tenant_id=?", java.math.BigDecimal.class, tenant));
		post("/v1/orders/" + first.path("orderId").asString() + "/cancel", member, "cancel", null);
		assertEquals(0, jdbc.queryForObject("SELECT reserved FROM benefit_definition WHERE tenant_id=?", Integer.class,
				tenant));
		post("/v1/orders", member, "o2", orderInput(secondQuote));
		assertEquals(1, jdbc.queryForObject("SELECT reserved FROM benefit_definition WHERE tenant_id=?", Integer.class,
				tenant));
	}

	@Test
	void lastEntitlementQuotaLetsOnlyOneConcurrentOrderReserve() throws Exception {
		seed();
		stock("sku1", 3);
		entitlementCampaign(1);
		var firstQuote = post("/v1/quotes", member, "quota-q1", basket(1));
		var secondQuote = post("/v1/quotes", member, "quota-q2", basket(1));
		try (var pool = Executors.newFixedThreadPool(2)) {
			var start = new CountDownLatch(1);
			var first = pool.submit(() -> {
				start.await();
				return call("POST", "/v1/orders", member, "quota-o1", orderInput(firstQuote));
			});
			var second = pool.submit(() -> {
				start.await();
				return call("POST", "/v1/orders", member, "quota-o2", orderInput(secondQuote));
			});
			start.countDown();
			assertEquals(List.of(200, 409),
					java.util.stream.Stream.of(first.get(), second.get()).map(Reply::status).sorted().toList());
		}
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_grant WHERE tenant_id=?", Integer.class,
				tenant));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM marketing_execution WHERE tenant_id=?", Integer.class,
				tenant));
		assertEquals(1, jdbc.queryForObject("SELECT reserved FROM benefit_definition WHERE tenant_id=?", Integer.class,
				tenant));
		assertEquals(0, jdbc.queryForObject("SELECT issued FROM benefit_definition WHERE tenant_id=?", Integer.class,
				tenant));
	}

	@Test
	void concurrentEntitlementConsumptionCannotProduceNegativeBalance() throws Exception {
		var grant = granted(entitlementOrder());
		String id = grant.path("grantId").asString();
		try (var pool = Executors.newFixedThreadPool(2)) {
			var latch = new CountDownLatch(1);
			var a = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/entitlements/" + id + "/consume", member, "consume1", Map.of("units", 2));
			});
			var b = pool.submit(() -> {
				latch.await();
				return call("POST", "/v1/entitlements/" + id + "/consume", member, "consume2", Map.of("units", 2));
			});
			latch.countDown();
			assertEquals(List.of(200, 409),
					java.util.stream.Stream.of(a.get(), b.get()).map(Reply::status).sorted().toList());
		}
		assertEquals(1, jdbc.queryForObject("SELECT remaining_units FROM benefit_grant WHERE tenant_id=?",
				Integer.class, tenant));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='CONSUME'", Integer.class, tenant));
		var consumed = post("/v1/entitlements/" + id + "/consume", member, "last", Map.of("units", 1));
		assertEquals("CONSUMED", consumed.path("status").asString());
		assertEquals(consumed, post("/v1/entitlements/" + id + "/consume", member, "last", Map.of("units", 1)));
	}

	@Test
	void consumedEntitlementRefundCreatesExplicitCompensationDebt() throws Exception {
		var order = entitlementOrder();
		var grant = granted(order);
		String id = grant.path("grantId").asString();
		post("/v1/entitlements/" + id + "/consume", member, "consume", Map.of("units", 2));
		var refund = approve(requestReturn(order, 1, "r"), "a");
		finishRefund(refund, "refund");
		pump();
		assertEquals("COMPENSATION_REQUIRED",
				jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?", String.class, tenant));
		assertEquals(2,
				jdbc.queryForObject("SELECT debt_units FROM benefit_grant WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(0, jdbc.queryForObject("SELECT remaining_units FROM benefit_grant WHERE tenant_id=?",
				Integer.class, tenant));
		assertEquals(403, call("POST", "/v1/admin/entitlements/" + id + "/resolve", member, "unauthorized",
				Map.of("resolution", "WRITTEN_OFF", "reference", "test-decision"))
			.status());
		var resolved = post("/v1/admin/entitlements/" + id + "/resolve", admin, "resolve",
				Map.of("resolution", "WRITTEN_OFF", "reference", "approved-test-loss"));
		assertEquals("COMPENSATED", resolved.path("status").asString());
		assertEquals(1,
				jdbc.queryForObject("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='WRITTEN_OFF'",
						Integer.class, tenant));
		assertEquals(1,
				jdbc.queryForObject("SELECT issued FROM benefit_definition WHERE tenant_id=?", Integer.class, tenant));
	}

	@Test
	void refundBeforeDelayedGrantCannotResurrectRevokedEntitlement() throws Exception {
		var order = entitlementOrder();
		payOrder(order);
		jdbc.update(
				"UPDATE platform_event SET available_at=? WHERE tenant_id=? AND event_type='benefit.grant.requested.v1'",
				java.sql.Timestamp.from(Instant.now().plusSeconds(3600)), tenant);
		var refund = approve(requestReturn(order, 1, "r"), "a");
		finishRefund(refund, "refund");
		pump();
		assertEquals("REVOKED",
				jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?", String.class, tenant));
		jdbc.update(
				"UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=? AND event_type='benefit.grant.requested.v1'",
				tenant);
		pump();
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='GRANT'",
				Integer.class, tenant));
		assertEquals(0, jdbc.queryForObject("SELECT remaining_units FROM benefit_grant WHERE tenant_id=?",
				Integer.class, tenant));
	}

	@Test
	void expiredOrForeignEntitlementCannotBeConsumed() throws Exception {
		var grant = granted(entitlementOrder());
		String id = grant.path("grantId").asString();
		assertEquals(404,
				call("POST", "/v1/entitlements/" + id + "/consume", other, "foreign", Map.of("units", 1)).status());
		assertEquals(404, call("GET", "/v1/entitlements/" + id + "/ledger", other, null, null).status());
		jdbc.update("UPDATE benefit_grant SET expires_at=? WHERE tenant_id=?",
				java.sql.Timestamp.from(Instant.now().minusSeconds(1)), tenant);
		assertEquals(409,
				call("POST", "/v1/entitlements/" + id + "/consume", member, "expired", Map.of("units", 1)).status());
		assertEquals(3, jdbc.queryForObject("SELECT remaining_units FROM benefit_grant WHERE tenant_id=?",
				Integer.class, tenant));
	}

	private Map<String, Object> journey(String id, String trigger, int duration, List<Map<String, Object>> nodes) {
		return Map.of("journeyId", id, "version", 1, "storeId", "store1", "name", "会员关怀旅程", "trigger", trigger,
				"validFrom", Instant.now().minusSeconds(30).toString(), "validTo",
				Instant.now().plusSeconds(300).toString(), "maxDurationSeconds", duration, "entry",
				nodes.getFirst().get("id"), "nodes", nodes);
	}

	private Map<String, Object> endNode() {
		return Map.of("id", "end", "kind", "END");
	}

	private Map<String, Object> noticeNode(String next) {
		return Map.of("id", "notice", "kind", "NOTIFY", "title", "会员关怀", "body", "权益已进入您的账户", "next", next);
	}

	private void publishJourney(Map<String, Object> definition) throws Exception {
		String id = (String) definition.get("journeyId");
		post("/v1/admin/journeys", admin, "create-" + id, definition);
		int version = 0;
		for (String action : List.of("submit", "approve", "publish"))
			post("/v1/admin/journeys/" + id + "/1/" + action, admin, action + "-" + id,
					Map.of("expectedVersion", version++));
	}

	private JsonNode enroll(String id, String event) throws Exception {
		return post("/v1/admin/journey-instances", admin, "enroll-" + event,
				Map.of("journeyId", id, "version", 1, "memberId", "m1", "eventKey", event));
	}

	private void journeyPump() throws Exception {
		post("/v1/admin/journeys/pump", admin, null, null);
	}

	private void journeyBenefit(int quota) throws Exception {
		post("/v1/admin/entitlement-definitions", admin, "journey-benefit",
				Map.of("benefitId", "journey-credit", "version", 1, "storeId", "store1", "name", "旅程体验权益", "units", 2,
						"quota", quota, "validFrom", Instant.now().minusSeconds(120).toString(), "validTo",
						Instant.now().plusSeconds(7200).toString(), "validityDays", 1));
	}

	private Map<String, Object> grantNode(String next) {
		return Map.of("id", "grant", "kind", "GRANT", "benefit", Map.of("benefitId", "journey-credit", "version", 1),
				"next", next);
	}

	private String journeyState(String id) {
		return jdbc.queryForObject("SELECT status FROM journey_instance WHERE tenant_id=? AND instance_id=?",
				String.class, tenant, id);
	}

	@Test
	void journeyRejectsCyclesUnreachableNodesAndUntrustedRules() throws Exception {
		seed();
		var cycle = journey("cycle", "MANUAL", 60,
				List.of(Map.of("id", "wait", "kind", "WAIT", "seconds", 1, "next", "wait")));
		assertEquals(400, call("POST", "/v1/admin/journeys", admin, "cycle", cycle).status());
		assertEquals(400,
				call("POST", "/v1/admin/journeys", admin, "unreachable",
						journey("unreachable", "MANUAL", 60, List.of(endNode(), Map.of("id", "orphan", "kind", "END"))))
					.status());
		var rule = Map.of("kind", "COMPARE", "field", "clientDiscount", "operator", "EQ", "valueType", "TEXT", "value",
				"VIP");
		assertEquals(400,
				call("POST", "/v1/admin/journeys", admin, "rule", journey("rule", "MANUAL", 60, List.of(
						Map.of("id", "check", "kind", "DECIDE", "rule", rule, "yesNext", "end", "noNext", "end"),
						endNode())))
					.status());
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM journey_definition WHERE tenant_id=?", Integer.class,
				tenant));
	}

	/** 预览走真实规则与历史图，但不会入组、发放或消耗额度。 */
	@Test
	void journeyValidationAndPreviewAreTenantScopedAndHaveNoSideEffects() throws Exception {
		seed();
		journeyBenefit(1);
		var rule = Map.of("kind", "COMPARE", "field", "memberLevel", "operator", "EQ", "valueType", "TEXT", "value", "VIP");
		var definition = journey("preview", "MANUAL", 120, List.of(
				Map.of("id", "check", "kind", "DECIDE", "rule", rule, "yesNext", "grant", "noNext", "end"),
				grantNode("end"), endNode()));
		var checked = post("/v1/admin/journeys/validate", admin, null, definition);
		assertTrue(checked.path("valid").asBoolean());
		var invalid = post("/v1/admin/journeys/validate", admin, null,
				journey("bad-preview", "MANUAL", 120, List.of(Map.of("id", "wait", "kind", "WAIT", "seconds", 1, "next", "wait"))));
		assertEquals("CYCLE_NOT_SUPPORTED", invalid.path("issues").get(0).path("code").asString());
		publishJourney(definition);
		var preview = post("/v1/admin/journeys/preview/1/preview", admin, null, Map.of("memberId", "m1"));
		assertEquals("COMPLETED", preview.path("stopReason").asString());
		assertEquals("MATCH", preview.path("path").get(0).path("decision").asString());
		assertEquals("ACTION_NOT_EXECUTED", preview.path("path").get(1).path("decision").asString());
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM journey_instance WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_grant WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(403, call("POST", "/v1/admin/journeys/preview/1/preview", member, null, Map.of("memberId", "m1")).status());
		assertEquals(404, call("POST", "/v1/admin/journeys/preview/1/preview", token("foreign-" + tenant, "admin", "ADMIN"), null, Map.of("memberId", "m1")).status());
	}

	/** 未来的会员事实不在当前预览中假装可预测，未知enum也不能写成定义。 */
	@Test
	void journeyPreviewStopsAtDurableWaitAndRejectsUnknownNode() throws Exception {
		seed();
		publishJourney(journey("future-preview", "MANUAL", 120, List.of(
				Map.of("id", "wait", "kind", "WAIT", "seconds", 30, "next", "notice"), noticeNode("end"), endNode())));
		var preview = post("/v1/admin/journeys/future-preview/1/preview", admin, null, Map.of("memberId", "m1"));
		assertEquals("FUTURE_DEPENDENT", preview.path("stopReason").asString());
		assertEquals(1, preview.path("path").size());
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM journey_notification WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(400, call("POST", "/v1/admin/journeys", admin, "unknown-node",
				journey("unknown-node", "MANUAL", 120, List.of(Map.of("id", "unknown", "kind", "SCRIPT")))).status());
	}

	/** 依赖在审批后发生异常，发布必须重检而非沿用创建时资格。 */
	@Test
	void journeyPublicationRechecksFixedBenefitReferences() throws Exception {
		seed();
		journeyBenefit(1);
		post("/v1/admin/journeys", admin, "create-stale-reference",
				journey("stale-reference", "MANUAL", 120, List.of(grantNode("end"), endNode())));
		post("/v1/admin/journeys/stale-reference/1/submit", admin, "submit-stale-reference", Map.of("expectedVersion", 0));
		post("/v1/admin/journeys/stale-reference/1/approve", admin, "approve-stale-reference", Map.of("expectedVersion", 1));
		jdbc.update("UPDATE benefit_definition SET valid_to=UTC_TIMESTAMP(6) WHERE tenant_id=? AND benefit_id='journey-credit'", tenant);
		assertEquals(409, call("POST", "/v1/admin/journeys/stale-reference/1/publish", admin, "publish-stale-reference", Map.of("expectedVersion", 2)).status());
		assertEquals("APPROVED", jdbc.queryForObject("SELECT status FROM journey_definition WHERE tenant_id=?", String.class, tenant));
	}

	@Test
	void journeyRequiresApprovalAndPersistsWaitBeforeExactlyOneNotification() throws Exception {
		seed();
		var definition = journey("welcome", "MANUAL", 120, List
			.of(Map.of("id", "wait", "kind", "WAIT", "seconds", 30, "next", "notice"), noticeNode("end"), endNode()));
		post("/v1/admin/journeys", admin, "create", definition);
		assertEquals(409,
				call("POST", "/v1/admin/journeys/welcome/1/publish", admin, "early", Map.of("expectedVersion", 0))
					.status());
		int version = 0;
		for (String action : List.of("submit", "approve", "publish"))
			post("/v1/admin/journeys/welcome/1/" + action, admin, action, Map.of("expectedVersion", version++));
		var instance = enroll("welcome", "event1");
		assertEquals(instance, enroll("welcome", "event1"));
		String id = instance.path("instanceId").asString();
		journeyPump();
		journeyPump();
		assertEquals("WAITING", journeyState(id));
		assertEquals(0, call("GET", "/v1/notifications", member, null, null).body().size());
		jdbc.update("UPDATE journey_instance SET due_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=?", tenant);
		journeyPump();
		journeyPump();
		journeyPump();
		assertEquals("COMPLETED", journeyState(id));
		assertEquals(1, call("GET", "/v1/notifications", member, null, null).body().size());
		assertEquals(3,
				jdbc.queryForObject("SELECT steps FROM journey_instance WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(403, call("POST", "/v1/admin/journey-instances", member, "forbidden",
				Map.of("journeyId", "welcome", "version", 1, "memberId", "m1", "eventKey", "e2"))
			.status());
	}

	@Test
	void journeyUnknownFactStopsWithoutTakingAwardBranch() throws Exception {
		seed();
		var rule = Map.of("kind", "COMPARE", "field", "orderAmount", "operator", "GTE", "valueType", "DECIMAL", "value",
				"1");
		publishJourney(journey("unknown", "MANUAL", 120,
				List.of(Map.of("id", "check", "kind", "DECIDE", "rule", rule, "yesNext", "notice", "noNext", "end"),
						noticeNode("end"), endNode())));
		var instance = enroll("unknown", "event");
		journeyPump();
		assertEquals("COMPLETED", journeyState(instance.path("instanceId").asString()));
		assertEquals("RULE_UNKNOWN",
				jdbc.queryForObject("SELECT result FROM journey_instance WHERE tenant_id=?", String.class, tenant));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM journey_notification WHERE tenant_id=?",
				Integer.class, tenant));
	}

	@Test
	void journeyCancelAndDeadlinePreventFutureEffectsAndEnforceOwnership() throws Exception {
		seed();
		publishJourney(journey("stop", "MANUAL", 120, List.of(noticeNode("end"), endNode())));
		String first = enroll("stop", "one").path("instanceId").asString(),
				second = enroll("stop", "two").path("instanceId").asString();
		assertEquals(404, call("POST", "/v1/admin/journey-instances/" + first + "/cancel",
				token("foreign-" + UUID.randomUUID(), "admin", "ADMIN"), "cancel", null)
			.status());
		post("/v1/admin/journey-instances/" + first + "/cancel", admin, "cancel", null);
		jdbc.update("UPDATE journey_instance SET deadline=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND instance_id=?",
				tenant, second);
		journeyPump();
		assertEquals("CANCELLED", journeyState(first));
		assertEquals("TIMED_OUT", journeyState(second));
		assertEquals(0, call("GET", "/v1/notifications", member, null, null).body().size());
	}

	/** WAIT后读取当前会员事实；成功历史、固定版本与真实权益来源能够相互核对。 */
	@Test
	void journeyHistoryRecordsWaitDecisionAndAcceptedActionWithStablePagination() throws Exception {
		seed();
		journeyBenefit(1);
		var rule = Map.of("kind", "COMPARE", "field", "memberLevel", "operator", "EQ", "valueType", "TEXT", "value", "VIP");
		publishJourney(journey("history", "MANUAL", 120, List.of(
				Map.of("id", "wait", "kind", "WAIT", "seconds", 30, "next", "decide"),
				Map.of("id", "decide", "kind", "DECIDE", "rule", rule, "yesNext", "grant", "noNext", "end"),
				grantNode("end"), endNode())));
		String id = enroll("history", "source").path("instanceId").asString();
		assertEquals(id, enroll("history", "source").path("instanceId").asString());
		journeyPump();
		var wait = postlessHistory(id, admin);
		assertEquals("COMPLETE", wait.path("traceCoverage").asString());
		assertEquals("source", wait.path("triggerKey").asString());
		assertEquals("WAITING", wait.path("steps").get(0).path("status").asString());
		assertEquals("wait", wait.path("steps").get(0).path("nodeId").asString());
		assertEquals("decide", wait.path("instance").path("currentNode").asString());
		assertNotEquals("", wait.path("steps").get(0).path("wakeAt").asString());
		journeyPump();
		assertEquals(1, postlessHistory(id, member).path("steps").size());
		jdbc.update("UPDATE journey_instance SET due_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND instance_id=?", tenant, id);
		journeyPump();
		journeyPump();
		journeyPump();
		var history = postlessHistory(id, member);
		assertEquals("COMPLETED", history.path("instance").path("status").asString());
		assertEquals(4, history.path("steps").size());
		assertEquals("MATCH", history.path("steps").get(1).path("decision").asString());
		assertEquals("BENEFIT_ACCEPTED", history.path("steps").get(2).path("outcome").asString());
		assertEquals(jdbc.queryForObject("SELECT grant_id FROM benefit_grant WHERE tenant_id=?", String.class, tenant),
				history.path("steps").get(2).path("actionRef").asString());
		assertEquals("FINISHED", history.path("steps").get(3).path("outcome").asString());
		var page = call("GET", "/v1/journey-instances/" + id + "/history?afterVersion=0&limit=1", member, null, null);
		assertEquals(200, page.status());
		assertEquals("COMPLETE", page.body().path("traceCoverage").asString());
		assertEquals(1, page.body().path("steps").size());
		assertEquals(1, page.body().path("steps").get(0).path("transitionVersion").asLong());
		assertEquals(404, call("GET", "/v1/admin/journey-instances/" + id + "/history", token("foreign-" + tenant, "admin", "ADMIN"), null, null).status());
		assertEquals(400, call("GET", "/v1/admin/journey-instances/" + id + "/history?limit=51", admin, null, null).status());
		assertEquals(401, call("GET", "/v1/journey-instances/" + id + "/history", null, null, null).status());
		pump();
		assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?", String.class, tenant));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='GRANT'", Integer.class, tenant));
	}

	private JsonNode postlessHistory(String id, String token) throws Exception {
		var reply = call("GET", (token.equals(admin) ? "/v1/admin/journey-instances/" : "/v1/journey-instances/") + id + "/history", token, null, null);
		assertEquals(200, reply.status(), reply.body().toString());
		return reply.body();
	}

	/** 旧写入没有完整证据，扩展迁移不推测或回填历史。 */
	@Test
	void legacyJourneyHistoryExplicitlyReportsPartialCoverage() throws Exception {
		seed();
		publishJourney(journey("legacy-history", "MANUAL", 120, List.of(noticeNode("end"), endNode())));
		String id = enroll("legacy-history", "old").path("instanceId").asString();
		jdbc.update("UPDATE journey_instance SET trace_origin_version=NULL WHERE tenant_id=? AND instance_id=?", tenant, id);
		journeyPump();
		var history = postlessHistory(id, admin);
		assertEquals("LEGACY_PARTIAL", history.path("traceCoverage").asString());
		assertEquals(1, history.path("steps").size());
		assertEquals("NOTIFIED", history.path("steps").get(0).path("outcome").asString());
		assertFalse(history.path("steps").get(0).path("actionRef").asString().isBlank());
	}

	@Test
	void concurrentJourneyWorkersCommitOneGrantAndResumeNextNode() throws Exception {
		seed();
		journeyBenefit(1);
		publishJourney(journey("grant", "MANUAL", 120, List.of(grantNode("notice"), noticeNode("end"), endNode())));
		var instance = enroll("grant", "one");
		try (var pool = Executors.newFixedThreadPool(2)) {
			var a = pool.submit(() -> {
				journeyPump();
				return true;
			});
			var b = pool.submit(() -> {
				journeyPump();
				return true;
			});
			a.get();
			b.get();
		}
		journeyPump();
		journeyPump();
		pump();
		assertEquals("COMPLETED", journeyState(instance.path("instanceId").asString()));
		assertEquals(1, jdbc.queryForObject(
				"SELECT COUNT(*) FROM benefit_grant WHERE tenant_id=? AND source_type='JOURNEY' AND order_id IS NULL",
				Integer.class, tenant));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='GRANT'",
				Integer.class, tenant));
		assertEquals(1, call("GET", "/v1/notifications", member, null, null).body().size());
	}

	@Test
	void failedJourneyNodeRetriesBoundedlyAndManualRetryKeepsCheckpoint() throws Exception {
		seed();
		journeyBenefit(1);
		publishJourney(journey("limited", "MANUAL", 120, List.of(grantNode("end"), endNode())));
		enroll("limited", "first");
		journeyPump();
		journeyPump();
		var blocked = enroll("limited", "second");
		String id = blocked.path("instanceId").asString();
		for (int i = 0; i < 5; i++) {
			jdbc.update("UPDATE journey_instance SET due_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND instance_id=?",
					tenant, id);
			journeyPump();
		}
		assertEquals("ISOLATED", journeyState(id));
		var failures = postlessHistory(id, admin).path("steps");
		assertEquals(5, failures.size());
		for (int i = 0; i < 5; i++) {
			assertEquals(i, failures.get(i).path("transitionVersion").asLong());
			assertEquals(1, failures.get(i).path("ordinal").asInt());
			assertEquals("grant", failures.get(i).path("nodeId").asString());
			assertEquals(i == 4 ? "ISOLATED" : "FAILED", failures.get(i).path("status").asString());
			assertTrue(failures.get(i).path("actionRef").isNull());
		}
		assertEquals(0, jdbc.queryForObject("SELECT steps FROM journey_instance WHERE tenant_id=? AND instance_id=?",
				Integer.class, tenant, id));
		assertEquals(1,
				jdbc.queryForObject("SELECT COUNT(*) FROM benefit_grant WHERE tenant_id=?", Integer.class, tenant));
		post("/v1/admin/journey-instances/" + id + "/retry", admin, "retry", null);
		assertEquals("RUNNING", journeyState(id));
		jdbc.update("UPDATE journey_instance SET deadline=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND instance_id=?",
				tenant, id);
		journeyPump();
		assertEquals("TIMED_OUT", journeyState(id));
	}

	/** 真支付事件进入固定WAIT，再按当前事实分支，权益受理和最终台账均核对。 */
	@Test
	void paidJourneyCompletesTriggerWaitCurrentDecisionCreditAndHistoryExactlyOnce() throws Exception {
		var order = pendingOrder();
		journeyBenefit(1);
		var rule = Map.of("kind", "COMPARE", "field", "memberLevel", "operator", "EQ", "valueType", "TEXT", "value", "VIP");
		publishJourney(journey("paid-full", "ORDER_PAID", 120, List.of(
				Map.of("id", "wait", "kind", "WAIT", "seconds", 30, "next", "decide"),
				Map.of("id", "decide", "kind", "DECIDE", "rule", rule, "yesNext", "grant", "noNext", "end"),
				grantNode("end"), endNode())));
		payOrder(order);
		pump();
		String eventId = jdbc.queryForObject("SELECT event_id FROM platform_event WHERE tenant_id=? AND event_type='order.paid.v1'", String.class, tenant);
		var event = eventMapper.find(tenant, eventId);
		var redelivery = new com.lrj.commerce.runtime.api.event.EventHandler.Event(UUID.randomUUID().toString(),
				event.tenantId(), event.eventType(), event.aggregateId(), event.aggregateVersion(), event.payloadJson(), event.createdAt(), 0, 0);
		new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(s -> journeyService.handle(redelivery));
		assertFalse(journeyService.replaySafety().historicalReplay());
		String id = jdbc.queryForObject("SELECT instance_id FROM journey_instance WHERE tenant_id=?", String.class, tenant);
		journeyPump();
		assertEquals("WAITING", journeyState(id));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_grant WHERE tenant_id=?", Integer.class, tenant));
		pump();
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM journey_instance WHERE tenant_id=?", Integer.class, tenant));
		jdbc.update("UPDATE journey_instance SET due_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND instance_id=?", tenant, id);
		for (int step = 0; step < 3; step++)
			journeyPump();
		pump();
		assertEquals("COMPLETED", journeyState(id));
		var trace = postlessHistory(id, admin);
		assertEquals(order.path("orderId").asString(), trace.path("triggerKey").asString());
		assertEquals("MATCH", trace.path("steps").get(1).path("decision").asString());
		assertEquals(4, trace.path("steps").size());
		assertEquals("AVAILABLE", jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?", String.class, tenant));
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_ledger WHERE tenant_id=? AND action='GRANT'", Integer.class, tenant));
	}

	@Test
	void waitBranchUsesCurrentTagsInsteadOfEnrollmentFacts() throws Exception {
		seed();
		journeyBenefit(1);
		post("/v1/admin/member-tags", admin, "tag", Map.of("tagId", "loyal", "name", "忠诚会员"));
		post("/v1/admin/member-tags/m1/assign", admin, "assign", Map.of("tagId", "loyal", "active", true, "expectedVersion", 0, "reason", "等待前"));
		var rule = Map.of("kind", "COMPARE", "field", "memberTags", "operator", "CONTAINS", "valueType", "TEXT", "value", "loyal");
		publishJourney(journey("current-facts", "MANUAL", 120, List.of(
				Map.of("id", "wait", "kind", "WAIT", "seconds", 30, "next", "decide"),
				Map.of("id", "decide", "kind", "DECIDE", "rule", rule, "yesNext", "grant", "noNext", "end"),
				grantNode("end"), endNode())));
		String id = enroll("current-facts", "tagged").path("instanceId").asString();
		journeyPump();
		post("/v1/admin/member-tags/m1/assign", admin, "remove", Map.of("tagId", "loyal", "active", false, "expectedVersion", 1, "reason", "等待中撤销"));
		jdbc.update("UPDATE journey_instance SET due_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND instance_id=?", tenant, id);
		journeyPump();
		journeyPump();
		var trace = postlessHistory(id, admin);
		assertEquals("NO_MATCH", trace.path("steps").get(1).path("decision").asString());
		assertEquals("end", trace.path("steps").get(1).path("nextNode").asString());
		assertEquals("COMPLETED", journeyState(id));
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_grant WHERE tenant_id=?", Integer.class, tenant));
	}

	@Test
	void paidJourneyGrantsAreReversedAndLaterNodesCancelledAfterFullRefund() throws Exception {
		var order = pendingOrder();
		journeyBenefit(1);
		publishJourney(journey("paid", "ORDER_PAID", 120, List.of(grantNode("wait"),
				Map.of("id", "wait", "kind", "WAIT", "seconds", 60, "next", "notice"), noticeNode("end"), endNode())));
		payOrder(order);
		pump();
		journeyPump();
		pump();
		journeyPump();
		assertEquals("AVAILABLE",
				jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?", String.class, tenant));
		var refund = approve(requestReturn(order, 1, "r"), "a");
		finishRefund(refund, "refund");
		pump();
		assertEquals("REVOKED",
				jdbc.queryForObject("SELECT status FROM benefit_grant WHERE tenant_id=?", String.class, tenant));
		assertEquals("CANCELLED",
				jdbc.queryForObject("SELECT status FROM journey_instance WHERE tenant_id=?", String.class, tenant));
		journeyPump();
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM journey_notification WHERE tenant_id=?",
				Integer.class, tenant));
	}

	@Test
	void latePaidJourneyEventAfterRefundDoesNotEnroll() throws Exception {
		var order = pendingOrder();
		publishJourney(journey("late", "ORDER_PAID", 120, List.of(noticeNode("end"), endNode())));
		payOrder(order);
		jdbc.update("UPDATE platform_event SET available_at=? WHERE tenant_id=? AND event_type='order.paid.v1'",
				java.sql.Timestamp.from(Instant.now().plusSeconds(3600)), tenant);
		var refund = approve(requestReturn(order, 1, "r"), "a");
		finishRefund(refund, "refund");
		pump();
		jdbc.update(
				"UPDATE platform_event SET available_at=CURRENT_TIMESTAMP(6) WHERE tenant_id=? AND event_type='order.paid.v1'",
				tenant);
		pump();
		assertEquals(0,
				jdbc.queryForObject("SELECT COUNT(*) FROM journey_instance WHERE tenant_id=?", Integer.class, tenant));
	}

	private Map<String, Object> opsPage(long version) {
		return Map.of("pageId", "marketing-desk", "version", version, "title", "营销工作台", "storeId", "store1", "sections",
				List.of(Map.of("id", "coupons", "title", "优惠券", "source", "COUPONS")), "actions",
				List.of(Map.of("id", "create-coupon", "label", "新建券", "kind", "CREATE_COUPON")));
	}

	private void publishPage(long version) throws Exception {
		post("/v1/admin/ops-pages", admin, "page-" + version, opsPage(version));
		int expected = 0;
		for (String action : List.of("submit", "approve", "publish"))
			post("/v1/admin/ops-pages/marketing-desk/" + version + "/" + action, admin, action + "-page-" + version,
					Map.of("expectedVersion", expected++));
	}

	private Map<String, Object> pageCoupon() {
		return Map.of("definitionId", "ops-coupon", "version", 1, "storeId", "store1", "name", "页面创建优惠", "minimumSpend",
				"10.00", "discountAmount", "2.00", "validFrom", Instant.now().minusSeconds(30).toString(), "validTo",
				Instant.now().plusSeconds(3600).toString(), "quota", 10, "stackable", true);
	}

	@Test
	void lowcodePreviewReadsDatabaseWithoutSavingPageOrCommands() throws Exception {
		seed();
		couponDefinition("2.00", true, 10);
		int before = jdbc.queryForObject("SELECT COUNT(*) FROM platform_command WHERE tenant_id=?", Integer.class,
				tenant);
		var preview = post("/v1/admin/ops-pages/preview", admin, null, opsPage(1));
		assertTrue(preview.path("preview").asBoolean());
		assertTrue(preview.path("bounded").asBoolean());
		assertEquals(1, preview.path("data").get(0).path("rows").size());
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ops_page WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(before,
				jdbc.queryForObject("SELECT COUNT(*) FROM platform_command WHERE tenant_id=?", Integer.class, tenant));
		assertEquals(403, call("POST", "/v1/admin/ops-pages/preview", member, null, opsPage(1)).status());
	}

	@Test
	void lowcodeRejectsUnknownDataSourceAndDuplicateComponentIds() throws Exception {
		seed();
		var bad = new HashMap<>(opsPage(1));
		bad.put("sections", List.of(Map.of("id", "bad", "title", "不受控源", "source", "https://example.com/private")));
		assertEquals(400, call("POST", "/v1/admin/ops-pages", admin, "bad-source", bad).status());
		bad.put("sections", List.of(Map.of("id", "create-coupon", "title", "冲突标识", "source", "COUPONS")));
		assertEquals(400, call("POST", "/v1/admin/ops-pages", admin, "bad-id", bad).status());
		var action = new HashMap<>(opsPage(1));
		action.put("actions", List.of(Map.of("id", "run", "label", "脚本", "kind", "RUN_SCRIPT")));
		assertEquals(400, call("POST", "/v1/admin/ops-pages", admin, "bad-action", action).status());
	}

	@Test
	void lowcodeActionRequiresPublishedDeclaredVersionAndIsIdempotent() throws Exception {
		seed();
		post("/v1/admin/ops-pages", admin, "draft", opsPage(1));
		var input = Map.of("coupon", pageCoupon());
		String path = "/v1/admin/ops-pages/marketing-desk/1/actions/create-coupon";
		assertEquals(409, call("POST", path, admin, "execute", input).status());
		assertEquals(409, call("POST", "/v1/admin/ops-pages/marketing-desk/1/publish", admin, "skip-review",
				Map.of("expectedVersion", 0))
			.status());
		int expected = 0;
		for (String action : List.of("submit", "approve", "publish"))
			post("/v1/admin/ops-pages/marketing-desk/1/" + action, admin, action,
					Map.of("expectedVersion", expected++));
		var result = post(path, admin, "execute", input);
		assertEquals(result, post(path, admin, "execute", input));
		assertEquals("ops-coupon", result.path("resourceId").asString());
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon_definition WHERE tenant_id=?",
				Integer.class, tenant));
		assertEquals(404,
				call("POST", "/v1/admin/ops-pages/marketing-desk/1/actions/undeclared", admin, "no-action", input)
					.status());
		assertEquals(403, call("POST", path, member, "forbidden", input).status());
	}

	@Test
	void lowcodeVersionRollbackRestoresPageWithoutUndoingBusinessData() throws Exception {
		seed();
		publishPage(1);
		post("/v1/admin/ops-pages/marketing-desk/1/actions/create-coupon", admin, "execute",
				Map.of("coupon", pageCoupon()));
		publishPage(2);
		assertEquals(2,
				call("GET", "/v1/admin/ops-pages/marketing-desk/render", admin, null, null).body()
					.path("page")
					.path("content")
					.path("version")
					.asInt());
		assertEquals(409, call("POST", "/v1/admin/ops-pages/marketing-desk/1/actions/create-coupon", admin, "old-page",
				Map.of("coupon", pageCoupon()))
			.status());
		post("/v1/admin/ops-pages/marketing-desk/1/rollback", admin, "rollback", Map.of("expectedVersion", 4));
		var render = call("GET", "/v1/admin/ops-pages/marketing-desk/render", admin, null, null).body();
		assertEquals(1, render.path("page").path("content").path("version").asInt());
		assertEquals(1, render.path("data").get(0).path("rows").size());
		assertEquals(2, call("GET", "/v1/admin/ops-pages/marketing-desk/versions", admin, null, null).body().size());
		assertEquals(404, call("GET", "/v1/admin/ops-pages/marketing-desk/render",
				token("other-" + UUID.randomUUID(), "admin", "ADMIN"), null, null)
			.status());
	}

	@Test
	void lowcodeActionCannotChangeStoreOrSmuggleAnotherCommandType() throws Exception {
		seed();
		publishPage(1);
		var coupon = new HashMap<>(pageCoupon());
		coupon.put("storeId", "other-store");
		String path = "/v1/admin/ops-pages/marketing-desk/1/actions/create-coupon";
		assertEquals(400, call("POST", path, admin, "other-store", Map.of("coupon", coupon)).status());
		assertEquals(400, call("POST", path, admin, "mixed", Map.of("coupon", pageCoupon(), "enrollment",
				Map.of("journeyId", "j", "version", 1, "memberId", "m1", "eventKey", "e")))
			.status());
		assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM benefit_coupon_definition WHERE tenant_id=?",
				Integer.class, tenant));
	}

	@Test
	void consoleReadsRespectAdminTenantAndKeepMemberOrdersPrivate() throws Exception {
		var order = pendingOrder();
		String id = order.path("orderId").asString();
		var payment = startPayment(order);
		assertEquals(1, call("GET", "/v1/stores", member, null, null).body().size());
		assertEquals(1, call("GET", "/v1/admin/orders", admin, null, null).body().size());
		assertEquals(id, call("GET", "/v1/admin/orders/" + id, admin, null, null).body().path("orderId").asString());
		assertFalse(call("GET", "/v1/admin/orders/" + id, admin, null, null).body().has("address"));
		assertEquals(403, call("GET", "/v1/admin/journey-instances", member, null, null).status());
		assertEquals(403, call("GET", "/v1/admin/coupon-definitions?storeId=store1", member, null, null).status());
		assertEquals(403, call("GET", "/v1/admin/orders", member, null, null).status());
		assertEquals(403, call("GET", "/v1/admin/orders/" + id + "/payment", member, null, null).status());
		String foreign = token("foreign-" + UUID.randomUUID(), "admin", "ADMIN");
		assertEquals(404, call("GET", "/v1/admin/orders/" + id, foreign, null, null).status());
		assertEquals(404, call("POST", "/v1/admin/orders/" + id + "/payment/reconcile", foreign, null, null).status());
		sandbox(payment, "PAID");
		assertEquals("PAID",
				post("/v1/admin/orders/" + id + "/payment/reconcile", admin, null, null).path("status").asString());
		pump();
		assertEquals("PAID", call("GET", "/v1/admin/orders/" + id, admin, null, null).body().path("status").asString());
		assertTrue(
				call("GET", "/v1/runtime-capabilities", member, null, null).body().path("sandboxEnabled").asBoolean());
		assertEquals(401, call("GET", "/v1/runtime-capabilities", null, null, null).status());
	}

	@Test
	void protocolErrorsDoNotBecomeInternalServerFailures() throws Exception {
		assertEquals(400, call("GET", "/v1/catalog", member, null, null).status());
		// 未登记路径由安全层默认拒绝；管理命名空间内不存在的资源仍是404。
		assertEquals(403, call("GET", "/v1/no-such-endpoint", member, null, null).status());
		assertEquals(404, call("GET", "/v1/admin/no-such-endpoint", admin, null, null).status());
		assertEquals(405, call("POST", "/v1/me", member, null, null).status());
	}

	@Test
	void everyBusinessTableAndColumnHasComments() {
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' AND TABLE_COMMENT=''",
				Integer.class));
		assertEquals(0, jdbc.queryForObject(
				"SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME<>'flyway_schema_history' AND COLUMN_COMMENT=''",
				Integer.class));
	}

}
