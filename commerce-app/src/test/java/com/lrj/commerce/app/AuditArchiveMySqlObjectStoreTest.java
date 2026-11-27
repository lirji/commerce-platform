package com.lrj.commerce.app;

import static org.junit.jupiter.api.Assertions.*;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.audit.OperationAudit;
import com.lrj.commerce.runtime.audit.archive.ArchiveObjectStore;
import com.lrj.commerce.runtime.audit.archive.ArchiveOnlineCutoverMapper;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveOnlineCutover;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveOnlineCutover.Outcome;
import com.lrj.commerce.runtime.command.persistence.CommandMapper.Key;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveMapper;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveVerification;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveRetentionReview;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveRetentionReview.Reason;
import com.lrj.commerce.runtime.serialization.JsonCodec;
import tools.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

/** 使用真实 MySQL 和版本化测试桶模拟回包丢失、坏对象、工单撤销与恢复重试。 */
@EnabledIfEnvironmentVariable(named = "ARCHIVE_TEST_ENDPOINT", matches = "http://127[.]0[.]0[.]1:[0-9]+")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuditArchiveMySqlObjectStoreTest {
    private static final String TENANT = "archive-test-" + UUID.randomUUID();

    @DynamicPropertySource
    static void environment(DynamicPropertyRegistry registry) {
        String database = System.getenv("COMMERCE_TEST_DB_URL");
        if (database == null || !database.contains("/commerce_test_20260923?")) {
            throw new IllegalStateException("归档验证需要授权的 Commerce 隔离库");
        }
        registry.add("spring.datasource.url", () -> database);
        registry.add("commerce.workers-enabled", () -> false);
        registry.add("commerce.audit-archive.enabled", () -> true);
        registry.add("commerce.audit-archive.scope", () -> "TENANT");
        registry.add("commerce.audit-archive.tenant", () -> TENANT);
        registry.add("commerce.audit-archive.minimum-age", () -> "P1D");
        registry.add("commerce.audit-archive.endpoint", () -> System.getenv("ARCHIVE_TEST_ENDPOINT"));
        registry.add("commerce.audit-archive.bucket", () -> "commerce-audit-verification");
        registry.add("commerce.audit-archive.write-key", () -> System.getenv("ARCHIVE_WRITE_KEY"));
        registry.add("commerce.audit-archive.write-secret", () -> System.getenv("ARCHIVE_WRITE_SECRET"));
        registry.add("commerce.audit-archive.restore-key", () -> System.getenv("ARCHIVE_RESTORE_KEY"));
        registry.add("commerce.audit-archive.restore-secret", () -> System.getenv("ARCHIVE_RESTORE_SECRET"));
    }

    @Autowired ArchiveObjectStore objects;
    @Autowired AuditArchiveMapper mapper;
    @Autowired ArchiveOnlineCutoverMapper cutoverMapper;
    @Autowired OperationAudit audit;
    @Autowired PlatformTransactionManager tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void reusesOneObjectVersionAndChecksRestoreTicketsBeforePublishing() {
        long original = oldAttempt("original");
        long replay = oldAttempt("replay");
        oldAttempt("still-awaiting");
        jdbc.update("""
            INSERT INTO platform_audit(tenant_id,actor_id,operation,command_key,audit_attempt_id,audit_resource_id,audit_action_code)
            VALUES(?,'operator','catalog.category.bind','archive-fixture',?,'catalog-1','catalog.category.bind')
            """, TENANT, original);
        long effect = jdbc.queryForObject("SELECT id FROM platform_audit WHERE audit_attempt_id=?", Long.class, original);
        jdbc.update("""
            INSERT INTO audit_attempt_outcome(attempt_id,event_ref,result_code,effect_id)
            VALUES(?,?,'REPLAYED',?)
            """, replay, replay + ":result", effect);
        var scope = new AuditArchiveMapper.Scope("TENANT", TENANT);
        var failures = new Faults(objects);
        var verification = new AuditArchiveVerification(mapper, failures, audit, scope, Duration.ofDays(1), Clock.systemUTC(), tx);
        assertThrows(DomainException.class, verification::tick);
        var claimed = mapper.pending(scope);
        assertNotNull(claimed);
        assertEquals("CLAIMED", claimed.state());
        assertEquals(2, claimed.rowCount());
        assertEquals(3, onlineCount());
        assertEquals(claimed.batchId(), verification.tick());
        assertEquals(failures.storedVersion, mapper.batch(claimed.batchId()).objectVersion());
        assertEquals("VERIFIED", mapper.batch(claimed.batchId()).state());
        assertNull(verification.tick());

        String ticket = "case-" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO audit_archive_restore_grant(ticket_id,batch_id,requested_by,approved_by,expires_at)
            VALUES(?,?,'recoverer','separate-approver',UTC_TIMESTAMP(3)+INTERVAL 1 HOUR)
            """, ticket, claimed.batchId());
        var actor = new Actor(TENANT, "recoverer", Actor.Role.ADMIN, Actor.Channel.WEB);
        var unauthorized = new Actor(TENANT, "another-admin", Actor.Role.ADMIN, Actor.Channel.WEB);
        var outside = new Actor(TENANT + "-outside", "recoverer", Actor.Role.ADMIN, Actor.Channel.WEB);
        assertDenied(() -> verification.restore(unauthorized, ticket, null));
        assertDenied(() -> verification.restore(outside, ticket, null));
        failures.corruptRead = true;
        assertThrows(DomainException.class, () -> verification.restore(actor, ticket, null));
        assertTrue(verification.restored(actor, ticket, 0, null).items().isEmpty());
        failures.corruptRead = false;
        assertEquals(2, verification.restore(actor, ticket, null));
        assertEquals(2, verification.restore(actor, ticket, null));
        var recovered = verification.restored(actor, ticket, 0, null).items();
        assertEquals(2, recovered.size());
        assertEquals(original, recovered.get(0).attemptId());
        assertEquals(replay, recovered.get(1).attemptId());
        var replayRow = JsonCodec.read(recovered.get(1).payload(), AuditArchiveMapper.Member.class);
        var originalRow = JsonCodec.read(recovered.get(0).payload(), AuditArchiveMapper.Member.class);
        var outcome = JsonCodec.read(replayRow.outcome(), JsonNode.class);
        assertEquals("REPLAYED", outcome.path("result").asString());
        assertEquals(effect, outcome.path("effectId").asLong());
        assertEquals(effect, JsonCodec.read(originalRow.effect(), JsonNode.class).path("id").asLong());
        assertDenied(() -> verification.restored(unauthorized, ticket, 0, null));
        jdbc.update("UPDATE audit_archive_restore_grant SET revoked_at=UTC_TIMESTAMP(3) WHERE ticket_id=?", ticket);
        assertDenied(() -> verification.restored(actor, ticket, 0, null));
        assertDenied(() -> verification.restore(actor, ticket, null));
        assertEquals(3, onlineCount());
        var eligible = checkRetention(scope, verification, claimed.batchId());
        checkOnlineCutover(verification, eligible, claimed.batchId(), original, actor);
    }

    private AuditArchiveRetentionReview checkRetention(AuditArchiveMapper.Scope scope, AuditArchiveVerification verification, String batchId) {
        var review = new AuditArchiveRetentionReview(mapper, verification, scope, Clock.systemUTC());
        assertEquals(Reason.NO_POLICY, review.assess(batchId).reason());
        jdbc.update("""
            INSERT INTO audit_archive_retention_policy(scope_type,tenant_id,policy_code,policy_version,
                online_days,total_days,approval_reference,approved_at)
            VALUES('TENANT',?,'BUSINESS',1,1,3,'isolated-policy-review',UTC_TIMESTAMP(3))
            """, TENANT);
        assertEquals(Reason.STILL_ONLINE, review.assess(batchId).reason());
        var afterRetention = new AuditArchiveRetentionReview(mapper, verification, scope,
                Clock.offset(Clock.systemUTC(), Duration.ofDays(2)));
        assertEquals(Reason.VERIFIED_FOR_REVIEW, afterRetention.assess(batchId).reason());
        jdbc.update("""
            INSERT INTO audit_archive_hold(scope_type,tenant_id,resource_id,case_reference,created_by)
            VALUES('TENANT',?,'catalog-1','isolated-hold','investigator')
            """, TENANT);
        assertEquals(Reason.HELD, afterRetention.assess(batchId).reason());
        jdbc.update("""
            UPDATE audit_archive_hold SET released_by='independent-reviewer',released_at=UTC_TIMESTAMP(3)
            WHERE tenant_id=? AND case_reference='isolated-hold'
            """, TENANT);
        assertEquals(Reason.VERIFIED_FOR_REVIEW, afterRetention.assess(batchId).reason());
        jdbc.update("""
            UPDATE audit_archive_retention_policy SET revoked_at=UTC_TIMESTAMP(3)
            WHERE tenant_id=? AND policy_code='BUSINESS'
            """, TENANT);
        assertEquals(Reason.NO_POLICY, afterRetention.assess(batchId).reason());
        assertEquals(3, onlineCount());
        jdbc.update("UPDATE audit_archive_retention_policy SET revoked_at=NULL WHERE tenant_id=?", TENANT);
        return afterRetention;
    }

    private void checkOnlineCutover(AuditArchiveVerification archive, AuditArchiveRetentionReview review,
            String batchId, long original, Actor actor) {
        var cutover = new AuditArchiveOnlineCutover(review, mapper, cutoverMapper, tx);
        assertEquals(Outcome.NOT_APPROVED, cutover.move(batchId));
        jdbc.update("""
            INSERT INTO audit_archive_cutover_grant(batch_id,approval_reference,approved_by,effective_at,expires_at)
            VALUES(?, 'isolated-cutover','isolated-reviewer',UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE,UTC_TIMESTAMP(3)+INTERVAL 1 HOUR)
            """, batchId);
        jdbc.update("""
            INSERT INTO platform_command(tenant_id,actor_id,operation,command_key,request_hash,response_json)
            VALUES(?,'operator','catalog.category.bind','archive-fixture',REPEAT('0',64),'{}')
            """, TENANT);
        assertEquals(Outcome.COMMAND_ACTIVE, cutover.move(batchId));
        assertEquals(3, onlineCount());
        // 清除特意添加的合成命令，仅为验证真实在线清理不会改动仍在使用的幂等证据。
        jdbc.update("DELETE FROM platform_command WHERE tenant_id=? AND command_key='archive-fixture'", TENANT);
        jdbc.update("""
            INSERT INTO audit_archive_online_purge_receipt(batch_id,object_version,sha256,approval_reference,attempt_count,effect_count,outcome_count)
            VALUES(?,'conflicting-fixture',REPEAT('0',64),'isolated-cutover',2,1,1)
            """, batchId);
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> cutover.move(batchId));
        assertEquals("VERIFIED", mapper.batch(batchId).state());
        assertEquals(3, onlineCount());
        jdbc.update("DELETE FROM audit_archive_online_purge_receipt WHERE batch_id=? AND object_version='conflicting-fixture'", batchId);
        assertEquals(Outcome.MOVED, cutover.move(batchId));
        assertEquals(Outcome.ALREADY_MOVED, cutover.move(batchId));
        assertEquals(1, onlineCount());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_archive_effect_locator WHERE batch_id=?", Integer.class, batchId));

        var command = new Key(TENANT, "operator", "catalog.category.bind", "archive-fixture");
        var evidence = new OperationAudit.Evidence("catalog-1", 1L, 2, "核对迟到记录", java.util.List.of(), "catalog.category.bind");
        new org.springframework.transaction.support.TransactionTemplate(tx).executeWithoutResult(status -> {
            audit.effect(command, original, evidence);
            audit.effect(command, original, evidence);
        });
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM audit_archive_late_evidence WHERE batch_id=?", Integer.class, batchId));
        String another = "case-" + UUID.randomUUID();
        jdbc.update("""
            INSERT INTO audit_archive_restore_grant(ticket_id,batch_id,requested_by,approved_by,expires_at)
            VALUES(?,?,'recoverer','separate-approver',UTC_TIMESTAMP(3)+INTERVAL 1 HOUR)
            """, another, batchId);
        assertEquals(2, archive.restore(actor, another, null));
        var restored = archive.restored(actor, another, 0, null).items();
        assertEquals(2, restored.size());
        assertTrue(restored.getFirst().lateEvidence().contains("EFFECT"));
        assertEquals(1, onlineCount());
    }

    private long oldAttempt(String result) {
        String request = UUID.randomUUID().toString();
        jdbc.update("""
            INSERT INTO audit_operation_attempt(source_system,request_id,scope_type,actor_tenant_id,actor_id,action_code,
                resource_id,occurred_at,policy_code,policy_version,schema_version)
            VALUES('COMMERCE',?,'TENANT',?,'operator','catalog.category.bind',?,UTC_TIMESTAMP(3)-INTERVAL 2 DAY,'BUSINESS',1,1)
            """, request, TENANT, result);
        return jdbc.queryForObject("SELECT id FROM audit_operation_attempt WHERE request_id=?", Long.class, request);
    }

    private int onlineCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM audit_operation_attempt WHERE actor_tenant_id=?"
                + " AND action_code='catalog.category.bind'", Integer.class, TENANT);
    }

    private static void assertDenied(org.junit.jupiter.api.function.Executable useCase) {
        assertEquals(DomainException.Code.FORBIDDEN, assertThrows(DomainException.class, useCase).code());
    }

    private static final class Faults implements ArchiveObjectStore {
        private final ArchiveObjectStore real;
        private String storedVersion;
        private boolean corruptRead;

        private Faults(ArchiveObjectStore real) {
            this.real = real;
        }

        @Override
        public String putOnce(String key, byte[] bytes, String sha256) {
            String version = real.putOnce(key, bytes, sha256);
            if (storedVersion == null) {
                storedVersion = version;
                throw new DomainException(DomainException.Code.UNAVAILABLE, "模拟对象已经上传但响应丢失");
            }
            assertEquals(storedVersion, version);
            return version;
        }

        @Override
        public byte[] readForVerification(String key, String version, int exactBytes) {
            return real.readForVerification(key, version, exactBytes);
        }

        @Override
        public byte[] readForRestore(String key, String version, int exactBytes) {
            byte[] read = real.readForRestore(key, version, exactBytes);
            if (corruptRead) read[0] ^= 1;
            return read;
        }
    }
}
