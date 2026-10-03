package com.kerosene.kfe.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Read-only diagnostics, never completion authority or a continuation credential. */
@Service
public class KfeMaintenanceAdmissionQuery {
    public static final String SCHEMA = "kerosene.kfe-maintenance-admissions/v1";
    private static final Instant MIN_TIME = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_TIME = Instant.parse("9999-12-31T23:59:59.999999Z");
    private static final String SELECT_ENTRIES = """
            SELECT id, operation, admitted_revision, state, admitted_at, parent_admission_id
            FROM financial.kfe_maintenance_admissions
            WHERE (state IS NULL OR state NOT IN ('COMPLETED', 'CANCELLED'))
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public KfeMaintenanceAdmissionQuery(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        transaction = new TransactionTemplate(Objects.requireNonNull(manager));
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
        transaction.setTimeout(5);
    }

    public record Entry(UUID id, String operation, long admittedRevision, String state,
                        Instant admittedAt, UUID parentAdmissionId) { }

    public record Page(String schema, Instant observedAt, KfeMaintenanceGuard.Mode mode,
                       String changeId, long revision, boolean diagnosticOnly,
                       List<Entry> entries, String nextCursor) {
        public Page {
            Objects.requireNonNull(observedAt);
            entries = List.copyOf(entries);
            if (!diagnosticOnly) { throw new IllegalArgumentException("Diagnostics cannot authorize completion."); }
        }
    }

    private record Cursor(Instant time, UUID id) { }
    private record Observation(KfeMaintenanceGuard.Mode mode, String changeId, long revision, Instant at) { }

    public Page page(int limit, String token) {
        if (limit < 1 || limit > 100) { throw new IllegalArgumentException("limit must be between 1 and 100."); }
        Cursor cursor = decode(token);
        try {
            return Objects.requireNonNull(transaction.execute(ignored -> readPage(limit, cursor)));
        } catch (RuntimeException failure) {
            // Do not expose SQL, connection strings, stored payloads or fabricated empty results.
            throw new KfeMaintenanceGuard.MaintenanceException(503, "KFE maintenance diagnostics are unavailable.");
        }
    }

    private Page readPage(int limit, Cursor cursor) {
        var observations = jdbc.query("""
                SELECT mode, change_id, revision, CURRENT_TIMESTAMP AS observed_at
                FROM financial.kfe_maintenance_control WHERE singleton_id = 1
                """, (row, index) -> new Observation(KfeMaintenanceGuard.Mode.valueOf(row.getString("mode")),
                row.getString("change_id"), row.getLong("revision"), row.getTimestamp("observed_at").toInstant()));
        if (observations.size() != 1) { throw new IllegalStateException("Missing maintenance control."); }
        Observation observation = observations.getFirst();
        String sql = SELECT_ENTRIES + (cursor == null ? "" : " AND (admitted_at, id) > (?, ?) ")
                + " ORDER BY admitted_at ASC, id ASC LIMIT ?";
        Object[] parameters = cursor == null ? new Object[]{limit + 1}
                : new Object[]{Timestamp.from(cursor.time()), cursor.id(), limit + 1};
        List<Entry> rows = jdbc.query(sql, (row, index) -> new Entry(row.getObject("id", UUID.class),
                row.getString("operation"), row.getLong("admitted_revision"), row.getString("state"),
                row.getTimestamp("admitted_at").toInstant(), row.getObject("parent_admission_id", UUID.class)), parameters);
        boolean more = rows.size() > limit;
        List<Entry> entries = more ? rows.subList(0, limit) : rows;
        String next = more ? encode(new Cursor(entries.getLast().admittedAt(), entries.getLast().id())) : null;
        return new Page(SCHEMA, observation.at(), observation.mode(), observation.changeId(),
                observation.revision(), true, entries, next);
    }

    private static String encode(Cursor cursor) {
        String payload = "1|" + cursor.time() + "|" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static Cursor decode(String token) {
        if (token == null) { return null; }
        try {
            if (token.length() > 128 || !token.matches("[A-Za-z0-9_-]+")) { throw new IllegalArgumentException(); }
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 3 || !parts[0].equals("1")) { throw new IllegalArgumentException(); }
            Cursor cursor = new Cursor(Instant.parse(parts[1]), UUID.fromString(parts[2]));
            if (cursor.time().isBefore(MIN_TIME) || cursor.time().isAfter(MAX_TIME)
                    || cursor.time().getNano() % 1000 != 0 || !encode(cursor).equals(token)) {
                throw new IllegalArgumentException();
            }
            return cursor;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid maintenance diagnostics cursor.");
        }
    }
}
