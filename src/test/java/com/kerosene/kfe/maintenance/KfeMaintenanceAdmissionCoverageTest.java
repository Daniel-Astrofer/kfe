package com.kerosene.kfe.maintenance;

import com.kerosene.common.vaultmesh.VaultMeshDayStatus;
import com.kerosene.common.vaultmesh.VaultMeshSettlementPort;
import com.kerosene.kfe.application.transaction.KfeSubmitTransactionUseCase;
import com.kerosene.kfe.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Verifies only the explicitly owned starts. Does not certify whole-runtime coverage. */
class KfeMaintenanceAdmissionCoverageTest {
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceGuard draining = new KfeMaintenanceService(store);

    @Test
    void allOwnedChannelAndPsbtStartsRejectBeforeCollaboratorEffects() throws Exception {
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
        Fixture<KfeChannelLifecycleService> channel = fixture(KfeChannelLifecycleService.class);
        inject(channel.value, draining);
        for (Runnable start : List.<Runnable>of(
                () -> channel.value.evaluateOpen(null), () -> channel.value.openChannel(null),
                () -> channel.value.retryCommit(UUID.randomUUID()),
                () -> channel.value.evaluateRebalance(null), () -> channel.value.rebalance(null),
                () -> channel.value.evaluateClose(null), () -> channel.value.closeChannel(null),
                () -> channel.value.evaluatePpm(null), () -> channel.value.adjustPpm(null))) {
            assertRejected(start);
        }
        verifyNoInteractions(channel.dependencies.values().toArray());

        Fixture<KfePsbtWorkflowService> psbt = fixture(KfePsbtWorkflowService.class);
        inject(psbt.value, draining);
        assertRejected(() -> psbt.value.create(42L, UUID.randomUUID(), null, null, 0, 1, null, List.of()));
        assertRejected(() -> psbt.value.attachSignedPsbt(42L, UUID.randomUUID(), null));
        assertRejected(() -> psbt.value.broadcast(42L, UUID.randomUUID()));
        verifyNoInteractions(psbt.dependencies.values().toArray());
    }

    @Test
    void compatibilityConstructionCannotBecomeAnUnguardedProductionMutation() throws Exception {
        Fixture<KfeChannelCapacityWorker> capacity = fixture(KfeChannelCapacityWorker.class);
        assertRejected(() -> capacity.value.executeJob(UUID.randomUUID()));
        verifyNoInteractions(capacity.dependencies.values().toArray());
        Fixture<KfeChannelRebalanceWorker> rebalance = fixture(KfeChannelRebalanceWorker.class);
        assertRejected(() -> rebalance.value.executeJob(UUID.randomUUID()));
        verifyNoInteractions(rebalance.dependencies.values().toArray());
        Fixture<KfePsbtWorkflowService> psbt = fixture(KfePsbtWorkflowService.class);
        assertRejected(() -> psbt.value.broadcast(42L, UUID.randomUUID()));
        verifyNoInteractions(psbt.dependencies.values().toArray());
        Fixture<KfeExecutionOutboxService> outbox = fixture(KfeExecutionOutboxService.class);
        assertRejected(() -> outbox.value.claimDue("worker"));
        assertRejected(() -> outbox.value.claimImmediate(UUID.randomUUID(), "worker"));
        verifyNoInteractions(outbox.dependencies.values().toArray());
    }

    @Test
    void pausedChannelBatchesPreservePendingJobs() throws Exception {
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
        Fixture<KfeChannelCapacityWorker> capacity = fixture(KfeChannelCapacityWorker.class);
        inject(capacity.value, draining);
        KfeChannelCapacityQueueService capacityQueue = capacity.dependency(KfeChannelCapacityQueueService.class);
        var capacityJob = new com.kerosene.kfe.model.KfeChannelCapacityJobEntity();
        when(capacity.dependency(com.kerosene.kfe.rail.LightningChannelGateway.class).isLive()).thenReturn(true);
        when(capacityQueue.pending(1)).thenReturn(List.of(capacityJob));
        assertThat(capacity.value.processBatch(1)).isZero();
        verify(capacityQueue, never()).markInProgress(any(), any());
        verify(capacityQueue, never()).fail(any(), any());

        Fixture<KfeChannelRebalanceWorker> rebalance = fixture(KfeChannelRebalanceWorker.class);
        inject(rebalance.value, draining);
        KfeChannelRebalanceQueueService rebalanceQueue = rebalance.dependency(KfeChannelRebalanceQueueService.class);
        var rebalanceJob = new com.kerosene.kfe.model.KfeChannelRebalanceJobEntity();
        when(rebalance.dependency(com.kerosene.kfe.rail.LightningChannelGateway.class).isLive()).thenReturn(true);
        when(rebalanceQueue.pending(1)).thenReturn(List.of(rebalanceJob));
        assertThat(rebalance.value.processBatch(1)).isZero();
        verify(rebalanceQueue, never()).markInProgress(any(), any());
        verify(rebalanceQueue, never()).fail(any(), any());
    }

    @Test
    void vaultRotationCanReadStatusButCannotVoteAdvanceOrReshareWhileDraining() throws Exception {
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
        Fixture<KfeVaultMeshDayRotationWorker> rotation = fixture(KfeVaultMeshDayRotationWorker.class);
        inject(rotation.value, draining);
        VaultMeshSettlementPort port = rotation.dependency(VaultMeshSettlementPort.class);
        when(port.getDayStatus()).thenReturn(VaultMeshDayStatus.stale("2000-01-01", "2000-01-02"));
        assertRejected(rotation.value::rotateIfNeeded);
        verify(port).getDayStatus();
        verify(port, never()).voteDay(any(), any());
        verify(port, never()).advanceDay();
        verify(port, never()).triggerReshare(any());
    }

    @Test
    void allOwnedConsumersHaveMandatorySpringGuardInjection() throws Exception {
        for (Class<?> type : List.of(KfeSubmitTransactionUseCase.class, KfeExecutionOutboxService.class,
                KfeChannelLifecycleService.class, KfeChannelCapacityWorker.class, KfeChannelRebalanceWorker.class,
                KfePsbtWorkflowService.class, KfeVaultMeshDayRotationWorker.class)) {
            assertThat(type.getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class)
                    .getAnnotation(Autowired.class).required()).as(type.getSimpleName()).isTrue();
        }
    }

    private void inject(Object consumer, KfeMaintenanceGuard guard) throws Exception {
        consumer.getClass().getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class).invoke(consumer, guard);
    }

    private void assertRejected(Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(KfeMaintenanceGuard.MaintenanceException.class,
                error -> assertThat(error.httpStatus()).isEqualTo(503));
    }

    private <T> Fixture<T> fixture(Class<T> type) throws Exception {
        Constructor<?> constructor = type.getConstructors()[0];
        Map<Class<?>, Object> dependencies = new LinkedHashMap<>();
        Class<?>[] parameters = constructor.getParameterTypes();
        Object[] arguments = new Object[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Class<?> parameter = parameters[i];
            if (parameter == boolean.class) { arguments[i] = false; }
            else if (parameter == int.class) { arguments[i] = 80; }
            else if (parameter == long.class) { arguments[i] = 600L; }
            else if (parameter == String.class) { arguments[i] = "test"; }
            else if (parameter == Clock.class) { arguments[i] = Clock.systemUTC(); }
            else { arguments[i] = dependencies.computeIfAbsent(parameter, key -> mock(key)); }
        }
        return new Fixture<>(type.cast(constructor.newInstance(arguments)), dependencies);
    }

    private record Fixture<T>(T value, Map<Class<?>, Object> dependencies) {
        <D> D dependency(Class<D> type) { return type.cast(dependencies.get(type)); }
    }
}
