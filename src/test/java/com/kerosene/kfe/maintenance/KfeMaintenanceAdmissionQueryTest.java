package com.kerosene.kfe.maintenance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.Mockito.*;

@SuppressWarnings({"unchecked", "rawtypes"})
class KfeMaintenanceAdmissionQueryTest {
    final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final KfeMaintenanceAdmissionQuery query = new KfeMaintenanceAdmissionQuery(jdbc, manager);
    final Instant time = Instant.parse("2026-10-03T12:00:00.123456Z");
    final UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
    final KfeMaintenanceAdmissionQuery.Entry entry = new KfeMaintenanceAdmissionQuery.Entry(
            id, "synthetic.operation", 7, "UNCERTAIN", time, null);

    @BeforeEach
    void setup() throws Exception {
        lenient().when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        ResultSet control = mock(ResultSet.class);
        lenient().when(control.getString("mode")).thenReturn("DRAINING");
        lenient().when(control.getString("change_id")).thenReturn("change-1");
        lenient().when(control.getLong("revision")).thenReturn(8L);
        lenient().when(control.getTimestamp("observed_at")).thenReturn(Timestamp.from(time));
        lenient().when(jdbc.query(anyString(), any(RowMapper.class))).thenAnswer(call ->
                List.of(((RowMapper) call.getArgument(1)).mapRow(control, 0)));
        lenient().when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(entry));
    }

    @ParameterizedTest
    @ValueSource(ints = {-2147483648, -1, 0, 101, 2147483647})
    void invalidLimitRejectsBeforeAnyDatabaseWork(int limit) {
        assertThatIllegalArgumentException().isThrownBy(() -> query.page(limit, null));
        verifyNoInteractions(jdbc, manager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "bad=", "../x", "a", "!!!!", "SELECT * FROM secrets"})
    void malformedCursorRejectsBeforeDatabase(String token) {
        assertThatIllegalArgumentException().isThrownBy(() -> query.page(50, token));
        verifyNoInteractions(jdbc, manager);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2|2026-10-03T12:00:00Z|00000000-0000-0000-0000-000000000001",
            "1|2026-10-03T12:00:00Z|1-1-1-1-1",
            "1|2026-10-03T12:00:00.123456789Z|00000000-0000-0000-0000-000000000001",
            "1|2026-10-03T12:00:00+00:00|00000000-0000-0000-0000-000000000001",
            "1|0000-01-01T00:00:00Z|00000000-0000-0000-0000-000000000001",
            "1|+10000-01-01T00:00:00Z|00000000-0000-0000-0000-000000000001",
            "1|2026-10-03T12:00:00Z|00000000-0000-0000-0000-000000000001|extra"})
    void invalidDecodedCursorCannotReachSql(String payload) {
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        assertThatIllegalArgumentException().isThrownBy(() -> query.page(50, token));
        verifyNoInteractions(jdbc, manager);
    }

    @Test
    void snapshotIsReadOnlyBoundedIndependentAndNeverAdmission() {
        var page = query.page(100, null);
        assertThat(page.schema()).isEqualTo(KfeMaintenanceAdmissionQuery.SCHEMA);
        assertThat(page.mode()).isEqualTo(KfeMaintenanceGuard.Mode.DRAINING);
        assertThat(page.revision()).isEqualTo(8);
        assertThat(page.diagnosticOnly()).isTrue();
        assertThat(page.entries()).containsExactly(entry);
        assertThat(page.nextCursor()).isNull();
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().isReadOnly()).isTrue();
        assertThat(definition.getValue().getTimeout()).isEqualTo(5);
        assertThat(definition.getValue().getIsolationLevel()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(definition.getValue().getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(manager).commit(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).query(sql.capture(), any(RowMapper.class), aryEq(new Object[]{101}));
        assertThat(sql.getValue()).contains("NOT IN ('COMPLETED', 'CANCELLED')", "ORDER BY admitted_at ASC, id ASC LIMIT ?")
                .doesNotContain("FOR UPDATE", "OFFSET", "DELETE", "INSERT");
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> page.entries().clear());
    }

    @Test
    void paginationUsesLastReturnedTimestampAndIdNotOffsetOrExtraRow() {
        var second = new KfeMaintenanceAdmissionQuery.Entry(UUID.randomUUID(), "child", 7,
                "WAITING", time.plusSeconds(1), id);
        var extra = new KfeMaintenanceAdmissionQuery.Entry(UUID.randomUUID(), "extra", 7,
                "READY", time.plusSeconds(2), id);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(entry, second, extra));
        var first = query.page(2, null);
        assertThat(first.entries()).containsExactly(entry, second);
        String payload = new String(Base64.getUrlDecoder().decode(first.nextCursor()), StandardCharsets.UTF_8);
        assertThat(payload).isEqualTo("1|" + second.admittedAt() + "|" + second.id());
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of(extra));
        var next = query.page(2, first.nextCursor());
        assertThat(next.entries()).containsExactly(extra);
        assertThat(next.nextCursor()).isNull();
        verify(jdbc).query(contains("AND (admitted_at, id) > (?, ?)"), any(RowMapper.class),
                aryEq(new Object[]{Timestamp.from(second.admittedAt()), second.id(), 3}));
    }

    @Test
    void missingControlIsUnavailableNotEmptyOrActive() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        assertUnavailable();
        verify(manager).rollback(any());
        verify(jdbc, never()).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void databaseErrorAndCommitErrorAreSanitizedNotSuccessfulEmptyPages() {
        doThrow(new IllegalStateException("jdbc:secret sql password"))
                .when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
        assertUnavailable();
        verify(manager).rollback(any());
    }

    @Test
    void observationCommitFailureCannotReturnSuccess() {
        doThrow(new IllegalStateException("private connection details")).when(manager).commit(any());
        assertUnavailable();
    }

    @Test
    void oversizedCursorCannotReachDatabase() {
        assertThatIllegalArgumentException().isThrownBy(() -> query.page(50, "x".repeat(129)));
        verifyNoInteractions(jdbc, manager);
    }

    private void assertUnavailable() {
        assertThatThrownBy(() -> query.page(50, null)).isInstanceOfSatisfying(
                KfeMaintenanceGuard.MaintenanceException.class, failure -> {
                    assertThat(failure.httpStatus()).isEqualTo(503);
                    assertThat(failure.getMessage()).isEqualTo("KFE maintenance diagnostics are unavailable.");
                    assertThat(failure.getCause()).isNull();
                });
    }
}
