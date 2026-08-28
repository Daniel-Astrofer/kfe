package com.kerosene.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DestructiveMigrationGuardrailsTest {

    private static final Path MIGRATION = Path.of("").toAbsolutePath()
            .resolve("src/main/resources/db/migration/V23__drop_legacy_financial_tables.sql");

    @Test
    void destructiveLegacyFinancialDropIsMarkedAsDevTestResetOnly() throws IOException {
        String migration = Files.readString(MIGRATION);

        assertTrue(migration.startsWith("-- KEROSENE DEV/TEST RESET MIGRATION"));
        assertTrue(migration.contains("dev/test only"));
        assertTrue(migration.contains("Do not run against production data"));
    }
}
