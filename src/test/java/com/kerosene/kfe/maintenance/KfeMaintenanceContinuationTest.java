package com.kerosene.kfe.maintenance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeMaintenanceContinuationTest {
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission parent = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 4);
    private final KfeMaintenanceStore.Admission child = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 4);
    private final List<Runnable> queued = new ArrayList<>();
    private final Executor executor = queued::add;
    private final Runnable work = mock(Runnable.class);

    @BeforeEach
    void activeStore() {
        when(store.admit("payment")).thenReturn(parent);
        when(store.captureContinuation(eq(parent.id()), eq("delivery"), anyBoolean())).thenReturn(child);
        when(store.claimContinuation(child.id())).thenReturn(child);
    }

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void persistedBeforeEnqueueThenNestedWorkReusesChildNotAnActiveRoot() {
        guard.executeMutation("payment", () -> {
            guard.scheduleContinuation("delivery", executor, () ->
                    guard.executeMutation("nested", () -> { work.run(); return true; }));
            return true;
        });
        var order = inOrder(store);
        order.verify(store).admit("payment");
        order.verify(store).captureContinuation(parent.id(), "delivery", false);
        order.verify(store).resolve(parent.id(), true);
        verifyNoInteractions(work);
        queued.getFirst().run();
        verify(work).run();
        verify(store).claimContinuation(child.id());
        verify(store).resolve(child.id(), true);
        verify(store, times(1)).admit(anyString());
    }

    @Test
    void onlyProvenCommitReleasesAndEnqueuesTheDurableChild() {
        bindTransaction();
        guard.executeMutation("payment", () -> {
            guard.scheduleContinuation("delivery", executor, work);
            return true;
        });
        assertThat(queued).isEmpty();
        verify(store).captureContinuation(parent.id(), "delivery", true);
        finish(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).releaseContinuation(child.id(), true);
        assertThat(queued).hasSize(1);
        queued.getFirst().run();
        verify(work).run();
        verify(store).resolve(child.id(), true);
    }

    @Test
    void rollbackCancelsUnstartedChildButUnknownCompletionRemainsWaiting() {
        bindTransaction();
        guard.executeMutation("payment", () -> {
            guard.scheduleContinuation("delivery", executor, work);
            return true;
        });
        finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(store).releaseContinuation(child.id(), false);
        assertThat(queued).isEmpty();
        verifyNoInteractions(work);
        reset(store);
        activeStore();
        bindTransaction();
        guard.executeMutation("payment", () -> {
            guard.scheduleContinuation("delivery", executor, work);
            return true;
        });
        finish(TransactionSynchronization.STATUS_UNKNOWN);
        verify(store, never()).releaseContinuation(any(), anyBoolean());
        assertThat(queued).isEmpty();
    }

    @Test
    void absencePersistenceFailureOrDuplicateClaimNeverRunsWork() {
        assertThatThrownBy(() -> guard.scheduleContinuation("delivery", executor, work))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verify(store, never()).captureContinuation(any(), anyString(), anyBoolean());
        when(store.captureContinuation(any(), anyString(), anyBoolean())).thenThrow(new IllegalStateException("db"));
        assertThatThrownBy(() -> guard.executeMutation("payment", () -> {
            guard.scheduleContinuation("delivery", executor, work); return true;
        })).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verify(store).resolve(parent.id(), false);
        assertThat(queued).isEmpty();
        reset(store);
        activeStore();
        guard.executeMutation("payment", () -> {
            guard.scheduleContinuation("delivery", executor, work); return true;
        });
        when(store.claimContinuation(child.id())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(409, "replayed"));
        assertThatThrownBy(() -> queued.getFirst().run()).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(work);
        verify(store, never()).resolve(child.id(), true);
    }

    @Test
    void executorRejectionAndFailureDoNotManufactureCompletion() {
        guard.executeMutation("payment", () -> {
            guard.scheduleContinuation("delivery", ignored -> { throw new java.util.concurrent.RejectedExecutionException(); }, work);
            return true;
        });
        verify(store, never()).resolve(eq(child.id()), anyBoolean());
        verifyNoInteractions(work);
        guard.executeMutation("payment", () -> {
            guard.scheduleContinuation("delivery", executor, () -> { throw new IllegalStateException("timeout"); });
            return true;
        });
        assertThatThrownBy(() -> queued.getFirst().run()).isInstanceOf(IllegalStateException.class);
        verify(store).resolve(child.id(), false);
    }

    @Test
    void completedTransactionCannotLeakIntoADirectExecutor() {
        bindTransaction();
        guard.executeMutation("payment", () -> {
            guard.scheduleContinuation("delivery", Runnable::run, work); return true;
        });
        TransactionSynchronizationManager.getSynchronizations().forEach(sync ->
                sync.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
        verify(store).releaseContinuation(child.id(), true);
        verify(store, never()).claimContinuation(any());
        verifyNoInteractions(work);
    }

    private void bindTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void finish(int status) {
        var synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.forEach(sync -> sync.afterCompletion(status));
        TransactionSynchronizationManager.clearSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }
}
