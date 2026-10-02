package com.kerosene.kfe.maintenance;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.kerosene.kfe.service.TransactionEventPublisher;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.time.Duration;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static com.kerosene.kfe.maintenance.KfeMaintenanceGuard.*;
import static org.mockito.Mockito.*;

/** Dedicated disposable fixture only; does not claim full financial-schema integration. */
@EnabledIfEnvironmentVariable(named = "KFE_MAINTENANCE_POSTGRES_DISPOSABLE", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
class KfeMaintenancePostgresTest {
    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private JdbcKfeMaintenanceStore store;

    @BeforeAll
    static void setupDisposableDatabase() throws Exception {
        String url = System.getenv("KFE_MAINTENANCE_POSTGRES_URL");
        assertThat(url).as("Coordinator-provided disposable PostgreSQL JDBC URL").isNotBlank();
        dataSource = new DriverManagerDataSource(url,
                System.getenv("KFE_MAINTENANCE_POSTGRES_USER"), System.getenv("KFE_MAINTENANCE_POSTGRES_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .as("Refuse application databases").startsWith("kfe_maintenance_test_");
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS financial");
        // The coordinator-owned migration is the sole maintenance DDL source.
        ClassPathResource migration = new ClassPathResource("db/migration/V57__kfe_maintenance_admission.sql");
        assertThat(migration.exists()).isTrue();
        if (jdbc.queryForObject("SELECT to_regclass('financial.kfe_maintenance_control')", String.class) == null) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    ScriptUtils.executeSqlScript(connection, migration);
                    connection.commit();
                } catch (Exception failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        }
        if (jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = 'financial' AND table_name = 'kfe_maintenance_admissions'
                  AND column_name = 'parent_admission_id'
                """, Integer.class) == 0) {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try {
                    ScriptUtils.executeSqlScript(connection, new ClassPathResource(
                            "db/migration/V58__kfe_maintenance_continuations.sql"));
                    connection.commit();
                } catch (Exception failure) {
                    connection.rollback();
                    throw failure;
                }
            }
        }
        // Minimal financial status fixtures, not a replacement for production migrations.
        jdbc.execute("CREATE TABLE IF NOT EXISTS financial.financial_execution_outbox (id UUID PRIMARY KEY, status TEXT, lease_expires_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS financial.transactions_master (id UUID PRIMARY KEY, status TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS financial.channel_capacity_jobs (id UUID PRIMARY KEY, status TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS financial.channel_rebalance_jobs (id UUID PRIMARY KEY, status TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS financial.channel_operation_decisions (id UUID PRIMARY KEY, mesh_inject_phase TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS financial.kfe_psbt_workflows (id UUID PRIMARY KEY, status TEXT)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS financial.maintenance_fixture_effects (id UUID PRIMARY KEY)");
    }

    @BeforeEach
    void resetExclusiveFixture() {
        for (String table : List.of("kfe_maintenance_admissions", "kfe_maintenance_audit",
                "financial_execution_outbox", "transactions_master", "channel_capacity_jobs",
                "channel_rebalance_jobs", "channel_operation_decisions", "kfe_psbt_workflows",
                "maintenance_fixture_effects")) {
            jdbc.update("DELETE FROM financial." + table);
        }
        jdbc.update("""
                UPDATE financial.kfe_maintenance_control SET mode = 'ACTIVE', change_id = NULL,
                revision = 0, changed_at = CURRENT_TIMESTAMP, operator_id = NULL, reason = NULL
                WHERE singleton_id = 1
                """);
        store = newStore(dataSource);
    }

    @Test
    void transitionsAuditReplayConflictsAndResumeOwnership() {
        Command drain = new Command("update", "upgrade", 0);
        assertThat(store.transition(Action.DRAIN, drain, 42).revision()).isEqualTo(1);
        assertThat(store.transition(Action.DRAIN, drain, 42).revision()).isEqualTo(1);
        assertThat(count("kfe_maintenance_audit")).isEqualTo(1);
        assertThatThrownBy(() -> store.transition(Action.DRAIN, drain, 99)).isInstanceOf(MaintenanceException.class);
        assertThatThrownBy(() -> store.transition(Action.RESUME, new Command("other", "resume", 1), 42))
                .isInstanceOf(MaintenanceException.class);
        assertThatThrownBy(() -> store.transition(Action.RESUME, new Command("update", "resume", 0), 42))
                .isInstanceOf(MaintenanceException.class);
        Command resume = new Command("update", "resume", 1);
        assertThat(store.transition(Action.RESUME, resume, 42).mode()).isEqualTo(Mode.ACTIVE);
        assertThat(store.transition(Action.RESUME, resume, 42).revision()).isEqualTo(2);
        assertThat(count("kfe_maintenance_audit")).isEqualTo(2);
        assertThat(store.transition(Action.DRAIN, drain, 42).mode()).isEqualTo(Mode.ACTIVE);
        assertThat(count("kfe_maintenance_audit")).isEqualTo(2);
    }

    @Test
    void auditFailureCannotPublishDrain() {
        jdbc.execute("ALTER TABLE financial.kfe_maintenance_audit ADD CONSTRAINT fixture_reject_change CHECK (change_id <> 'reject')");
        try {
            assertThatThrownBy(() -> store.transition(Action.DRAIN, new Command("reject", "upgrade", 0), 42))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(store.observe().control().mode()).isEqualTo(Mode.ACTIVE);
            assertThat(store.observe().control().revision()).isZero();
            assertThat(count("kfe_maintenance_audit")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE financial.kfe_maintenance_audit DROP CONSTRAINT fixture_reject_change");
        }
    }

    @Test
    void admissionSurvivesStoreRecreationAndDrainAndDoesNotExpire() {
        var admission = store.admit("provider.execute");
        jdbc.update("UPDATE financial.kfe_maintenance_admissions SET admitted_at = CURRENT_TIMESTAMP - INTERVAL '1 year' WHERE id = ?",
                admission.id());
        JdbcKfeMaintenanceStore restarted = newStore(dataSource);
        restarted.transition(Action.DRAIN, new Command("update", "upgrade", 0), 42);
        assertThat(restarted.observe().blockers()).containsEntry("admissionsInFlight", 1L);
        assertThatThrownBy(() -> restarted.admit("provider.execute")).isInstanceOf(MaintenanceException.class);
        restarted.resolve(admission.id(), false);
        restarted.resolve(admission.id(), true);
        assertThat(newStore(dataSource).observe().blockers()).containsEntry("admissionsUncertain", 1L);
    }

    @Test
    void financialRollbackLeavesDurableUncertaintyAndNoLocalEffect() {
        KfeMaintenanceService service = new KfeMaintenanceService(store);
        TransactionTemplate financial = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        financial.execute(status -> {
            service.executeMutation("provider.execute", () -> {
                jdbc.update("INSERT INTO financial.maintenance_fixture_effects VALUES (?)", UUID.randomUUID());
                return "provider replied";
            });
            status.setRollbackOnly();
            return true;
        });
        assertThat(count("maintenance_fixture_effects")).isZero();
        assertThat(store.observe().blockers()).containsEntry("admissionsUncertain", 1L);
    }

    @Test
    void processingUnknownReconciliationAndUnknownStatusesBlockEvenWithoutQueue() {
        for (String status : List.of("PROCESSING", "UNKNOWN", "FUTURE_PROVIDER_STATE")) {
            jdbc.update("INSERT INTO financial.financial_execution_outbox (id, status) VALUES (?, ?)", UUID.randomUUID(), status);
        }
        jdbc.update("UPDATE financial.financial_execution_outbox SET lease_expires_at = CURRENT_TIMESTAMP - INTERVAL '1 year' WHERE status = 'PROCESSING'");
        jdbc.update("INSERT INTO financial.transactions_master VALUES (?, 'REORG_RECONCILIATION')", UUID.randomUUID());
        jdbc.update("INSERT INTO financial.channel_capacity_jobs VALUES (?, 'UNCLASSIFIED')", UUID.randomUUID());
        jdbc.update("INSERT INTO financial.channel_operation_decisions VALUES (?, 'FUTURE_MESH_PHASE')", UUID.randomUUID());
        jdbc.update("INSERT INTO financial.kfe_psbt_workflows VALUES (?, 'FUTURE_PSBT_STATE')", UUID.randomUUID());
        store.transition(Action.DRAIN, new Command("update", "upgrade", 0), 42);
        var blockers = store.observe().blockers();
        assertThat(blockers).containsEntry("outboxQueued", 0L).containsEntry("outboxInFlight", 1L)
                .containsEntry("outboxUncertain", 1L).containsEntry("outboxStatusUnknown", 1L)
                .containsEntry("transactionsReconciliation", 1L).containsEntry("capacityStatusUnknown", 1L)
                .containsEntry("meshUnresolved", 1L).containsEntry("psbtStatusUnknown", 1L);
        assertThat(new KfeMaintenanceService(store).status().safeToUpdate()).isFalse();
    }

    @Test
    void drainWinsAgainstAnAdmissionWaitingOnTheSameRow() throws Exception {
        competingAdmission(false);
    }

    @Test
    void admissionWinsBeforeDrainAndRemainsVisibleToDrainer() throws Exception {
        competingAdmission(true);
    }

    @Test
    void durableChildrenSurviveRestartDrainAndParentCompletionAndCannotReplay() {
        var parent = store.admit("payment");
        var waiting = store.captureContinuation(parent.id(), "after-commit", true);
        var ready = store.captureContinuation(parent.id(), "async", false);
        store.transition(Action.DRAIN, new Command("update", "upgrade", 0), 42);
        store.resolve(parent.id(), true);
        JdbcKfeMaintenanceStore restarted = newStore(dataSource);
        assertThat(restarted.observe().blockers()).containsEntry("continuationsWaiting", 1L)
                .containsEntry("continuationsReady", 1L);
        assertThatThrownBy(() -> restarted.claimContinuation(waiting.id())).isInstanceOf(MaintenanceException.class);
        restarted.releaseContinuation(waiting.id(), true);
        assertThat(restarted.claimContinuation(waiting.id()).revision()).isEqualTo(parent.revision());
        assertThatThrownBy(() -> restarted.claimContinuation(waiting.id())).isInstanceOf(MaintenanceException.class);
        restarted.resolve(waiting.id(), true);
        restarted.claimContinuation(ready.id());
        restarted.resolve(ready.id(), false);
        restarted.resolve(ready.id(), true);
        assertThat(restarted.observe().blockers()).containsEntry("admissionsUncertain", 1L);
        assertThatThrownBy(() -> restarted.captureContinuation(parent.id(), "late", false))
                .isInstanceOf(MaintenanceException.class);
        assertThatThrownBy(() -> restarted.captureContinuation(UUID.randomUUID(), "invented", false))
                .isInstanceOf(MaintenanceException.class);
    }

    @Test
    void rollbackCanCancelOnlyWaitingChildrenNeverClaimedOrReadyWork() {
        var parent = store.admit("payment");
        var waiting = store.captureContinuation(parent.id(), "after-commit", true);
        var ready = store.captureContinuation(parent.id(), "async", false);
        store.releaseContinuation(waiting.id(), false);
        assertThatThrownBy(() -> store.claimContinuation(waiting.id())).isInstanceOf(MaintenanceException.class);
        assertThatThrownBy(() -> store.releaseContinuation(ready.id(), false)).isInstanceOf(MaintenanceException.class);
        store.claimContinuation(ready.id());
        assertThatThrownBy(() -> store.releaseContinuation(ready.id(), false)).isInstanceOf(MaintenanceException.class);
        assertThat(store.observe().blockers()).containsEntry("admissionsInFlight", 2L)
                .containsEntry("continuationsWaiting", 0L);
    }

    @Test
    void concurrentClaimExecutesAtMostOnceAfterDrain() throws Exception {
        var parent = store.admit("payment");
        var child = store.captureContinuation(parent.id(), "async", false);
        store.transition(Action.DRAIN, new Command("update", "upgrade", 0), 42);
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch start = new CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> claim = () -> {
                start.await();
                try { store.claimContinuation(child.id()); return true; }
                catch (MaintenanceException expected) { return false; }
            };
            var first = executor.submit(claim);
            var second = executor.submit(claim);
            start.countDown();
            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualTransactionPublisherCapturesBeforeCommitAndDeliversAsChildDuringDrain(boolean transportFails)
            throws Exception {
        var service = new KfeMaintenanceService(store);
        var queued = new ArrayDeque<Runnable>();
        var transport = mock(SimpMessagingTemplate.class);
        Map<String, Object> body = Map.of("transactionId", "synthetic-display-only");
        if (transportFails) {
            doThrow(new IllegalStateException("synthetic transport failure")).when(transport)
                    .convertAndSendToUser("7", TransactionEventPublisher.DESTINATION, body);
        }
        var publisher = transactionPublisher(service, transport, queued);
        var financial = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        financial.executeWithoutResult(status -> {
            publisher.publishAfterCommit(7L, body);
            assertThat(publisherChildState()).isEqualTo("WAITING");
            assertThat(queued).isEmpty(); verifyNoInteractions(transport);
            store.transition(Action.DRAIN, new Command("publisher-update", "synthetic drain", 0), 42);
        });
        assertThat(publisherChildState()).isEqualTo("READY");
        assertThat(queued).hasSize(1);
        assertThat(newStore(dataSource).observe().blockers()).containsEntry("continuationsReady", 1L);
        assertThatThrownBy(() -> publisher.publishAfterCommit(7L, body)).isInstanceOf(MaintenanceException.class);
        assertThat(queued).hasSize(1); verifyNoInteractions(transport);
        // Run the retained closure after Spring has released the transaction context.
        queued.remove().run();
        verify(transport).convertAndSendToUser("7", TransactionEventPublisher.DESTINATION, body);
        assertThat(publisherChildState()).isEqualTo("UNCERTAIN");
        UUID childId = jdbc.queryForObject("SELECT id FROM financial.kfe_maintenance_admissions "
                + "WHERE operation = 'publisher.transaction.delivery'", UUID.class);
        newStore(dataSource).resolve(childId, true);
        assertThat(publisherChildState()).isEqualTo("UNCERTAIN");
        assertThat(service.status().safeToUpdate()).isFalse();
    }

    @Test
    void actualPublisherRollbackCancelsOnlyUnstartedChildAndNeverCallsTransport() throws Exception {
        var queued = new ArrayDeque<Runnable>();
        var transport = mock(SimpMessagingTemplate.class);
        var publisher = transactionPublisher(new KfeMaintenanceService(store), transport, queued);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status -> {
            publisher.publishAfterCommit(7L, Map.of("transactionId", "synthetic-rollback"));
            assertThat(publisherChildState()).isEqualTo("WAITING");
            status.setRollbackOnly();
        });
        assertThat(publisherChildState()).isEqualTo("CANCELLED");
        assertThat(queued).isEmpty(); verifyNoInteractions(transport);
        assertThat(newStore(dataSource).observe().blockers()).containsEntry("admissionsUncertain", 1L);
    }

    @Test
    void losingPublisherClosureDoesNotEraseReadyChildOrClaimRestartRecovery() throws Exception {
        var queued = new ArrayDeque<Runnable>();
        var transport = mock(SimpMessagingTemplate.class);
        var publisher = transactionPublisher(new KfeMaintenanceService(store), transport, queued);
        new TransactionTemplate(new DataSourceTransactionManager(dataSource)).executeWithoutResult(status ->
                publisher.publishAfterCommit(7L, Map.of("transactionId", "synthetic-lost-closure")));
        assertThat(queued).hasSize(1);
        queued.clear(); // Model loss of the in-memory closure, not a durable replay implementation.
        var restarted = newStore(dataSource);
        restarted.transition(Action.DRAIN, new Command("publisher-update", "synthetic drain", 0), 42);
        assertThat(restarted.observe().blockers()).containsEntry("continuationsReady", 1L);
        assertThat(new KfeMaintenanceService(restarted).status().safeToUpdate()).isFalse();
        assertThat(publisherChildState()).isEqualTo("READY"); verifyNoInteractions(transport);
    }

    private static TransactionEventPublisher transactionPublisher(KfeMaintenanceGuard guard,
            SimpMessagingTemplate transport, ArrayDeque<Runnable> queued) throws Exception {
        @SuppressWarnings("unchecked") ObjectProvider<SimpMessagingTemplate> provider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked") ObjectProvider<com.kerosene.kfe.integration.KfeRemoteStompRelayClient> relay = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(transport);
        var constructor = TransactionEventPublisher.class.getDeclaredConstructor(
                ObjectProvider.class, ObjectProvider.class, java.util.concurrent.Executor.class);
        constructor.setAccessible(true);
        TransactionEventPublisher publisher = constructor.newInstance(provider, relay,
                (java.util.concurrent.Executor) queued::add);
        publisher.setMaintenanceGuard(guard);
        return publisher;
    }

    private String publisherChildState() {
        return jdbc.queryForObject("SELECT state FROM financial.kfe_maintenance_admissions "
                + "WHERE operation = 'publisher.transaction.delivery'", String.class);
    }

    private void competingAdmission(boolean admissionWins) throws Exception {
        try (Connection winner = dataSource.getConnection(); Connection loser = dataSource.getConnection();
             var executor = Executors.newSingleThreadExecutor()) {
            winner.setAutoCommit(false);
            try (var statement = winner.createStatement()) {
                statement.execute("SELECT singleton_id FROM financial.kfe_maintenance_control WHERE singleton_id = 1 FOR UPDATE");
            }
            var winnerStore = newStore(new SingleConnectionDataSource(winner, true));
            var loserDataSource = new SingleConnectionDataSource(loser, true);
            int loserPid = new JdbcTemplate(loserDataSource).queryForObject("SELECT pg_backend_pid()", Integer.class);
            var loserStore = newStore(loserDataSource);
            CountDownLatch attempting = new CountDownLatch(1);
            var future = executor.submit(() -> {
                attempting.countDown();
                if (admissionWins) {
                    return loserStore.transition(Action.DRAIN, new Command("update", "upgrade", 0), 42);
                }
                return loserStore.admit("provider.execute");
            });
            try {
                assertThat(attempting.await(5, TimeUnit.SECONDS)).isTrue();
                awaitDatabaseLock(loserPid);
                if (admissionWins) {
                    winnerStore.admit("provider.execute");
                    assertThat(future.get(5, TimeUnit.SECONDS)).isInstanceOf(KfeMaintenanceStore.Control.class);
                    assertThat(store.observe().blockers()).containsEntry("admissionsInFlight", 1L);
                } else {
                    winnerStore.transition(Action.DRAIN, new Command("update", "upgrade", 0), 42);
                    assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                            .hasCauseInstanceOf(MaintenanceException.class);
                    assertThat(count("kfe_maintenance_admissions")).isZero();
                }
                assertThat(store.observe().control().mode()).isEqualTo(Mode.DRAINING);
            } finally {
                winner.rollback();
                future.cancel(true);
                executor.shutdownNow();
            }
        }
    }

    private void awaitDatabaseLock(int pid) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            String wait = jdbc.queryForObject("SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?", String.class, pid);
            if ("Lock".equals(wait)) { return; }
            Thread.onSpinWait();
        }
        fail("Competing connection did not enter a PostgreSQL lock wait.");
    }

    private static JdbcKfeMaintenanceStore newStore(javax.sql.DataSource source) {
        return new JdbcKfeMaintenanceStore(new JdbcTemplate(source), new DataSourceTransactionManager(source));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM financial." + table, Long.class);
    }
}
