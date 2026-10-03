package com.kerosene.kfe.maintenance;

import java.util.UUID;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.audit.StructuredAuditLogger;
import com.kerosene.kfe.model.KfeAuditLogEntity;
import com.kerosene.kfe.repository.KfeAuditLogRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.model.KfeBalanceEntity;
import com.kerosene.kfe.model.KfeBalanceId;
import com.kerosene.kfe.model.KfeDerivationCursorEntity;
import com.kerosene.kfe.repository.KfeBalanceRepository;
import com.kerosene.kfe.repository.KfeDerivationCursorRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;
import com.kerosene.kfe.service.BalanceEventPublisher;
import com.kerosene.kfe.service.KfeBalanceService;
import com.kerosene.kfe.service.KfeDerivationCursorService;
import com.kerosene.kfe.service.KfeHashService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.SharedEntityManagerCreator;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;
import static com.kerosene.kfe.maintenance.KfeMaintenanceGuard.*;
import static org.mockito.Mockito.*;

/** Real complete migration chain in a separately created, exclusive disposable database. */
@EnabledIfEnvironmentVariable(named = "KFE_MAINTENANCE_FULL_SCHEMA_DISPOSABLE", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
class KfeMaintenanceFinancialSchemaTest {
    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private static Flyway flyway;
    private static LocalContainerEntityManagerFactoryBean jpa;
    private static JpaTransactionManager transactions;
    private static KfeBalanceRepository balances;
    private static KfeDerivationCursorRepository cursors;
    private static KfeAuditLogRepository auditRows;

    @BeforeAll
    static void migrateExactApplicationResources() {
        String url = System.getenv("KFE_MAINTENANCE_FULL_SCHEMA_URL");
        assertThat(url).isNotBlank();
        source = new DriverManagerDataSource(url, System.getenv("KFE_MAINTENANCE_POSTGRES_USER"),
                System.getenv("KFE_MAINTENANCE_POSTGRES_PASSWORD"));
        jdbc = new JdbcTemplate(source);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .as("Refuse application and minimal-fixture databases")
                .startsWith("kfe_maintenance_test_full_schema_");
        flyway = Flyway.configure().dataSource(source).locations("classpath:db/migration")
                .cleanDisabled(true).validateOnMigrate(true).load();
        flyway.migrate();
        // A synthetic deferred constraint makes the database reject commit, not the service body.
        jdbc.execute("CREATE TABLE IF NOT EXISTS financial.maintenance_participant_commit_failure "
                + "(id UUID NOT NULL, UNIQUE (id) DEFERRABLE INITIALLY DEFERRED)");
        // Exact financial entities/repositories; no generated replacement schema or providers.
        jpa = new LocalContainerEntityManagerFactoryBean();
        jpa.setDataSource(source);
        jpa.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        jpa.setManagedTypes(PersistenceManagedTypes.of(KfeBalanceEntity.class.getName(),
                KfeBalanceId.class.getName(), KfeDerivationCursorEntity.class.getName(), KfeAuditLogEntity.class.getName()));
        jpa.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "validate"));
        jpa.afterPropertiesSet();
        transactions = new JpaTransactionManager(jpa.getObject());
        var repositories = new JpaRepositoryFactory(SharedEntityManagerCreator.createSharedEntityManager(jpa.getObject()));
        balances = repositories.getRepository(KfeBalanceRepository.class);
        cursors = repositories.getRepository(KfeDerivationCursorRepository.class);
        auditRows = repositories.getRepository(KfeAuditLogRepository.class);
    }

    @AfterAll
    static void closeJpa() {
        if (jpa != null) { jpa.destroy(); }
    }

    @Test
    void allRealMigrationsValidateAndStatusPredicatesMatchFinancialTables() {
        flyway.validate();
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("58");
        JdbcKfeMaintenanceStore store = store();
        assertThat(store.observe().blockers()).containsKeys("transactionsPending", "transactionsReconciliation",
                "outboxQueued", "outboxInFlight", "psbtOutstanding", "meshUnresolved", "continuationsWaiting");
        assertThat(new KfeMaintenanceService(store).status().safeToUpdate()).isFalse();
    }

    @Test
    void transitionsAndChildCompletionWorkOnTheMigratedSchema() {
        JdbcKfeMaintenanceStore store = store();
        var control = store.observe().control();
        assertThat(control.mode()).isEqualTo(Mode.ACTIVE);
        String change = "full-schema-" + UUID.randomUUID();
        var parent = store.admit("full-schema.root");
        var child = store.captureContinuation(parent.id(), "full-schema.child", false);
        var drain = store.transition(Action.DRAIN, new Command(change, "disposable migration test", control.revision()), 42);
        assertThat(store.observe().blockers().get("continuationsReady")).isGreaterThanOrEqualTo(1);
        assertThatThrownBy(() -> store.admit("new-root")).isInstanceOf(MaintenanceException.class);
        store.claimContinuation(child.id());
        store.resolve(child.id(), true);
        store.resolve(parent.id(), true);
        store.transition(Action.RESUME, new Command(change, "test complete", drain.revision()), 42);
        assertThat(store.observe().control().mode()).isEqualTo(Mode.ACTIVE);
    }

    @Test
    void realCursorProxyCommitsAndRecreatedStoreSeesCompletedLocalAdmission() {
        String key = "fixture-" + UUID.randomUUID();
        assertThat(cursorService().nextIndex(key)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_issued_index FROM financial.custodial_derivation_cursors WHERE cursor_key = ?",
                Integer.class, key)).isZero();
        assertThat(admissionState("derivation.next-index")).isEqualTo("COMPLETED");
        assertThat(cursorService().nextIndex(key)).isEqualTo(1);
        assertThat(admissionState("derivation.next-index")).isEqualTo("COMPLETED");
        jdbc.update("DELETE FROM financial.custodial_derivation_cursors WHERE cursor_key = ?", key);
    }

    @Test
    void realCursorRollbackKeepsDurableUncertaintyAndNoIssuedIndex() {
        String key = "fixture-" + UUID.randomUUID();
        new TransactionTemplate(transactions).execute(status -> {
            assertThat(cursorService().nextIndex(key)).isZero();
            assertThat(admissionState("derivation.next-index")).isEqualTo("IN_FLIGHT");
            status.setRollbackOnly(); return null;
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial.custodial_derivation_cursors WHERE cursor_key = ?",
                Long.class, key)).isZero();
        assertThat(admissionState("derivation.next-index")).isEqualTo("UNCERTAIN");
        assertThat(new KfeMaintenanceService(participantStore()).status().safeToUpdate()).isFalse();
    }

    @Test
    void actualDrainRejectsCursorBeforeAnyJpaWrite() {
        JdbcKfeMaintenanceStore store = participantStore();
        var control = store.observe().control();
        String change = "participant-" + UUID.randomUUID();
        String key = "fixture-" + UUID.randomUUID();
        var drain = store.transition(Action.DRAIN, new Command(change, "disposable participant drain", control.revision()), 42);
        try {
            assertThatThrownBy(() -> cursorService().nextIndex(key)).isInstanceOf(MaintenanceException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial.custodial_derivation_cursors WHERE cursor_key = ?",
                    Long.class, key)).isZero();
        } finally {
            store.transition(Action.RESUME, new Command(change, "disposable participant cleanup", drain.revision()), 42);
        }
    }

    @Test
    void databaseCommitRejectionKeepsCursorAndAdmissionUnresolvedDespiteMethodReturn() {
        String key = "fixture-" + UUID.randomUUID();
        UUID collision = UUID.randomUUID();
        try {
            assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
                assertThat(cursorService().nextIndex(key)).isZero();
                assertThat(admissionState("derivation.next-index")).isEqualTo("IN_FLIGHT");
                jdbc.update("INSERT INTO financial.maintenance_participant_commit_failure VALUES (?)", collision);
                jdbc.update("INSERT INTO financial.maintenance_participant_commit_failure VALUES (?)", collision);
                return null;
            })).isInstanceOf(org.springframework.dao.DuplicateKeyException.class)
                    .hasRootCauseInstanceOf(org.postgresql.util.PSQLException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial.custodial_derivation_cursors WHERE cursor_key = ?",
                    Long.class, key)).isZero();
            assertThat(admissionState("derivation.next-index")).isEqualTo("UNCERTAIN");
            assertThat(new KfeMaintenanceService(participantStore()).status().safeToUpdate()).isFalse();
        } finally {
            jdbc.update("DELETE FROM financial.maintenance_participant_commit_failure WHERE id = ?", collision);
            jdbc.update("DELETE FROM financial.custodial_derivation_cursors WHERE cursor_key = ?", key);
        }
    }

    @Test
    void admittedExistingCursorWritersFinishAcrossDrainWithoutDuplicateIndices() throws Exception {
        String key = "fixture-" + UUID.randomUUID();
        assertThat(cursorService().nextIndex(key)).isZero();
        long before = cursorAdmissionCount();
        CountDownLatch firstIssued = new CountDownLatch(1);
        CountDownLatch releaseCommit = new CountDownLatch(1);
        String change = "cursor-race-" + UUID.randomUUID();
        JdbcKfeMaintenanceStore store = participantStore();
        KfeMaintenanceStore.Control drain = null;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                int index = cursorService().nextIndex(key);
                firstIssued.countDown();
                try {
                    if (!releaseCommit.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Fixture commit release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
                }
                return index;
            }));
            try {
                assertThat(firstIssued.await(5, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> cursorService().nextIndex(key));
                long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
                while (cursorAdmissionCount() < before + 2 && System.nanoTime() < deadline) {
                    Thread.sleep(20);
                }
                // The second workflow is durably admitted but cannot obtain the first row lock.
                assertThat(cursorAdmissionCount()).isEqualTo(before + 2);
                assertThat(second.isDone()).isFalse();
                var control = store.observe().control();
                drain = store.transition(Action.DRAIN, new Command(change, "disposable cursor race", control.revision()), 42);
                assertThat(store.observe().blockers().get("admissionsInFlight")).isGreaterThanOrEqualTo(2);
                releaseCommit.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
                assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT last_issued_index FROM financial.custodial_derivation_cursors WHERE cursor_key = ?",
                        Integer.class, key)).isEqualTo(2);
                assertThatThrownBy(() -> cursorService().nextIndex(key)).isInstanceOf(MaintenanceException.class);
                assertThat(new KfeMaintenanceService(store).status().safeToUpdate()).isFalse();
            } finally { releaseCommit.countDown(); }
        } finally {
            if (drain != null) {
                store.transition(Action.RESUME, new Command(change, "disposable cursor race cleanup", drain.revision()), 42);
            }
            jdbc.update("DELETE FROM financial.custodial_derivation_cursors WHERE cursor_key = ?", key);
        }
    }

    private static long cursorAdmissionCount() {
        return jdbc.queryForObject("SELECT count(*) FROM financial.kfe_maintenance_admissions "
                + "WHERE operation = 'derivation.next-index'", Long.class);
    }

    @Test
    void realBalanceGenesisCompletesOnlyAfterJpaCommitAndCreditRemainsUncertain() {
        UUID wallet = fixtureWallet();
        try {
            KfeBalanceService service = balanceService();
            new TransactionTemplate(transactions).execute(status -> {
                service.createEmptyBalance(wallet, "BTC");
                assertThat(admissionState("balance.create-empty")).isEqualTo("IN_FLIGHT");
                return null;
            });
            assertThat(admissionState("balance.create-empty")).isEqualTo("COMPLETED");
            new TransactionTemplate(transactions).execute(status -> service.creditAvailable(wallet, "BTC", 12));
            assertThat(jdbc.queryForObject("SELECT available_sats FROM financial.balances_core WHERE wallet_id = ? AND asset = 'BTC'",
                    Long.class, wallet)).isEqualTo(12);
            assertThat(admissionState("balance.credit-available")).isEqualTo("UNCERTAIN");
            assertThat(new KfeMaintenanceService(participantStore()).status().safeToUpdate()).isFalse();
        } finally { deleteFixtureWallet(wallet); }
    }

    @Test
    void realBalanceRollbackRestoresBucketsAndHashButRetainsAdmission() {
        UUID wallet = fixtureWallet();
        try {
            KfeBalanceService service = balanceService();
            new TransactionTemplate(transactions).execute(status -> service.createEmptyBalance(wallet, "BTC"));
            String originalHash = jdbc.queryForObject("SELECT last_hash FROM financial.balances_core WHERE wallet_id = ?",
                    String.class, wallet);
            new TransactionTemplate(transactions).execute(status -> {
                service.creditAvailable(wallet, "BTC", 15);
                status.setRollbackOnly(); return null;
            });
            assertThat(jdbc.queryForObject("SELECT available_sats FROM financial.balances_core WHERE wallet_id = ?",
                    Long.class, wallet)).isZero();
            assertThat(jdbc.queryForObject("SELECT last_hash FROM financial.balances_core WHERE wallet_id = ?",
                    String.class, wallet)).isEqualTo(originalHash);
            assertThat(admissionState("balance.credit-available")).isEqualTo("UNCERTAIN");
        } finally { deleteFixtureWallet(wallet); }
    }

    @Test
    void actualRequiredAuditJoinsRollbackAndDoesNotClearItsDurableAdmission() {
        AtomicReference<UUID> event = new AtomicReference<>();
        new TransactionTemplate(transactions).execute(status -> {
            var saved = auditService().record("KFE_SETTLEMENT_COMPLETED", null, null, null, null,
                    Map.of("reason", "disposable REQUIRED", "fixture", UUID.randomUUID().toString()));
            event.set(saved.getId());
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial.financial_audit_log WHERE id = ?",
                    Long.class, event.get())).isEqualTo(1);
            assertThat(admissionState("audit.record")).isEqualTo("IN_FLIGHT");
            status.setRollbackOnly(); return null;
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM financial.financial_audit_log WHERE id = ?",
                Long.class, event.get())).isZero();
        assertThat(admissionState("audit.record")).isEqualTo("UNCERTAIN");
    }

    @Test
    void actualRequiresNewAuditSurvivesOuterRollbackWithoutManufacturingDeliveryProof() {
        UUID marker = UUID.randomUUID();
        AtomicReference<UUID> event = new AtomicReference<>();
        try {
            new TransactionTemplate(transactions).execute(status -> {
                jdbc.update("INSERT INTO financial.maintenance_participant_commit_failure VALUES (?)", marker);
                var saved = auditService().recordInNewTransaction("KFE_SETTLEMENT_COMPLETED", null, null, null, null,
                        Map.of("reason", "disposable REQUIRES_NEW", "fixture", marker.toString()));
                event.set(saved.getId());
                assertThat(admissionState("audit.record-new-transaction")).isEqualTo("UNCERTAIN");
                status.setRollbackOnly(); return null;
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial.maintenance_participant_commit_failure WHERE id = ?",
                    Long.class, marker)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial.financial_audit_log WHERE id = ?",
                    Long.class, event.get())).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM financial.financial_audit_log WHERE id = ?", event.get()))
                    .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class)
                    .hasMessageContaining("append-only");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial.financial_audit_log WHERE id = ?",
                    Long.class, event.get())).isEqualTo(1);
            assertThat(new KfeMaintenanceService(participantStore()).status().safeToUpdate()).isFalse();
        } finally {
            // Retain the synthetic forensic row: the exact schema forbids audit deletion.
            // Never disable the append-only trigger or rewrite a chain for fixture cleanup.
            jdbc.update("DELETE FROM financial.maintenance_participant_commit_failure WHERE id = ?", marker);
        }
    }

    private static KfeAuditLogService auditService() {
        KfeAuditLogService service = new KfeAuditLogService(auditRows, new KfeHashService(),
                new ObjectMapper(), mock(StructuredAuditLogger.class));
        service.setMaintenanceGuard(new KfeMaintenanceService(participantStore()));
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (KfeAuditLogService) proxy.getProxy();
    }

    private static KfeDerivationCursorService cursorService() {
        return cursorService(new KfeMaintenanceService(participantStore()));
    }

    private static KfeDerivationCursorService cursorService(KfeMaintenanceService guard) {
        KfeDerivationCursorService service = new KfeDerivationCursorService(cursors);
        service.setMaintenanceGuard(guard);
        ProxyFactory proxy = new ProxyFactory(service);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return (KfeDerivationCursorService) proxy.getProxy();
    }

    @Test
    void caughtRequiresNewCommitFailureCannotClearSuccessfullyCommittedParent() {
        String key = "fixture-" + UUID.randomUUID();
        UUID collision = UUID.randomUUID();
        KfeMaintenanceService guard = new KfeMaintenanceService(participantStore());
        TransactionTemplate inner = new TransactionTemplate(transactions);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        try {
            new TransactionTemplate(transactions).execute(outer -> guard.executeMutation("fixture.nested-commit-parent", () -> {
                assertThatThrownBy(() -> inner.execute(status -> {
                    assertThat(cursorService(guard).nextIndex(key)).isZero();
                    jdbc.update("INSERT INTO financial.maintenance_participant_commit_failure VALUES (?)", collision);
                    jdbc.update("INSERT INTO financial.maintenance_participant_commit_failure VALUES (?)", collision);
                    return true;
                })).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
                // The caller swallowed the commit failure and its own transaction commits.
                return true;
            }));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM financial.custodial_derivation_cursors WHERE cursor_key = ?",
                    Long.class, key)).isZero();
            assertThat(admissionState("fixture.nested-commit-parent")).isEqualTo("UNCERTAIN");
            assertThat(new KfeMaintenanceService(participantStore()).status().safeToUpdate()).isFalse();
        } finally {
            jdbc.update("DELETE FROM financial.maintenance_participant_commit_failure WHERE id = ?", collision);
            jdbc.update("DELETE FROM financial.custodial_derivation_cursors WHERE cursor_key = ?", key);
        }
    }

    private static KfeBalanceService balanceService() {
        KfeBalanceService service = new KfeBalanceService(balances, new KfeHashService(),
                mock(KfeWalletRepository.class), mock(BalanceEventPublisher.class));
        service.setMaintenanceGuard(new KfeMaintenanceService(participantStore()));
        return service;
    }

    private static JdbcKfeMaintenanceStore participantStore() {
        // Same JPA manager as financial work: REQUIRES_NEW suspends that transaction correctly.
        return new JdbcKfeMaintenanceStore(jdbc, transactions);
    }

    private static String admissionState(String operation) {
        return jdbc.queryForObject("SELECT state FROM financial.kfe_maintenance_admissions WHERE operation = ? "
                + "ORDER BY admitted_at DESC LIMIT 1", String.class, operation);
    }

    private static UUID fixtureWallet() {
        UUID wallet = UUID.randomUUID();
        Long user = jdbc.queryForObject("INSERT INTO auth.users_credentials (username) VALUES (?) RETURNING id",
                Long.class, "participant-" + wallet);
        jdbc.update("INSERT INTO financial.wallets_core (id, user_id, kind, status, label, quorum_policy_hash) "
                + "VALUES (?, ?, 'INTERNAL', 'CREATING', 'disposable participant', ?)", wallet, user, "0".repeat(64));
        return wallet;
    }

    private static void deleteFixtureWallet(UUID wallet) {
        // Scoped to this test's synthetic wallet/user in the asserted disposable database.
        Long user = jdbc.queryForObject("SELECT user_id FROM financial.wallets_core WHERE id = ?", Long.class, wallet);
        jdbc.update("DELETE FROM financial.wallets_core WHERE id = ?", wallet);
        jdbc.update("DELETE FROM auth.users_credentials WHERE id = ?", user);
    }

    private static JdbcKfeMaintenanceStore store() {
        return new JdbcKfeMaintenanceStore(jdbc, new DataSourceTransactionManager(source));
    }
}
