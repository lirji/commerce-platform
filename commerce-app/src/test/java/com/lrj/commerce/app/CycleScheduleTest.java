package com.lrj.commerce.app;

import com.lrj.commerce.member.points.infrastructure.persistence.PointsMapper;
import com.lrj.commerce.member.cycle.api.MemberCycleApi;
import com.lrj.commerce.member.cycle.infrastructure.persistence.CycleMapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * P4.2 周期考核调度：到期由可索引的member_record.cycle_due_at表达，发现只看有已生效策略的租户中已到期的会员；
 * 新策略分批把全部会员拉到生效时间；考核后到期时间取周期边界与下一策略生效时间中较早者；注销会员离开调度。 查询计划门：关键发现查询不得退化为全表扫描或按会员主键扫描整租户。
 */
@SpringBootTest
class CycleScheduleTest {

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		Phase4Properties.register(registry);
	}

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	MemberCycleApi cycles;

	@Autowired
	CycleMapper mapper;

	@Autowired
	SqlSessionFactory sessions;

	private final JsonMapper json = JsonMapper.builder().build();

	private String prefix;

	@BeforeEach
	void prefix() {
		prefix = "cs-" + UUID.randomUUID().toString().substring(0, 8) + "-";
	}

	@Test
	void membersBecomeDueOnlyUnderAnEffectivePolicyAndAdvanceToTheCycleEnd() {
		String t = prefix + "a";
		member(t, "m1");
		assertFalse(mapper.dueTenants(prefix, Instant.now(), 50).contains(t), "没有策略的租户不被发现，其会员不被扫描");
		policy(t, 1, "2000-01-01 00:00:00.000", 30, true);
		assertTrue(mapper.dueTenants(prefix, Instant.now(), 50).contains(t), "新会员取纪元值，策略生效即到期");
		tickUntil(() -> account(t, "m1") != null);
		var end = jdbc.queryForObject("SELECT cycle_end FROM member_cycle_account WHERE tenant_id=? AND member_id='m1'",
				Timestamp.class, t);
		assertEquals(end, due(t, "m1"), "考核后到期时间是本周期结束");
		assertTrue(mapper.due(t, Instant.now(), 10).isEmpty(), "考核完成后不再到期");
	}

	@Test
	void closedMembersLeaveTheSchedule() {
		String t = prefix + "a";
		policy(t, 1, "2000-01-01 00:00:00.000", 30, true);
		member(t, "gone");
		jdbc.update("UPDATE member_record SET status='CLOSED' WHERE tenant_id=? AND member_id='gone'", t);
		tickUntil(() -> due(t, "gone").toInstant().equals(CycleMapper.NEVER));
		assertNull(account(t, "gone"), "注销会员不写考核快照");
		assertTrue(mapper.due(t, Instant.now(), 10).isEmpty());
	}

	/** 新策略分批推进：1201名已考核会员（到期在未来）在推进完成后全部不晚于生效时间，并按新版本考核。 */
	@Test
	void newPolicyRollsOutInBoundedBatchesAndEveryMemberIsReassessed() {
		String t = prefix + "a";
		policy(t, 1, "2000-01-01 00:00:00.000", 30, true);
		var rows = new ArrayList<Object[]>();
		var future = Timestamp.from(Instant.now().plusSeconds(20 * 86400));
		for (int i = 0; i < 1201; i++)
			rows.add(new Object[] { t, String.format("m%05d", i), "a" + i, future });
		jdbc.batchUpdate(
				"INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level,cycle_due_at) VALUES(?,?,?,'批量会员','L1',?)",
				rows);
		var effective = Instant.now().minusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		policy(t, 2, Timestamp.from(effective).toString(), 30, false);
		tickUntil(() -> Boolean.TRUE.equals(jdbc.queryForObject(
				"SELECT rolled_out FROM member_cycle_policy WHERE tenant_id=? AND version=2", Boolean.class, t)));
		assertEquals("m01200",
				jdbc.queryForObject("SELECT rollout_cursor FROM member_cycle_policy WHERE tenant_id=? AND version=2",
						String.class, t),
				"游标推进到最后一名会员");
		// 推进完成后每名会员要么已到期（不晚于生效时间），要么已按新版本考核（到期移到新周期结束）。
		assertEquals(0, (int) jdbc.queryForObject(
				"SELECT COUNT(*) FROM member_record m LEFT JOIN member_cycle_account a ON a.tenant_id=m.tenant_id AND a.member_id=m.member_id WHERE m.tenant_id=? AND m.cycle_due_at>? AND (a.member_id IS NULL OR a.policy_version<>2)",
				Integer.class, t, Timestamp.from(effective)), "推进后没有会员错过新策略");
		tickUntil(() -> jdbc.queryForObject(
				"SELECT COUNT(*) FROM member_cycle_account WHERE tenant_id=? AND policy_version=2", Integer.class,
				t) == 1201);
	}

	@Test
	void aFuturePolicyCapsTheNextDueTime() {
		String t = prefix + "a";
		policy(t, 1, "2000-01-01 00:00:00.000", 30, true);
		var next = Instant.now().plusSeconds(3600).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
		policy(t, 2, Timestamp.from(next).toString(), 30, false);
		member(t, "m1");
		tickUntil(() -> account(t, "m1") != null);
		var end = jdbc
			.queryForObject("SELECT cycle_end FROM member_cycle_account WHERE tenant_id=? AND member_id='m1'",
					Timestamp.class, t)
			.toInstant();
		assertEquals(end.isBefore(next) ? end : next, due(t, "m1").toInstant(), "下一策略生效前重新考核");
	}

	/** 查询计划门：周期与积分的发现、租户内取数都走各自索引，没有全表扫描；周期取数不按主键扫描整租户会员。 */
	@Test
	void criticalDiscoveryQueriesKeepTheirIndexPlans() {
		var at = Instant.now();
		var cycleDue = plan("com.lrj.commerce.member.cycle.infrastructure.persistence.CycleMapper.due",
				Map.of("tenant", "t", "at", at, "limit", 10));
		assertEquals("ix_member_cycle_due", key(cycleDue, "m"), cycleDue.toString());
		var cycleTenants = plan("com.lrj.commerce.member.cycle.infrastructure.persistence.CycleMapper.dueTenants",
				Map.of("after", "", "at", at, "limit", 50));
		assertEquals("ix_member_cycle_due", key(cycleTenants, "m"), cycleTenants.toString());
		// 到期探测必须沿索引顺序只读第一项：出现文件排序说明探测会读取并排序该租户全部会员。
		assertFalse(sortsMembers(cycleTenants), "周期发现探测不得对会员排序：" + cycleTenants);
		var pointTenants = plan("com.lrj.commerce.member.points.infrastructure.persistence.PointsMapper.dueTenants",
				Map.of("after", "", "at", at, "limit", 50));
		assertEquals("ix_point_tenant_expiry", key(pointTenants, "l"), pointTenants.toString());
		var pointDue = plan("com.lrj.commerce.member.points.infrastructure.persistence.PointsMapper.due",
				Map.of("tenant", "t", "at", at, "limit", 20));
		assertEquals("ix_point_tenant_expiry", key(pointDue, "l"), pointDue.toString());
		// 只检查基表；派生表（两路各取limit后的合并结果）按设计是小结果集上的扫描。
		for (var p : List.of(cycleDue, cycleTenants, pointTenants, pointDue))
			for (var table : tables(p))
				if (!table.has("materialized_from_subquery") && !table.path("table_name").asString().startsWith("<"))
					assertNotEquals("ALL", table.path("access_type").asString(), table.path("table_name").asString());
	}

	// ---------------- helpers ----------------
	private JsonNode plan(String statement, Map<String, Object> params) {
		var bound = sessions.getConfiguration().getMappedStatement(statement).getBoundSql(new HashMap<>(params));
		var args = bound.getParameterMappings().stream().map(m -> {
			var v = params.get(m.getProperty());
			return v instanceof Instant i ? Timestamp.from(i) : v;
		}).toArray();
		return json.readTree(jdbc.queryForObject("EXPLAIN FORMAT=JSON " + bound.getSql(), String.class, args));
	}

	private static List<JsonNode> tables(JsonNode node) {
		var result = new ArrayList<JsonNode>();
		if (node.isObject()) {
			if (node.has("table_name") && node.has("access_type"))
				result.add(node);
			for (var e : node.properties())
				result.addAll(tables(e.getValue()));
		}
		else if (node.isArray())
			for (var n : node)
				result.addAll(tables(n));
		return result;
	}

	/** 某个排序操作直接对会员表m做文件排序（只看该排序直接作用的表，不下探到子查询或派生表）。 */
	private static boolean sortsMembers(JsonNode node) {
		if (node.isObject()) {
			if (node.has("ordering_operation")) {
				var o = node.get("ordering_operation");
				if (o.path("using_filesort").asBoolean() && directTables(o).contains("m"))
					return true;
			}
			for (var e : node.properties())
				if (sortsMembers(e.getValue()))
					return true;
		}
		else if (node.isArray())
			for (var n : node)
				if (sortsMembers(n))
					return true;
		return false;
	}

	private static List<String> directTables(JsonNode operation) {
		var names = new ArrayList<String>();
		if (operation.has("table"))
			names.add(operation.get("table").path("table_name").asString());
		for (var step : operation.path("nested_loop"))
			names.add(step.path("table").path("table_name").asString());
		for (var key : List.of("grouping_operation", "duplicates_removal"))
			if (operation.has(key))
				names.addAll(directTables(operation.get(key)));
		return names;
	}

	private static String key(JsonNode plan, String alias) {
		return tables(plan).stream()
			.filter(t -> t.path("table_name").asString().equals(alias))
			.map(t -> t.path("key").asString())
			.findFirst()
			.orElse("<none>");
	}

	private void member(String tenant, String member) {
		jdbc.update(
				"INSERT INTO member_record(tenant_id,member_id,actor_id,display_name,member_level) VALUES(?,?,?,'周期会员','L1')",
				tenant, member, member);
	}

	private void policy(String tenant, long version, String effective, int days, boolean rolledOut) {
		var at = Timestamp.valueOf(effective.length() > 23 ? effective.substring(0, 23) : effective).toInstant();
		jdbc.update(
				"INSERT INTO member_cycle_policy(tenant_id,version,effective_from,policy_json,rolled_out) VALUES(?,?,?,?,?)",
				tenant, version, Timestamp.from(at), "{\"version\":" + version + ",\"effectiveFrom\":\"" + at
						+ "\",\"periodDays\":" + days + ",\"levels\":[{\"code\":\"L1\",\"minimumGrowth\":0}]}",
				rolledOut);
	}

	private Timestamp due(String tenant, String member) {
		return jdbc.queryForObject("SELECT cycle_due_at FROM member_record WHERE tenant_id=? AND member_id=?",
				Timestamp.class, tenant, member);
	}

	private Long account(String tenant, String member) {
		var r = jdbc.queryForList("SELECT version FROM member_cycle_account WHERE tenant_id=? AND member_id=?",
				Long.class, tenant, member);
		return r.isEmpty() ? null : r.getFirst();
	}

	private void tickUntil(java.util.function.BooleanSupplier done) {
		for (int i = 0; i < 500 && !done.getAsBoolean(); i++)
			cycles.tick();
		assertTrue(done.getAsBoolean(), "周期车道未在上限内达到预期");
	}

}
