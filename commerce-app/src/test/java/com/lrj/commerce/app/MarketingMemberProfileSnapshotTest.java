package com.lrj.commerce.app;

import com.lrj.commerce.app.configuration.security.MarketingProfileSecurity;
import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.member.profile.api.MemberApi;
import com.lrj.commerce.member.profile.infrastructure.persistence.MemberMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 用真实 MySQL 验证分批快照、变化时的可重复读和授权闭环。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"commerce.workers-enabled=false",
		"commerce.marketing.member-profile.enabled=true",
		"commerce.marketing.member-profile.marketing-tenant=profile-test"
})
class MarketingMemberProfileSnapshotTest {
	private static final String TOKEN = UUID.randomUUID() + "-" + UUID.randomUUID();
	private static final String SOURCE = "profile-source-" + UUID.randomUUID();
	private static final String INSERT = """
			INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,status)
			VALUES (?,?,?,'测试会员','BASIC',?)
			""";

	@DynamicPropertySource
	static void configure(DynamicPropertyRegistry registry) {
		String url = System.getenv("COMMERCE_TEST_DB_URL");
		if (url == null || !url.contains("/commerce_test_20260923?")) {
			throw new IllegalStateException("必须显式指定本项目隔离测试库");
		}
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", () -> System.getenv("COMMERCE_DB_USER"));
		registry.add("spring.datasource.password", () -> System.getenv("COMMERCE_DB_PASSWORD"));
		registry.add("commerce.marketing.member-profile.bearer-token", () -> TOKEN);
		registry.add("commerce.marketing.member-profile.commerce-tenant", () -> SOURCE);
	}

	@LocalServerPort int port;
	@Autowired JdbcTemplate jdbc;
	@Autowired MemberApi members;
	@Autowired PlatformTransactionManager manager;
	@Autowired SqlSessionTemplate session;
	@MockitoSpyBean MemberMapper mapper;

	/** 第二页前提交等级和活跃状态变化，旧请求继续读取原记录，新请求才看到新状态。 */
	@Test
	void pinsTheReadViewAcrossPagesAndKeepsMachineScope() throws Exception {
		insertActive(SOURCE, 1_002);
		jdbc.update(INSERT, SOURCE, "m-10003", "m-10003", "FROZEN");
		jdbc.update(INSERT, SOURCE, "m-10004", "m-10004", "CLOSED");
		jdbc.update(INSERT, "profile-other", "foreign", "foreign", "ACTIVE");
		var writer = new TransactionTemplate(manager);
		writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		var firstCall = new AtomicBoolean(true);
		doAnswer(call -> {
			// 运行同一 XML 查询并使用当前事务绑定的 SqlSession，真实读完第一页才提交变更。
			var parameters = new HashMap<String, Object>();
			parameters.put("tenant", call.getArgument(0));
			parameters.put("status", call.getArgument(1));
			parameters.put("after", null);
			parameters.put("limit", call.getArgument(3));
			var first = session.selectList(MemberMapper.class.getName() + ".marketingProfiles", parameters);
			if (firstCall.getAndSet(false)) {
				writer.executeWithoutResult(status -> {
					assertEquals(1, jdbc.update("UPDATE member_record SET member_level='GOLD' WHERE tenant_id=? AND member_id='m-01001'", SOURCE));
					assertEquals(1, jdbc.update("UPDATE member_record SET status='FROZEN' WHERE tenant_id=? AND member_id='m-01002'", SOURCE));
					jdbc.update(INSERT, SOURCE, "m-01003", "m-01003", "ACTIVE");
				});
			}
			return first;
		}).when(mapper).marketingProfiles(eq(SOURCE), eq("ACTIVE"), isNull(), anyInt());

		assertEquals(403, request("profile-other").statusCode());
		var response = request("profile-test");
		assertEquals(200, response.statusCode());
		assertEquals("no-store", response.headers().firstValue("cache-control").orElseThrow());
		var body = JsonMapper.builder().build().readTree(response.body());
		assertNotNull(Instant.parse(body.path("asOf").asString()));
		var profiles = body.path("profiles");
		assertEquals(1_002, profiles.size());
		assertEquals("m-01000", profiles.get(999).path("memberId").asString());
		assertEquals("BASIC", profiles.get(1_000).path("memberLevel").asString());
		assertEquals("m-01002", profiles.get(1_001).path("memberId").asString());
		assertEquals(2, profiles.get(1_001).size());
		var latest = members.marketingSnapshot(SOURCE);
		assertEquals(1_002, latest.profiles().size());
		assertEquals(new MemberApi.MarketingProfile("m-01001", "GOLD"), latest.profiles().get(1_000));
		assertEquals("m-01003", latest.profiles().getLast().memberId());
	}

	/** 恰好到达批次末尾时继续探测空页。总体超出上限时拒绝整份结果。 */
	@Test
	void distinguishesEmptyExactLimitAndOverflow() {
		var empty = members.marketingSnapshot("profile-empty");
		assertTrue(empty.profiles().isEmpty());
		assertNotNull(empty.asOf());
		String tenant = "profile-limit-" + UUID.randomUUID();
		insertActive(tenant, 10_000);
		var exact = members.marketingSnapshot(tenant);
		assertEquals(10_000, exact.profiles().size());
		assertEquals(10_000, exact.profiles().stream().distinct().count());
		assertEquals("m-10000", exact.profiles().getLast().memberId());
		jdbc.update(INSERT, tenant, "m-10001", "m-10001", "ACTIVE");
		assertEquals(DomainException.Code.CONFLICT,
				assertThrows(DomainException.class, () -> members.marketingSnapshot(tenant)).code());
	}

	private void insertActive(String tenant, int size) {
		List<Object[]> rows = new ArrayList<>();
		for (int i = 1; i <= size; i++) {
			String id = "m-%05d".formatted(i);
			rows.add(new Object[] {tenant, id, id, "ACTIVE"});
		}
		jdbc.batchUpdate(INSERT, rows);
	}

	private HttpResponse<String> request(String marketingTenant) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + MarketingProfileSecurity.PATH))
				.header("X-Marketing-Tenant", marketingTenant).header("Authorization", "Bearer " + TOKEN)
				.timeout(Duration.ofSeconds(8)).GET().build();
		return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
	}
}
