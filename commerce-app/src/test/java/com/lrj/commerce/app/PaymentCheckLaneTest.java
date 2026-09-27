package com.lrj.commerce.app;

import com.lrj.commerce.payment.infrastructure.adapter.SandboxPaymentChannel;
import com.lrj.commerce.payment.infrastructure.persistence.PaymentMapper;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.payment.api.*;
import com.lrj.commerce.payment.application.PaymentService;
import com.lrj.commerce.payment.infrastructure.persistence.*;
import com.lrj.commerce.runtime.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 支付核对车道：渠道暂不可用不消耗五次自动核对（旧实现约2分钟就转人工），恢复后确认真实结果；
 * 渠道证据冲突等非瞬时失败仍消耗核对次数；多个租户连续渠道故障熔断车道而不是逐笔耗尽。
 */
@SpringBootTest
class PaymentCheckLaneTest {

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
	PaymentMapper mapper;

	@Autowired
	OrderApi orders;

	@Autowired
	Commands commands;

	@Autowired
	Outbox outbox;

	@Autowired
	PlatformTransactionManager transactions;

	@Autowired
	SandboxPaymentChannel sandbox;

	private String prefix;

	private final Set<String> down = Collections.synchronizedSet(new HashSet<>());

	@BeforeEach
	void run() {
		prefix = "px-" + UUID.randomUUID().toString().substring(0, 8) + "-";
	}

	@AfterEach
	void clean() {
		for (String table : List.of("platform_event", "payment_sandbox_ledger", "payment_attempt", "order_record"))
			jdbc.update("DELETE FROM " + table + " WHERE tenant_id LIKE ?", prefix + "%");
	}

	/** 场景E（支付）：渠道不可用8次，旧实现第5次后自动核对即用尽；现在核对次数不变，恢复后确认成功。 */
	@Test
	void channelOutageDoesNotExhaustAutomaticChecks() {
		String tenant = prefix + "a";
		String payment = seed(tenant);
		down.add(tenant);
		for (int i = 0; i < 8; i++) {
			due(tenant);
			service().tick();
		}
		assertEquals(0, value(tenant, "check_attempts"), "瞬时失败退回领取的核对次数");
		assertEquals(8, value(tenant, "check_transient_failures"));
		assertEquals("DEPENDENCY_UNAVAILABLE:DomainException/UNAVAILABLE",
				jdbc.queryForObject("SELECT check_error FROM payment_attempt WHERE tenant_id=?", String.class, tenant));
		assertTrue(jdbc.queryForObject(
				"SELECT next_check_at>TIMESTAMPADD(SECOND,200,CURRENT_TIMESTAMP(3)) FROM payment_attempt WHERE tenant_id=?",
				Boolean.class, tenant), "瞬时退避按指数增长");
		// 渠道恢复且实际已付款：自动核对确认成功，不需要人工。
		down.clear();
		jdbc.update(
				"UPDATE payment_sandbox_ledger SET status='PAID',transaction_id=? WHERE tenant_id=? AND payment_id=?",
				"tx-" + payment, tenant, payment);
		due(tenant);
		service().tick();
		assertEquals("PAID",
				jdbc.queryForObject("SELECT status FROM payment_attempt WHERE tenant_id=?", String.class, tenant));
		assertEquals(1,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='payment.paid.v1'",
						Integer.class, tenant));
	}

	/** 渠道证据与支付尝试不符属于业务拒绝：消耗核对次数，5次后停止自动核对，计入车道quarantined。 */
	@Test
	void conflictingEvidenceStillConsumesChecks() {
		String tenant = prefix + "a";
		seed(tenant);
		var service = service();
		service.tick();
		jdbc.update("UPDATE payment_sandbox_ledger SET amount=99.00 WHERE tenant_id=?", tenant);
		for (int i = 0; i < 10 && value(tenant, "check_attempts") < 5; i++) {
			due(tenant);
			service.tick();
		}
		assertEquals(5, value(tenant, "check_attempts"));
		assertEquals(0, value(tenant, "check_transient_failures"));
		assertEquals("BUSINESS_REJECTED:DomainException/CONFLICT",
				jdbc.queryForObject("SELECT check_error FROM payment_attempt WHERE tenant_id=?", String.class, tenant));
		assertTrue(mapper.checkBacklog(RetryPolicy.TRANSIENT.budget()).quarantined() >= 1);
	}

	/** 多个租户连续渠道故障：第3次后熔断，其余租户的支付不被领取、不被计数。 */
	@Test
	void channelOutageAcrossTenantsOpensTheBreaker() {
		var tenants = new ArrayList<String>();
		for (int t = 0; t < 6; t++) {
			String tenant = prefix + "t" + t;
			seed(tenant);
			down.add(tenant);
			tenants.add(tenant);
		}
		var service = service();
		service.tick();
		int touched = 0;
		for (var t : tenants)
			touched += value(t, "check_transient_failures");
		assertTrue(touched <= 3, "熔断前最多3笔瞬时失败：" + touched);
		for (var t : tenants)
			assertEquals(0, value(t, "check_attempts"));
	}

	private PaymentService service() {
		return new PaymentService(mapper, orders, commands, new FlakyChannel(), outbox, transactions, new WorkLanes());
	}

	/** 包装沙箱渠道，只对指定租户模拟渠道不可用（稳定错误码UNAVAILABLE，不看文本）。 */
	private final class FlakyChannel implements PaymentChannel {

		public String provider() {
			return sandbox.provider();
		}

		public void ensure(String tenant, PaymentApi.View payment) {
			sandbox.ensure(tenant, payment);
		}

		public Evidence observe(String tenant, String id) {
			if (down.contains(tenant))
				throw new DomainException(DomainException.Code.UNAVAILABLE, "渠道超时");
			return sandbox.observe(tenant, id);
		}

		public Evidence close(String tenant, String id) {
			if (down.contains(tenant))
				throw new DomainException(DomainException.Code.UNAVAILABLE, "渠道超时");
			return sandbox.close(tenant, id);
		}

	}

	private String seed(String tenant) {
		String order = "o-" + UUID.randomUUID(), payment = "p-" + UUID.randomUUID();
		var now = Instant.now();
		jdbc.update(
				"INSERT INTO order_record(tenant_id,order_id,member_id,store_id,merchant_id,quote_id,payable,status,payment_kind,version,created_at,expires_at,items_json,address_cipher,address_key_version) VALUES(?,?,'m1','s1','mc1',?,10.00,'PAYMENT_IN_PROGRESS','CHANNEL_REQUIRED',1,?,?,'[]',X'00',1)",
				tenant, order, "q-" + order, Timestamp.from(now), Timestamp.from(now.plusSeconds(900)));
		jdbc.update(
				"INSERT INTO payment_attempt(tenant_id,payment_id,order_id,amount,currency,provider,status,version) VALUES(?,?,?,10.00,'CNY','SANDBOX','UNKNOWN',0)",
				tenant, payment, order);
		return payment;
	}

	private void due(String tenant) {
		jdbc.update("UPDATE payment_attempt SET next_check_at=CURRENT_TIMESTAMP(3) WHERE tenant_id=?", tenant);
	}

	private int value(String tenant, String column) {
		return jdbc.queryForObject("SELECT " + column + " FROM payment_attempt WHERE tenant_id=?", Integer.class,
				tenant);
	}

}
