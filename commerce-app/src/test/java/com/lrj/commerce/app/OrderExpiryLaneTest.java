package com.lrj.commerce.app;

import com.lrj.commerce.ordering.infrastructure.security.AddressCipher;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.ordering.api.OrderApi;
import com.lrj.commerce.ordering.application.OrderService;
import com.lrj.commerce.ordering.infrastructure.persistence.OrderMapper;
import com.lrj.commerce.runtime.*;
import com.lrj.commerce.runtime.api.Actor;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 订单到期车道：逐单事务使一个坏订单只影响它自己；租户轮转使热租户不能拖住其他租户；多实例并发每单只取消一次。
 * 订单行直接写入（无预占时释放为空操作），坏订单用终态冲突的库存预占模拟。
 */
@SpringBootTest
class OrderExpiryLaneTest {

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
	OrderApi orders;

	@Autowired
	OrderMapper mapper;

	@Autowired
	ApplicationContext context;

	private String prefix;

	@BeforeEach
	void run() {
		prefix = "ox-" + UUID.randomUUID().toString().substring(0, 8) + "-";
	}

	@AfterEach
	void clean() {
		jdbc.update("DELETE FROM platform_event WHERE tenant_id LIKE ?", prefix + "%");
		jdbc.update("DELETE FROM inventory_hold WHERE tenant_id LIKE ?", prefix + "%");
		jdbc.update("DELETE FROM order_record WHERE tenant_id LIKE ?", prefix + "%");
	}

	/** 场景F（订单到期）：同租户一个坏订单不再让整批20单回滚；5次非瞬时失败后停止自动取消并可审计重试。 */
	@Test
	void poisonOrderOnlyBlocksItselfAndIsQuarantinedWithEvidence() {
		String tenant = prefix + "a";
		String poison = insert(tenant, "poison", 600);
		jdbc.update(
				"INSERT INTO inventory_hold(tenant_id,order_id,store_id,sku_id,quantity,status) VALUES(?,?,'s1','sku1',1,'CONFIRMED')",
				tenant, poison);
		var healthy = new ArrayList<String>();
		for (int i = 0; i < 30; i++)
			healthy.add(insert(tenant, "o" + i, 300));
		for (int i = 0; i < 50 && healthy.stream().anyMatch(id -> !status(tenant, id).equals("CANCELLED")); i++)
			orders.tick();
		for (var id : healthy)
			assertEquals("CANCELLED", status(tenant, id), "坏订单排在最前也不阻塞同租户其他订单");
		assertEquals("PENDING_PAYMENT", status(tenant, poison));
		assertEquals(1, expiry(tenant, poison, "expiry_attempts"));
		assertEquals("BUSINESS_REJECTED:DomainException/CONFLICT", text(tenant, poison, "expiry_error"));
		assertTrue(jdbc.queryForObject(
				"SELECT expiry_retry_at>UTC_TIMESTAMP(3) FROM order_record WHERE tenant_id=? AND order_id=?",
				Boolean.class, tenant, poison), "失败后退避");
		for (int i = 0; i < 20 && expiry(tenant, poison, "expiry_attempts") < 5; i++) {
			jdbc.update("UPDATE order_record SET expiry_retry_at=NULL WHERE tenant_id=? AND order_id=?", tenant,
					poison);
			orders.tick();
		}
		assertEquals(5, expiry(tenant, poison, "expiry_attempts"));
		jdbc.update("UPDATE order_record SET expiry_retry_at=NULL WHERE tenant_id=? AND order_id=?", tenant, poison);
		orders.tick();
		assertEquals(5, expiry(tenant, poison, "expiry_attempts"), "停止自动处理后不再尝试");
		assertTrue(mapper.expiryBacklog(Instant.now(), RetryPolicy.POISON.budget(), RetryPolicy.TRANSIENT.budget())
			.quarantined() >= 1);
		// 修复数据后人工重试：只清计数与退避，最近失败证据保留，随后正常取消。
		var admin = new Actor(tenant, "admin", Actor.Role.ADMIN);
		jdbc.update("DELETE FROM inventory_hold WHERE tenant_id=? AND order_id=?", tenant, poison);
		assertEquals(1, orders.retryExpiry(admin, "retry-" + prefix, poison));
		assertEquals(0, expiry(tenant, poison, "expiry_attempts"));
		assertEquals("BUSINESS_REJECTED:DomainException/CONFLICT", text(tenant, poison, "expiry_error"));
		for (int i = 0; i < 20 && !status(tenant, poison).equals("CANCELLED"); i++)
			orders.tick();
		assertEquals("CANCELLED", status(tenant, poison));
		assertEquals(DomainException.Code.CONFLICT,
				assertThrows(DomainException.class, () -> orders.retryExpiry(admin, "retry2-" + prefix, poison))
					.code());
		assertThrows(DomainException.class,
				() -> orders.retryExpiry(new Actor(tenant, "buyer", Actor.Role.MEMBER), "k", poison));
	}

	/** 场景D（订单到期）：热租户200单与30个小租户各1单，所有小租户完成前热租户最多被处理一个quantum。 */
	@Test
	void hotTenantCannotStarveOtherTenants() {
		for (int i = 0; i < 200; i++)
			insert(prefix + "a-hot", "h" + i, 600);
		var small = new ArrayList<String>();
		for (int t = 0; t < 30; t++) {
			String tenant = prefix + String.format("b-%02d", t);
			insert(tenant, "s", 60);
			small.add(tenant);
		}
		for (int i = 0; i < 200 && small.stream().anyMatch(t -> !status(t, "s").equals("CANCELLED")); i++)
			orders.tick();
		for (var t : small)
			assertEquals("CANCELLED", status(t, "s"));
		// 以取消事件的提交顺序判断：最后一个小租户被处理之前，热租户被处理了多少单。
		int hot = jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_event WHERE tenant_id=? AND event_type='order.cancelled.v1' AND created_at<(SELECT MAX(created_at) FROM platform_event WHERE tenant_id LIKE ? AND tenant_id<>? AND event_type='order.cancelled.v1')",
				Integer.class, prefix + "a-hot", prefix + "%", prefix + "a-hot");
		assertTrue(hot <= OrderService.EXPIRY.quantum(), "小租户全部处理前热租户处理了" + hot + "单");
	}

	/** 多实例并发：4个独立实例各自轮转，SKIP LOCKED领取加状态复核，每单恰好取消一次、只发一个取消事件。 */
	@Test
	void concurrentInstancesExpireEachOrderExactlyOnce() throws Exception {
		for (int t = 0; t < 40; t++)
			for (int i = 0; i < 10; i++)
				insert(prefix + "t-" + t, "o" + i, 300);
		var pool = Executors.newFixedThreadPool(4);
		try {
			var futures = new ArrayList<Future<?>>();
			for (int w = 0; w < 4; w++) {
				var instance = instance();
				futures.add(pool.submit(() -> {
					for (int i = 0; i < 200 && remaining() > 0; i++)
						instance.tick();
				}));
			}
			for (var f : futures)
				f.get(180, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}
		assertEquals(0, remaining());
		assertEquals(400,
				jdbc.queryForObject(
						"SELECT COUNT(*) FROM order_record WHERE tenant_id LIKE ? AND status='CANCELLED' AND version=1",
						Integer.class, prefix + "%"),
				"每单只转换一次");
		assertEquals(400, jdbc.queryForObject(
				"SELECT COUNT(*) FROM platform_event WHERE tenant_id LIKE ? AND event_type='order.cancelled.v1'",
				Integer.class, prefix + "%"), "每单一个取消事件");
	}

	private OrderService instance() {
		return new OrderService(mapper, context.getBean(Commands.class),
				context.getBean(com.lrj.commerce.trade.api.QuoteApi.class),
				context.getBean(com.lrj.commerce.inventory.api.InventoryApi.class),
				context.getBean(com.lrj.commerce.member.api.MemberApi.class),
				context.getBean(com.lrj.commerce.store.api.StoreApi.class), context.getBean(Outbox.class),
				context.getBean(com.lrj.commerce.ordering.infrastructure.security.AddressCipher.class),
				context.getBean(Clock.class), context.getBean(com.lrj.commerce.benefit.api.CouponApi.class),
				context.getBean(com.lrj.commerce.campaign.api.CampaignFundingApi.class),
				context.getBean(com.lrj.commerce.benefit.api.EntitlementApi.class),
				context.getBean(com.lrj.commerce.member.api.PointsSpendApi.class),
				context.getBean(org.springframework.transaction.PlatformTransactionManager.class), new WorkLanes());
	}

	private String insert(String tenant, String id, int expiredSecondsAgo) {
		var now = Instant.now();
		// 释放积分预占先锁会员行，每个租户需要真实会员。
		jdbc.update(
				"INSERT IGNORE INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,'m1','buyer','测试会员','L1')",
				tenant);
		jdbc.update(
				"INSERT INTO order_record(tenant_id,order_id,member_id,store_id,merchant_id,quote_id,payable,status,payment_kind,version,created_at,expires_at,items_json,address_cipher,address_key_version) VALUES(?,?,'m1','s1','mc1',?,10.00,'PENDING_PAYMENT','CHANNEL_REQUIRED',0,?,?,'[]',X'00',1)",
				tenant, id, "q-" + id, Timestamp.from(now.minusSeconds(expiredSecondsAgo + 900)),
				Timestamp.from(now.minusSeconds(expiredSecondsAgo)));
		return id;
	}

	private long remaining() {
		return jdbc.queryForObject(
				"SELECT COUNT(*) FROM order_record WHERE tenant_id LIKE ? AND status='PENDING_PAYMENT'", Long.class,
				prefix + "%");
	}

	private String status(String tenant, String id) {
		return text(tenant, id, "status");
	}

	private String text(String tenant, String id, String column) {
		return jdbc.queryForObject("SELECT " + column + " FROM order_record WHERE tenant_id=? AND order_id=?",
				String.class, tenant, id);
	}

	private int expiry(String tenant, String id, String column) {
		return jdbc.queryForObject("SELECT " + column + " FROM order_record WHERE tenant_id=? AND order_id=?",
				Integer.class, tenant, id);
	}

}
