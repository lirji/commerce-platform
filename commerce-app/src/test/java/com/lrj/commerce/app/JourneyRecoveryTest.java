package com.lrj.commerce.app;

import com.lrj.commerce.journey.api.JourneyApi;
import com.lrj.commerce.journey.application.JourneyService;
import com.lrj.commerce.journey.infrastructure.persistence.JourneyMapper;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.work.WorkLanes;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

/** 真实MySQL故障窗口验证；注入仅存在测试包装器，产品运行没有故障开关。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class JourneyRecoveryTest {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		Phase4Properties.register(registry);
	}

	@Autowired
	ApplicationContext context;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	JourneyMapper mapper;

	@Autowired
	JourneyApi api;

	@Autowired
	PlatformTransactionManager transactions;

	@LocalServerPort
	int port;

	private String tenant, token, id;

	private Actor admin;

	private Phase4Http http;

	@BeforeEach
	void fixture() throws Exception {
		tenant = "p8-recovery-" + UUID.randomUUID();
		admin = new Actor(tenant, "admin", Actor.Role.ADMIN);
		http = new Phase4Http(jdbc, port);
		token = http.token(tenant, "admin", "ADMIN");
		post("/v1/admin/members", Map.of("memberId", "m1", "actorId", "buyer", "displayName", "恢复会员", "memberLevel", "VIP"));
		post("/v1/admin/merchants", Map.of("merchantId", "merchant1", "name", "恢复商家"));
		post("/v1/admin/stores", Map.of("storeId", "store1", "merchantId", "merchant1", "name", "恢复店铺"));
		post("/v1/admin/entitlement-definitions", Map.of("benefitId", "credit", "version", 1, "storeId", "store1",
				"name", "恢复权益", "units", 2, "quota", 100, "validFrom", Instant.now().minusSeconds(60),
				"validTo", Instant.now().plusSeconds(7200), "validityDays", 1));
		var nodes = List.of(Map.of("id", "grant", "kind", "GRANT", "next", "notice", "benefit", Map.of("benefitId", "credit", "version", 1)),
				Map.of("id", "notice", "kind", "NOTIFY", "next", "end", "title", "恢复通知", "body", "恢复验证"),
				Map.of("id", "end", "kind", "END"));
		post("/v1/admin/journeys", Map.of("journeyId", "recover", "version", 1, "storeId", "store1", "name", "恢复旅程",
				"trigger", "MANUAL", "validFrom", Instant.now().minusSeconds(30), "validTo", Instant.now().plusSeconds(300),
				"maxDurationSeconds", 3600, "entry", "grant", "nodes", nodes));
		int version = 0;
		for (String action : List.of("submit", "approve", "publish"))
			post("/v1/admin/journeys/recover/1/" + action, Map.of("expectedVersion", version++));
		id = post("/v1/admin/journey-instances", Map.of("journeyId", "recover", "version", 1, "memberId", "m1", "eventKey", "one"))
				.path("instanceId").asString();
	}

	private tools.jackson.databind.JsonNode post(String path, Object body) throws Exception {
		return http.ok("POST", path, token, UUID.randomUUID().toString(), body);
	}

	/** 创建全新轮转器，恢复不依赖旧服务的游标、统计或熔断状态。 */
	private JourneyService worker(JourneyMapper selected, PlatformTransactionManager manager) {
		return new JourneyService(selected, bean(com.lrj.commerce.runtime.command.Commands.class),
				bean(com.lrj.commerce.member.profile.api.MemberApi.class), bean(com.lrj.commerce.store.management.api.StoreApi.class),
				bean(com.lrj.commerce.benefit.entitlement.api.EntitlementApi.class), bean(com.lrj.commerce.ordering.order.api.OrderApi.class),
				bean(com.lrj.commerce.aftersales.api.AftersaleApi.class), bean(com.lrj.commerce.marketing.api.RuleDecisionPort.class),
				Clock.systemUTC(), manager, bean(com.lrj.commerce.member.growth.api.MemberGrowthApi.class),
				bean(com.lrj.commerce.campaign.asset.api.MarketingAssets.class), bean(com.lrj.commerce.member.behavior.api.MemberBehaviorApi.class),
				bean(com.lrj.commerce.benefit.coupon.api.CouponApi.class), new WorkLanes(), bean(com.lrj.commerce.journey.application.JourneyAuthorization.class));
	}

	private <T> T bean(Class<T> type) {
		return context.getBean(type);
	}

	@FunctionalInterface
	interface Before {
		void invoke(String method, Object[] arguments) throws Throwable;
	}

	private JourneyMapper intercept(Before before) {
		return (JourneyMapper) Proxy.newProxyInstance(JourneyMapper.class.getClassLoader(), new Class<?>[] { JourneyMapper.class },
				(proxy, method, args) -> {
					before.invoke(method.getName(), args);
					try {
						return method.invoke(mapper, args);
					}
					catch (InvocationTargetException failure) {
						throw failure.getCause();
					}
				});
	}

	private int count(String table) {
		// 表名只取测试写死的允许集合，值仍参数绑定。
		assertTrue(Set.of("benefit_grant", "benefit_ledger", "journey_step_execution", "journey_notification").contains(table));
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id=?", Integer.class, tenant);
	}

	private void due() {
		jdbc.update("UPDATE journey_instance SET due_at=UTC_TIMESTAMP(3) WHERE tenant_id=? AND instance_id=?", tenant, id);
	}

	private void drain() {
		var fresh = worker(mapper, transactions);
		for (int i = 0; i < 4; i++)
			fresh.pump(admin);
		assertEquals(JourneyApi.State.COMPLETED, mapper.findInstance(tenant, id).status());
		assertEquals(1, count("benefit_grant"));
		assertEquals(1, count("journey_notification"));
		assertEquals(3, mapper.recordedSteps(tenant, id));
	}

	@Test
	void crashAfterBenefitWriteBeforeLocalCompletionRollsBackAllEffects() {
		var crashing = worker(intercept((method, args) -> {
			if (method.equals("stepFinish")) {
				assertEquals(1, count("benefit_grant"), "同一事务里业务效果确已写入");
				throw new CrashRecoveryTest.Crash("after-effect-before-checkpoint");
			}
		}), transactions);
		assertThrows(CrashRecoveryTest.Crash.class, () -> crashing.pump(admin));
		assertEquals(0, count("benefit_grant"));
		assertEquals(0, count("journey_step_execution"));
		assertEquals(0, mapper.findInstance(tenant, id).version());
		assertEquals(0, mapper.findInstance(tenant, id).attempts());
		drain();
	}

	@Test
	void crashAfterCommitDoesNotReplayEarlierSuccessfulAction() {
		var manager = new PlatformTransactionManager() {
			final AtomicBoolean first = new AtomicBoolean(true);
			public org.springframework.transaction.TransactionStatus getTransaction(org.springframework.transaction.TransactionDefinition definition) {
				return transactions.getTransaction(definition);
			}
			public void commit(org.springframework.transaction.TransactionStatus status) {
				transactions.commit(status);
				if (first.getAndSet(false))
					throw new CrashRecoveryTest.Crash("after-commit");
			}
			public void rollback(org.springframework.transaction.TransactionStatus status) {
				transactions.rollback(status);
			}
		};
		assertThrows(CrashRecoveryTest.Crash.class, () -> worker(mapper, manager).pump(admin));
		assertEquals("notice", mapper.findInstance(tenant, id).currentNode());
		assertEquals(1, count("benefit_grant"));
		assertEquals(1, count("journey_step_execution"));
		drain();
	}

	@Test
	void transientFailureRollsBackActionAndDefersWithoutPoisonAttempts() {
		worker(intercept((method, args) -> {
			if (method.equals("stepFinish"))
				throw new DomainException(DomainException.Code.UNAVAILABLE, "测试依赖不可用");
		}), transactions).pump(admin);
		var row = mapper.findInstance(tenant, id);
		assertEquals(0, row.attempts());
		assertEquals(0, row.steps());
		assertEquals("grant", row.currentNode());
		assertEquals(0, count("benefit_grant"));
		var history = api.history(admin, id, -1, 50);
		assertEquals(JourneyApi.StepState.DEFERRED, history.steps().getFirst().status());
		assertEquals("NODE_DEFERRED", history.steps().getFirst().outcome());
		due();
		drain();
		assertEquals(4, count("journey_step_execution"));
	}

	@Test
	void laterPoisonFailureIsAuditedAndRetryDoesNotReplayGrant() {
		worker(mapper, transactions).pump(admin);
		var failing = worker(intercept((method, args) -> {
			if (method.equals("stepFinish"))
				throw new DomainException(DomainException.Code.CONFLICT, "测试永久失败");
		}), transactions);
		for (int i = 0; i < 5; i++) {
			due();
			failing.pump(admin);
		}
		assertEquals(JourneyApi.State.ISOLATED, mapper.findInstance(tenant, id).status());
		assertEquals("notice", mapper.findInstance(tenant, id).currentNode());
		assertEquals(1, count("benefit_grant"));
		assertEquals(0, count("journey_notification"));
		api.control(admin, "retry", id, "retry");
		assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM platform_recovery WHERE tenant_id=? AND operation='journey.retry' AND result='APPLIED'", Integer.class, tenant));
		drain();
		assertEquals(8, count("journey_step_execution"));
		assertThrows(DomainException.class, () -> api.control(admin, "replay", id, "replay"));
	}

	@Test
	void newVersionAndPauseDoNotChangeActiveHistoricalGraph() {
		var original = api.definitions(admin, "", 10).getFirst().content();
		var newer = new JourneyApi.Definition(original.journeyId(), 2, original.storeId(), "版本2",
				original.trigger(), original.validFrom(), original.validTo(), original.maxDurationSeconds(),
				"end", List.of(new JourneyApi.Node("end", JourneyApi.Kind.END, null, null, null, null, null, null, null, null)));
		api.create(admin, "v2", newer);
		int expected = 0;
		for (String action : List.of("submit", "approve", "publish"))
			api.change(admin, action + "-v2", "recover", 2, expected++, action);
		var current = api.enroll(admin, "new-entry", new JourneyApi.Start("recover", 2, "m1", "two"));
		api.change(admin, "pause", "recover", 2, 3, "pause");
		assertThrows(DomainException.class, () -> api.enroll(admin, "after-pause", new JourneyApi.Start("recover", 2, "m1", "three")));
		assertThrows(DomainException.class, () -> api.create(admin, "overwrite-published", original));
		drain();
		assertEquals(JourneyApi.State.COMPLETED, mapper.findInstance(tenant, current.instanceId()).status());
		var historic = api.history(admin, id, -1, 50);
		assertEquals(1, historic.definition().version());
		assertEquals(original.name(), historic.definition().name());
		assertEquals(3, historic.steps().size());
		assertEquals(2, api.history(admin, current.instanceId(), -1, 50).definition().version());
		assertEquals(1, api.history(admin, current.instanceId(), -1, 50).steps().size());
	}

	@Test
	void historyAndRecoveryRejectOtherMemberAndOtherTenant() {
		bean(com.lrj.commerce.member.profile.api.MemberApi.class).create(admin, "other-member",
				new com.lrj.commerce.member.profile.api.MemberApi.Create("m2", "buyer2", "其他会员", "VIP"));
		var other = new Actor(tenant, "buyer2", Actor.Role.MEMBER);
		assertEquals(DomainException.Code.NOT_FOUND, assertThrows(DomainException.class, () -> api.history(other, id, -1, 50)).code());
		assertEquals(DomainException.Code.FORBIDDEN, assertThrows(DomainException.class, () -> api.control(other, "retry", id, "retry")).code());
		assertEquals(DomainException.Code.NOT_FOUND, assertThrows(DomainException.class,
				() -> api.history(new Actor("other-" + tenant, "admin", Actor.Role.ADMIN), id, -1, 50)).code());
	}

	@Test
	void staleFailureCannotAttachToNodeAdvancedByAnotherWorker() throws Exception {
		var failed = new CountDownLatch(1);
		var progressed = new CountDownLatch(1);
		var first = worker(intercept((method, args) -> {
			if (method.equals("stepFinish"))
				throw new DomainException(DomainException.Code.CONFLICT, "测试旧执行失败");
			if (method.equals("lock")) {
				failed.countDown();
				assertTrue(progressed.await(10, TimeUnit.SECONDS));
			}
		}), transactions);
		try (var pool = Executors.newSingleThreadExecutor()) {
			var task = pool.submit(() -> first.pump(admin));
			assertTrue(failed.await(10, TimeUnit.SECONDS));
			worker(mapper, transactions).pump(admin);
			progressed.countDown();
			task.get(10, TimeUnit.SECONDS);
		}
		assertEquals(1, mapper.findInstance(tenant, id).steps());
		assertEquals(0, mapper.findInstance(tenant, id).attempts());
		assertEquals(1, count("journey_step_execution"));
		assertEquals(JourneyApi.StepState.COMPLETED, api.history(admin, id, -1, 50).steps().getFirst().status());
		drain();
	}
}
