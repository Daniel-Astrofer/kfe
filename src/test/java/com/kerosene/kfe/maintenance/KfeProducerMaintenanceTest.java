package com.kerosene.kfe.maintenance;

import com.kerosene.kfe.repository.*;
import com.kerosene.kfe.service.*;
import com.kerosene.kfe.rail.ChannelsMeshInjectGateway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real admission service, synthetic dependencies; no provider or financial execution. */
class KfeProducerMaintenanceTest {
    private enum Root { OPEN, CLOSE, CAPACITY_START, CAPACITY_COMPLETE, CAPACITY_FAIL,
        REBALANCE, REBALANCE_START, REBALANCE_COMPLETE, REBALANCE_FAIL, RETRY, ORPHANS, RETENTION }
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final KfeChannelCapacityJobRepository capacityRows = mock(KfeChannelCapacityJobRepository.class);
    private final KfeChannelRebalanceJobRepository rebalanceRows = mock(KfeChannelRebalanceJobRepository.class);
    private final KfeChannelOperationDecisionRepository decisions = mock(KfeChannelOperationDecisionRepository.class);
    private final KfeUserStatementRepository statements = mock(KfeUserStatementRepository.class);
    private final KfeSystemWalletService wallets = mock(KfeSystemWalletService.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final ChannelsMeshInjectGateway mesh = mock(ChannelsMeshInjectGateway.class);
    private final KfeChannelLifecycleService lifecycle = mock(KfeChannelLifecycleService.class);
    private KfeChannelCapacityQueueService capacity;
    private KfeChannelRebalanceQueueService rebalance;
    private KfeChannelMeshInjectReconciler reconciler;
    private KfeStatementRetentionService retention;

    @BeforeEach void construct() {
        capacity = new KfeChannelCapacityQueueService(capacityRows, wallets, balances, audit);
        rebalance = new KfeChannelRebalanceQueueService(rebalanceRows, wallets, balances, audit);
        reconciler = new KfeChannelMeshInjectReconciler(decisions, mesh, lifecycle, true, 20, 10);
        retention = new KfeStatementRetentionService(statements);
        capacity.setMaintenanceGuard(guard); rebalance.setMaintenanceGuard(guard);
        reconciler.setMaintenanceGuard(guard); retention.setMaintenanceGuard(guard);
        when(store.admit(anyString())).thenReturn(admission);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void drainingRejectsBeforeRepositoryOrProviderEffects(Root root) {
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain"));
        rejected(root); noEffects();
    }

    @ParameterizedTest @EnumSource(Root.class)
    void missingInjectionFailsClosed(Root root) {
        capacity.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        rebalance.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        reconciler.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        retention.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        rejected(root); noEffects(); verify(store, never()).admit(anyString());
    }

    @ParameterizedTest @EnumSource(Root.class)
    void admissionDatabaseFailureCannotStartWork(Root root) {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("synthetic database outage"));
        rejected(root); noEffects();
    }

    @Test void admittedParentCanFinishLocalQueueAndRetentionWritesDuringDrain() {
        guard.executeMutation("synthetic.parent", () -> {
            when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain"));
            capacity.complete(UUID.randomUUID(), "synthetic-provider-ref");
            rebalance.fail(UUID.randomUUID(), "synthetic failure");
            retention.purgeExpiredStatements();
            return true;
        });
        verify(store, times(1)).admit(anyString());
        verify(capacityRows).findById(any()); verify(rebalanceRows).findById(any());
        verify(statements).deleteByExpiresAtBefore(any());
        verify(store).resolve(admission.id(), true);
        rejected(Root.RETENTION);
    }

    @Test void admittedParentIsNotProvenanceForTtlOrphanReleaseDuringDrain() {
        when(store.observe()).thenReturn(new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.DRAINING, "synthetic.change", 1),
                Instant.now(), Map.of()));
        assertThatThrownBy(() -> guard.executeMutation("synthetic.parent", () -> reconciler.releaseOrphanedReserves(5)))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects(); verify(store).resolve(admission.id(), false);
    }

    @Test void missingIntentDoesNotSilentlyClearAnOrphanAfterTtl() {
        when(store.observe()).thenReturn(new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.ACTIVE, null, 0), Instant.now(), Map.of()));
        var orphan = new com.kerosene.kfe.model.KfeChannelOperationDecisionEntity();
        orphan.setMeshInjectPhase(KfeChannelLifecycleService.PHASE_RESERVED);
        when(decisions.findOrphanedReserves(any(), any())).thenReturn(List.of(orphan));
        assertThat(reconciler.releaseOrphanedReserves(5)).isZero();
        assertThat(orphan.getMeshInjectPhase()).isEqualTo(KfeChannelLifecycleService.PHASE_RESERVED);
        verify(decisions, never()).save(any()); verifyNoInteractions(mesh);
        verify(store).resolve(admission.id(), false);
    }

    private void rejected(Root root) {
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
    }
    private void noEffects() { verifyNoInteractions(capacityRows, rebalanceRows, decisions, statements, wallets, balances, audit, mesh, lifecycle); }
    private void invoke(Root root) {
        UUID id = UUID.randomUUID();
        switch (root) {
            case OPEN -> capacity.enqueueOpenIfAbsent("synthetic-peer", 10, 1, 2, "synthetic", id);
            case CLOSE -> capacity.enqueueCloseIfAbsent("synthetic-point", "synthetic-peer", 1, "synthetic", id);
            case CAPACITY_START -> capacity.markInProgress(id, "synthetic-ref");
            case CAPACITY_COMPLETE -> capacity.complete(id, "synthetic-ref");
            case CAPACITY_FAIL -> capacity.fail(id, "synthetic-error");
            case REBALANCE -> rebalance.enqueueIfAbsent(id, "synthetic-point", "synthetic-peer", 1, 2);
            case REBALANCE_START -> rebalance.markInProgress(id, "synthetic-ref");
            case REBALANCE_COMPLETE -> rebalance.complete(id, "synthetic-ref");
            case REBALANCE_FAIL -> rebalance.fail(id, "synthetic-error");
            case RETRY -> reconciler.retryPendingCommits(5);
            case ORPHANS -> reconciler.releaseOrphanedReserves(5);
            case RETENTION -> retention.purgeExpiredStatements();
        }
    }
}
