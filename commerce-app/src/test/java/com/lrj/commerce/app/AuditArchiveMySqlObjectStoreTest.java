package com.lrj.commerce.app;

import static org.junit.jupiter.api.Assertions.*;

import com.lrj.commerce.kernel.DomainException;
import com.lrj.commerce.runtime.api.identity.Actor;
import com.lrj.commerce.runtime.audit.OperationAudit;
import com.lrj.commerce.runtime.audit.archive.ArchiveObjectStore;
import com.lrj.commerce.runtime.audit.archive.ArchiveOnlineCutoverMapper;
import com.lrj.commerce.runtime.audit.archive.ArchivePurgeRecoveryMapper;
import com.lrj.commerce.runtime.audit.archive.ArchiveExpiryMapper;
import com.lrj.commerce.runtime.audit.archive.ArchiveVersionDisposal;
import com.lrj.commerce.runtime.audit.archive.AuditArchiveObjectExpiry;
import com.lrj.commerce.runtime.audit.archive.AuditArchivePurgeRecovery;
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
    private static final String STANDARD_BUCKET = "commerce-audit-verification";
    private static final String LOCK_BUCKET = "commerce-audit-lock-verification";
    private static final String BUCKET = LOCK_BUCKET.equals(System.getenv("ARCHIVE_TEST_BUCKET"))
            ? LOCK_BUCKET : STANDARD_BUCKET;

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
        registry.add("commerce.audit-archive.bucket", () -> BUCKET);
        registry.add("commerce.audit-archive.write-key", () -> System.getenv("ARCHIVE_WRITE_KEY"));
        registry.add("commerce.audit-archive.write-secret", () -> System.getenv("ARCHIVE_WRITE_SECRET"));
        registry.add("commerce.audit-archive.restore-key", () -> System.getenv("ARCHIVE_RESTORE_KEY"));
        registry.add("commerce.audit-archive.restore-secret", () -> System.getenv("ARCHIVE_RESTORE_SECRET"));
        registry.add("commerce.audit-archive.expiry-enabled", () -> System.getenv("ARCHIVE_EXPIRY_KEY") != null);
        registry.add("commerce.audit-archive.expiry-key", () -> System.getenv("ARCHIVE_EXPIRY_KEY"));
        registry.add("commerce.audit-archive.expiry-secret", () -> System.getenv("ARCHIVE_EXPIRY_SECRET"));
    }

    @Autowired ArchiveObjectStore objects;
    @Autowired AuditArchiveMapper mapper;
    @Autowired ArchiveOnlineCutoverMapper cutoverMapper;
    @Autowired ArchivePurgeRecoveryMapper purgeRecoveryMapper;
    @Autowired ArchiveExpiryMapper expiryMapper;
    @Autowired(required = false) ArchiveVersionDisposal versionDisposal;
    @Autowired OperationAudit audit;
    @Autowired PlatformTransactionManager tx;
    @Autowired JdbcTemplate jdbc;

    @Test
    void reusesOneObjectVersionAndChecksRestoreTicketsBeforePublishing() throws Exception {
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
            String batchId, long original, Actor actor) throws Exception {
        var scope = new AuditArchiveMapper.Scope("TENANT", TENANT);
        var publish = new AuditArchivePurgeRecovery(scope, mapper, cutoverMapper, purgeRecoveryMapper, expiryMapper, archive, objects, tx);
        var cutover = new AuditArchiveOnlineCutover(review, mapper, cutoverMapper, publish, tx);
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
        assertEquals(java.util.List.of(batchId), publish.inventory(null).unresolved());
        assertTrue(publish.inventory(null).committed().isEmpty());
        assertEquals(3, onlineCount());
        jdbc.update("DELETE FROM audit_archive_online_purge_receipt WHERE batch_id=? AND object_version='conflicting-fixture'", batchId);
        // 在正式切换前把这一批的真实在线状态恢复到另一份 MySQL schema，后续补放不沿用清理后的源库。
        var backup = restoreOlderBackup();
        assertEquals(Outcome.MOVED, cutover.move(batchId));
        assertEquals(Outcome.ALREADY_MOVED, cutover.move(batchId));
        var oldRecovery = checkBackupRecovery(publish, archive, backup, batchId, original);
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
        if (versionDisposal != null) checkExpiry(archive, publish, backup, oldRecovery, batchId, another);
    }

    /** 按备份时点复制关键原始表，目标 schema 用同一套 Flyway 结构、独立事务管理和 Mapper。 */
    private OlderBackup restoreOlderBackup() throws Exception {
        String sourceUrl = System.getenv("COMMERCE_TEST_DB_URL");
        String migrationUser = System.getenv("SPRING_FLYWAY_USER");
        String migrationPassword = System.getenv("SPRING_FLYWAY_PASSWORD");
        if (migrationUser == null || migrationPassword == null) {
            throw new IllegalStateException("旧备份演练需要隔离库的迁移账号，以创建专用恢复 schema");
        }
        var source = new org.springframework.jdbc.datasource.DriverManagerDataSource(sourceUrl, migrationUser, migrationPassword);
        String schema = "commerce_audit_backup_" + UUID.randomUUID().toString().replace("-", "");
        var sourceJdbc = new JdbcTemplate(source);
        sourceJdbc.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
        var target = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                sourceUrl.replace("/commerce_test_20260923?", "/" + schema + "?"), migrationUser, migrationPassword);
        org.flywaydb.core.Flyway.configure().dataSource(target).locations("classpath:db/migration").load().migrate();
        var restored = new JdbcTemplate(target);
        restored.update("""
            INSERT INTO audit_operation_attempt
            SELECT * FROM commerce_test_20260923.audit_operation_attempt WHERE actor_tenant_id=?
            """, TENANT);
        restored.update("""
            INSERT INTO platform_audit
            SELECT * FROM commerce_test_20260923.platform_audit WHERE tenant_id=? AND audit_attempt_id IS NOT NULL
            """, TENANT);
        restored.update("""
            INSERT INTO audit_attempt_outcome
            SELECT o.* FROM commerce_test_20260923.audit_attempt_outcome o
            JOIN audit_operation_attempt a ON a.id=o.attempt_id WHERE a.actor_tenant_id=?
            """, TENANT);

        var configuration = new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        var factory = new org.mybatis.spring.SqlSessionFactoryBean();
        factory.setConfiguration(configuration);
        factory.setDataSource(target);
        factory.setMapperLocations(new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                .getResources("classpath*:mappers/runtime/*Archive*Mapper.xml"));
        var session = new org.mybatis.spring.SqlSessionTemplate(java.util.Objects.requireNonNull(factory.getObject()));
        return new OlderBackup(restored, session, new org.springframework.jdbc.datasource.DataSourceTransactionManager(target));
    }

    private AuditArchivePurgeRecovery checkBackupRecovery(AuditArchivePurgeRecovery publish, AuditArchiveVerification archive,
            OlderBackup backup, String batchId, long original) {
        var scope = new AuditArchiveMapper.Scope("TENANT", TENANT);
        var proof = publish.publishNext();
        assertNotNull(proof);
        assertEquals(batchId, proof.batchId());
        assertEquals(proof, publish.publish(batchId));
        assertNull(publish.publishNext());
        var inventory = publish.inventory(null);
        assertEquals(java.util.List.of(proof), inventory.committed());
        assertTrue(inventory.unresolved().isEmpty());
        assertNull(inventory.continuation());

        var restore = new AuditArchivePurgeRecovery(scope, backup.session().getMapper(AuditArchiveMapper.class),
                backup.session().getMapper(ArchiveOnlineCutoverMapper.class),
                backup.session().getMapper(ArchivePurgeRecoveryMapper.class),
                backup.session().getMapper(ArchiveExpiryMapper.class), archive, objects, backup.manager());
        JdbcTemplate target = backup.jdbc();
        assertEquals(0, target.queryForObject("SELECT COUNT(*) FROM audit_archive_batch", Integer.class));
        assertEquals(3, backedUpAttempts(target));
        int unrelated = target.queryForObject("SELECT COUNT(*) FROM audit_operation_attempt", Integer.class) - backedUpAttempts(target);
        var modified = new AuditArchivePurgeRecovery.Reference(batchId, proof.version(), proof.byteCount(), "0".repeat(64));
        assertThrows(DomainException.class, () -> restore.replay(modified));
        target.update("UPDATE audit_operation_attempt SET resource_id='different-record' WHERE id=?", original);
        assertThrows(DomainException.class, () -> restore.replay(proof));
        assertEquals(0, target.queryForObject("SELECT COUNT(*) FROM audit_archive_batch", Integer.class));
        assertEquals(3, backedUpAttempts(target));

        target.update("UPDATE audit_operation_attempt SET resource_id='original' WHERE id=?", original);
        assertEquals(new AuditArchivePurgeRecovery.ReplayResult(batchId, 2, false), restore.replay(proof));
        assertEquals(new AuditArchivePurgeRecovery.ReplayResult(batchId, 0, true), restore.replay(proof));
        target.update("""
            UPDATE audit_archive_online_purge_receipt
            SET recovery_version=NULL,recovery_sha256=NULL,recovery_bytes=NULL WHERE batch_id=?
            """, batchId);
        assertEquals(new AuditArchivePurgeRecovery.ReplayResult(batchId, 0, true), restore.replay(proof));
        assertEquals(proof.version(), target.queryForObject(
                "SELECT recovery_version FROM audit_archive_online_purge_receipt WHERE batch_id=?", String.class, batchId));
        assertEquals(1, backedUpAttempts(target));
        assertEquals(unrelated + 1, target.queryForObject("SELECT COUNT(*) FROM audit_operation_attempt", Integer.class));
        assertEquals("still-awaiting", target.queryForObject(
                "SELECT resource_id FROM audit_operation_attempt WHERE action_code='catalog.category.bind'", String.class));
        assertEquals(1, target.queryForObject("SELECT COUNT(*) FROM audit_archive_effect_locator", Integer.class));
        assertEquals("ONLINE_PURGED", target.queryForObject("SELECT state FROM audit_archive_batch WHERE batch_id=?", String.class, batchId));
        return restore;
    }

    /** 使用隔离销毁身份真正删除合成档案版本，验证另一版本及外部凭证不受影响。 */
    private void checkExpiry(AuditArchiveVerification archive, AuditArchivePurgeRecovery recovery, OlderBackup backup,
            AuditArchivePurgeRecovery oldRecovery, String batchId, String restoreTicket) {
        var batch = mapper.batch(batchId);
        var scope = new AuditArchiveMapper.Scope("TENANT", TENANT);
        var clock = Clock.offset(Clock.systemUTC(), Duration.ofDays(5));
        var retention = new AuditArchiveRetentionReview(mapper, archive, scope, clock);
        var disposal = new ArchiveVersionDisposal() {
            @Override
            public boolean exists(String key, String version) {
                return versionDisposal.exists(key, version);
            }

            @Override
            public void delete(String key, String version) {
                versionDisposal.delete(key, version);
                throw new DomainException(DomainException.Code.UNAVAILABLE, "存储已删除版本但响应丢失");
            }
        };
        var workflow = new AuditArchiveObjectExpiry(scope, mapper, cutoverMapper, purgeRecoveryMapper, expiryMapper,
                archive, retention, recovery, disposal, clock, tx);
        assertEquals(AuditArchiveObjectExpiry.Outcome.INELIGIBLE, workflow.expire(batchId).outcome());
        jdbc.update("""
            INSERT INTO audit_archive_expiry_grant(batch_id,approval_reference,approved_by,effective_at,expires_at)
            VALUES(?, 'isolated-expiry','isolated-expiry-reviewer',UTC_TIMESTAMP(3)-INTERVAL 1 MINUTE,UTC_TIMESTAMP(3)+INTERVAL 1 HOUR)
            """, batchId);
        assertEquals(AuditArchiveObjectExpiry.Outcome.HELD, workflow.expire(batchId).outcome());
        jdbc.update("UPDATE audit_archive_restore_grant SET revoked_at=UTC_TIMESTAMP(3) WHERE ticket_id=?", restoreTicket);
        jdbc.update("""
            INSERT INTO audit_archive_hold(scope_type,tenant_id,resource_id,case_reference,created_by)
            VALUES('TENANT',?,'catalog-1','isolated-expiry-hold','investigator')
            """, TENANT);
        assertEquals(AuditArchiveObjectExpiry.Outcome.HELD, workflow.expire(batchId).outcome());
        jdbc.update("""
            UPDATE audit_archive_hold SET released_by='independent-reviewer',released_at=UTC_TIMESTAMP(3)
            WHERE tenant_id=? AND case_reference='isolated-expiry-hold'
            """, TENANT);
        String remainingVersion = storeFixtureVersion(batch.objectKey());
        assertNotEquals(batch.objectVersion(), remainingVersion);
        assertTrue(versionDisposal.exists(batch.objectKey(), batch.objectVersion()));
        backup.jdbc().update("""
            INSERT INTO audit_archive_late_evidence
            SELECT * FROM commerce_test_20260923.audit_archive_late_evidence WHERE batch_id=?
            """, batchId);

        if (LOCK_BUCKET.equals(BUCKET)) {
            fixtureLegalHold(batch.objectKey(), batch.objectVersion(), true);
            assertEquals("对象版本销毁暂无法确认",
                    assertThrows(DomainException.class, () -> workflow.expire(batchId)).getMessage());
            assertTrue(versionDisposal.exists(batch.objectKey(), batch.objectVersion()));
            assertEquals(java.util.List.of(batchId), recovery.expiryInventory(null).unresolved());
            fixtureLegalHold(batch.objectKey(), batch.objectVersion(), false);
        }

        assertEquals("存储已删除版本但响应丢失",
                assertThrows(DomainException.class, () -> workflow.expire(batchId)).getMessage());
        assertFalse(versionDisposal.exists(batch.objectKey(), batch.objectVersion()));
        assertTrue(versionDisposal.exists(batch.objectKey(), remainingVersion));
        assertEquals("ONLINE_PURGED", mapper.batch(batchId).state());
        assertEquals(java.util.List.of(batchId), recovery.expiryInventory(null).unresolved());
        var result = workflow.expire(batchId);
        assertEquals(AuditArchiveObjectExpiry.Outcome.EXPIRED, result.outcome());
        assertEquals(4, result.removedCopies());
        assertEquals(1, result.removedLateRows());
        assertEquals(new AuditArchiveObjectExpiry.Result(AuditArchiveObjectExpiry.Outcome.ALREADY_EXPIRED, 0, 0), workflow.expire(batchId));
        assertEquals("EXPIRED", mapper.batch(batchId).state());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_archive_restored WHERE batch_id=?", Integer.class, batchId));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM audit_archive_late_evidence WHERE batch_id=?", Integer.class, batchId));
        var expired = recovery.expiryInventory(null).committed().getFirst();
        backup.jdbc().update("""
            INSERT INTO audit_archive_restore_grant(ticket_id,batch_id,requested_by,approved_by,expires_at)
            VALUES('copied-ticket',?,'old-user','old-reviewer',UTC_TIMESTAMP(3)+INTERVAL 1 HOUR)
            """, batchId);
        backup.jdbc().update("""
            INSERT INTO audit_archive_restored(ticket_id,batch_id,attempt_id,payload,expires_at)
            SELECT 'copied-ticket',batch_id,attempt_id,JSON_OBJECT('fixture','old query copy'),UTC_TIMESTAMP(3)+INTERVAL 1 HOUR
            FROM audit_archive_member WHERE batch_id=?
            """, batchId);
        String originalLate = backup.jdbc().queryForObject(
                "SELECT payload FROM audit_archive_late_evidence WHERE batch_id=?", String.class, batchId);
        backup.jdbc().update("UPDATE audit_archive_late_evidence SET payload=JSON_OBJECT('fixture','different evidence') WHERE batch_id=?", batchId);
        assertThrows(DomainException.class, () -> oldRecovery.replayExpiry(expired));
        assertEquals(2, backup.jdbc().queryForObject("SELECT COUNT(*) FROM audit_archive_restored WHERE batch_id=?", Integer.class, batchId));
        assertEquals("ONLINE_PURGED", backup.jdbc().queryForObject(
                "SELECT state FROM audit_archive_batch WHERE batch_id=?", String.class, batchId));
        backup.jdbc().update("UPDATE audit_archive_late_evidence SET payload=? WHERE batch_id=?", originalLate, batchId);
        assertEquals(new AuditArchivePurgeRecovery.ExpiryReplay(batchId, 2, 1), oldRecovery.replayExpiry(expired));
        assertEquals(new AuditArchivePurgeRecovery.ExpiryReplay(batchId, 0, 0), oldRecovery.replayExpiry(expired));
        assertEquals("EXPIRED", backup.jdbc().queryForObject(
                "SELECT state FROM audit_archive_batch WHERE batch_id=?", String.class, batchId));
        assertTrue(versionDisposal.exists(batch.objectKey(), remainingVersion));
        assertEquals(1, onlineCount());
    }

    private String storeFixtureVersion(String key) {
        try (var client = fixtureClient()) {
            return client.putObject(request -> request.bucket(BUCKET).key(key),
                    software.amazon.awssdk.core.sync.RequestBody.fromString("new isolated fixture version")).versionId();
        }
    }

    /** 用测试桶管理员模拟真实保全，销毁执行器始终使用权限独立的应用身份。 */
    private void fixtureLegalHold(String key, String version, boolean enabled) {
        try (var client = fixtureClient()) {
            var status = enabled ? software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus.ON
                    : software.amazon.awssdk.services.s3.model.ObjectLockLegalHoldStatus.OFF;
            client.putObjectLegalHold(request -> request.bucket(BUCKET).key(key).versionId(version)
                    .legalHold(hold -> hold.status(status)));
        }
    }

    private software.amazon.awssdk.services.s3.S3Client fixtureClient() {
        var credentials = software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(
                System.getenv("MINIO_ROOT_USER"), System.getenv("MINIO_ROOT_PASSWORD"));
        return software.amazon.awssdk.services.s3.S3Client.builder()
                .endpointOverride(java.net.URI.create(System.getenv("ARCHIVE_TEST_ENDPOINT")))
                .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true)
                .requestChecksumCalculation(software.amazon.awssdk.core.checksums.RequestChecksumCalculation.WHEN_REQUIRED)
                .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(credentials)).build();
    }

    private int backedUpAttempts(JdbcTemplate target) {
        return target.queryForObject("SELECT COUNT(*) FROM audit_operation_attempt WHERE action_code='catalog.category.bind'", Integer.class);
    }

    private record OlderBackup(JdbcTemplate jdbc, org.mybatis.spring.SqlSessionTemplate session,
                               PlatformTransactionManager manager) {}

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
        public InventoryPage inventory(String prefix, String continuation, int limit) {
            return real.inventory(prefix, continuation, limit);
        }

        @Override
        public ObjectVersion currentVersion(String key) {
            return real.currentVersion(key);
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
