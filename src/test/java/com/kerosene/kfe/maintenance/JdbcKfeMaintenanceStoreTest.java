package com.kerosene.kfe.maintenance;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.kerosene.kfe.maintenance.KfeMaintenanceGuard.*;

class JdbcKfeMaintenanceStoreTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final JdbcKfeMaintenanceStore store = new JdbcKfeMaintenanceStore(jdbc, manager);

    @Test
    void missingSingletonRollsBackRatherThanDefaultingToActive() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(List.of());
        assertThatThrownBy(() -> store.admit("payment.submit")).isInstanceOf(IllegalStateException.class);
        verify(manager).rollback(any());
        verify(manager, never()).commit(any());
        ArgumentCaptor<TransactionDefinition> definition = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager).getTransaction(definition.capture());
        assertThat(definition.getValue().getPropagationBehavior())
                .isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertThat(definition.getValue().getIsolationLevel())
                .isEqualTo(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Test
    void drainingNeverInsertsAnAdmission() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(new KfeMaintenanceStore.Control(Mode.DRAINING, "update", 1)));
        assertThatThrownBy(() -> store.admit("outbox.claim-immediate")).isInstanceOf(MaintenanceException.class);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(manager).rollback(any());
    }

    @Test
    void uncertaintyIsStickyAndNeverGivenAnExpiry() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        UUID id = UUID.randomUUID();
        store.resolve(id, false);
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), eq("UNCERTAIN"), isNull(), eq(id));
        assertThat(sql.getValue()).contains("state = 'IN_FLIGHT'").doesNotContain("lease", "expires");
        verify(manager).commit(any());
    }
}
