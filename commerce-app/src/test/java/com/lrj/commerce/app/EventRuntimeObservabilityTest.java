package com.lrj.commerce.app;

import com.lrj.commerce.runtime.event.EventDispatcher;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.event.persistence.EventMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 诊断面：租户健康只统计有消费者的类型，全局指标无租户标签。 */
@SpringBootTest
class EventRuntimeObservabilityTest {

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
	EventDispatcher events;

	@Autowired
	MeterRegistry registry;

	@Test
	void tenantHealthSeparatesDueRetryingQuarantinedAndUnroutedEvents() {
		String tenant = "t-" + UUID.randomUUID();
		var admin = new Actor(tenant, "admin", Actor.Role.ADMIN);
		insert(tenant, "order.paid.v1", "PENDING", 0, -120);
		insert(tenant, "order.paid.v1", "PENDING", 2, 60);
		insert(tenant, "order.paid.v1", "ISOLATED", 5, -10);
		// 未声明又没有消费者的类型才计为unrouted（缺少必需消费者）；已声明的order.fulfilling.v1写入即SKIPPED。
		insert(tenant, "legacy.unconsumed.v1", "PENDING", 0, -999);
		EventMapper.Health h;
		// 伪造事件不属于任何真实聚合，断言后立即删除，避免留给其他用例的全局投递。
		try {
			h = events.health(admin);
		}
		finally {
			jdbc.update("DELETE FROM platform_event WHERE tenant_id=?", tenant);
		}
		assertEquals(2, h.pending());
		assertEquals(1, h.due());
		assertEquals(1, h.retrying());
		assertEquals(1, h.isolated());
		assertEquals(1, h.unrouted());
		assertTrue(h.oldestDueAgeSeconds() >= 119 && h.oldestDueAgeSeconds() < 200,
				"无消费者的更老事件不计入最老年龄：" + h.oldestDueAgeSeconds());
		assertThrows(RuntimeException.class, () -> events.health(new Actor(tenant, "buyer", Actor.Role.MEMBER)));
	}

	@Test
	void globalMetricsAreRegisteredWithoutTenantTags() {
		for (String name : new String[] { "commerce.events.queue.depth", "commerce.events.oldest.due.age",
				"commerce.events.quarantined", "commerce.events.retrying", "commerce.events.attempted",
				"commerce.events.delivered", "commerce.events.consumer.failures", "commerce.events.latency.total",
				"commerce.events.rotation.last", "commerce.events.transient.failures",
				"commerce.events.retrying.transient", "commerce.events.skipped", "commerce.events.breaker.open",
				"commerce.events.breaker.trips" }) {
			var meter = registry.find(name).meter();
			assertNotNull(meter, name);
			assertTrue(meter.getId().getTags().isEmpty(), name);
		}
		assertTrue(registry.get("commerce.events.queue.depth").gauge().value() >= 0);
	}

	/** 车道指标唯一标签是固定车道名，不出现租户、订单或事件标识。 */
	@Test
	void laneMetricsAreTaggedOnlyByFixedLaneName() {
		var lanes = new java.util.TreeSet<String>();
		for (var meter : registry.getMeters()) {
			if (!meter.getId().getName().startsWith("commerce.lanes."))
				continue;
			assertEquals(java.util.List.of("lane"),
					meter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey).toList(),
					meter.getId().toString());
			lanes.add(meter.getId().getTag("lane"));
		}
		// 第四阶段新增固定车道：replay（历史重放）与retention（保留期清理，只有调度观测）。
		assertEquals(new java.util.TreeSet<>(java.util.List.of("payments", "refunds", "orders", "segments", "journeys",
				"cycles", "points", "deliveries", "catalog-jobs", "events", "replay", "retention")), lanes);
		assertTrue(registry.get("commerce.lanes.backlog.due").tag("lane", "orders").gauge().value() >= 0,
				"订单到期车道有积压查询");
		// 积分与周期车道在第四阶段有了积压与隔离查询（逐项重试状态）。
		assertTrue(registry.get("commerce.lanes.backlog.quarantined").tag("lane", "points").gauge().value() >= 0);
		assertTrue(registry.get("commerce.lanes.backlog.quarantined").tag("lane", "cycles").gauge().value() >= 0);
		// 保留期指标只带固定数据类别标签。
		for (var meter : registry.getMeters())
			if (meter.getId().getName().startsWith("commerce.retention.") && !meter.getId().getTags().isEmpty())
				assertEquals(java.util.List.of("class"),
						meter.getId().getTags().stream().map(io.micrometer.core.instrument.Tag::getKey).toList(),
						meter.getId().toString());
	}

	private void insert(String tenant, String type, String status, int attempts, int dueInSeconds) {
		String id = UUID.randomUUID().toString();
		jdbc.update(
				"INSERT INTO platform_event(event_id,tenant_id,event_type,aggregate_id,aggregate_version,payload_json,status,attempts,available_at) VALUES(?,?,?,?,1,'{}',?,?,TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)))",
				id, tenant, type, id, status, attempts, dueInSeconds);
	}

}
