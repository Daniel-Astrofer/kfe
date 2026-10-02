package com.kerosene.kfe.service;

import org.junit.jupiter.api.Test;
import com.kerosene.kfe.model.KfeExecutionOutboxEntity;
import com.kerosene.kfe.repository.KfeExecutionOutboxRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeExecutionOutboxServiceTest {

    private final KfeExecutionOutboxRepository repository = mock(KfeExecutionOutboxRepository.class);
    private final KfeExecutionOutboxService service = new KfeExecutionOutboxService(repository);

    private final com.kerosene.kfe.maintenance.KfeMaintenanceStore maintenanceStore =
            mock(com.kerosene.kfe.maintenance.KfeMaintenanceStore.class);

    @org.junit.jupiter.api.BeforeEach
    void admitTestWork() {
        when(maintenanceStore.admit(anyString())).thenAnswer(ignored ->
                new com.kerosene.kfe.maintenance.KfeMaintenanceStore.Admission(UUID.randomUUID(), 0));
        service.setMaintenanceGuard(new com.kerosene.kfe.maintenance.KfeMaintenanceService(maintenanceStore));
    }

    @Test
    void drainingRejectsBothClaimPathsBeforeTouchingTheQueue() {
        when(maintenanceStore.admit(anyString())).thenThrow(
                new com.kerosene.kfe.maintenance.KfeMaintenanceGuard.MaintenanceException(503, "draining"));
        org.junit.jupiter.api.Assertions.assertThrows(
                com.kerosene.kfe.maintenance.KfeMaintenanceGuard.MaintenanceException.class,
                () -> service.claimDue("worker"));
        org.junit.jupiter.api.Assertions.assertThrows(
                com.kerosene.kfe.maintenance.KfeMaintenanceGuard.MaintenanceException.class,
                () -> service.claimImmediate(UUID.randomUUID(), "worker"));
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void standaloneHeartbeatRequiresAdmissionAndRemainsUncertain() {
        UUID id = UUID.randomUUID();
        UUID token = UUID.randomUUID();
        when(repository.heartbeat(eq(id), eq(token), any(), any())).thenReturn(1);
        assertThat(service.heartbeat(new KfeExecutionOutboxService.ExecutionClaim(id, token))).isTrue();
        verify(maintenanceStore).admit("outbox.heartbeat");
        verify(maintenanceStore).resolve(any(UUID.class), eq(false));
    }

    @Test
    void claimsDueOutboxItemsWithNormalizedWorkerId() {
        KfeExecutionOutboxEntity candidate = new KfeExecutionOutboxEntity();
        when(repository.findTop100ClaimCandidates(anyCollection(), anyCollection(), any()))
                .thenReturn(List.of(candidate));
        when(repository.claimDue(
                eq(candidate.getId()), anyCollection(), anyCollection(), any(),
                eq("kfe-worker"), any(UUID.class), any()))
                .thenReturn(1);

        List<KfeExecutionOutboxService.ExecutionClaim> claimed = service.claimDue("KFE-WORKER");

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().outboxId()).isEqualTo(candidate.getId());
        assertThat(claimed.getFirst().claimToken()).isNotNull();
        verify(repository).claimDue(
                eq(candidate.getId()), anyCollection(), anyCollection(), any(),
                eq("kfe-worker"), any(UUID.class), any());
        verify(maintenanceStore).resolve(any(UUID.class), eq(false));
    }

    @Test
    void emptyClaimPathsCanCompleteWithoutInventingExecutionProvenance() {
        when(repository.findTop100ClaimCandidates(anyCollection(), anyCollection(), any()))
                .thenReturn(List.of());
        assertThat(service.claimDue("worker")).isEmpty();
        assertThat(service.claimImmediate(UUID.randomUUID(), "worker")).isEmpty();
        org.mockito.Mockito.verify(maintenanceStore, org.mockito.Mockito.times(2))
                .resolve(any(UUID.class), eq(true));
    }
}
