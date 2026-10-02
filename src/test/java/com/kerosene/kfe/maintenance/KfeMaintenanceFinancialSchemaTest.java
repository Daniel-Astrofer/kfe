package com.kerosene.kfe.maintenance;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.assertj.core.api.Assertions.*;
import static com.kerosene.kfe.maintenance.KfeMaintenanceGuard.*;

/** Real complete migration chain in a separately created, exclusive disposable database. */
@EnabledIfEnvironmentVariable(named = "KFE_MAINTENANCE_FULL_SCHEMA_DISPOSABLE", matches = "true")
@Execution(ExecutionMode.SAME_THREAD)
class KfeMaintenanceFinancialSchemaTest {
    private static DriverManagerDataSource source;
    private static JdbcTemplate jdbc;
    private static Flyway flyway;

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

    private static JdbcKfeMaintenanceStore store() {
        return new JdbcKfeMaintenanceStore(jdbc, new DataSourceTransactionManager(source));
    }
}
