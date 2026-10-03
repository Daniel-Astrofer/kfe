package com.kerosene.kfe.maintenance;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.kerosene.kfe.maintenance.KfeMaintenanceGuard.*;

class KfeMaintenanceServiceTest {
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService service = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);

    @AfterEach
    void clearTransaction() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @Test
    void queueEmptyDoesNotEraseCoverageOrFinancialUncertainty() {
        Instant observed = Instant.parse("2026-10-01T12:00:00Z");
        when(store.observe()).thenReturn(new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(Mode.DRAINING, "cell-update-1", 1), observed,
                Map.of("outboxQueued", 0L, "outboxInFlight", 1L, "outboxUncertain", 2L,
                        "transactionsReconciliation", 3L)));
        Status status = service.status();
        assertThat(status.schema()).isEqualTo(SCHEMA);
        assertThat(status.changeId()).isEqualTo("cell-update-1");
        assertThat(status.revision()).isEqualTo(1);
        assertThat(status.observedAt()).isEqualTo(observed);
        assertThat(status.safeToUpdate()).isFalse();
        assertThat(status.blockers()).containsEntry("outboxInFlight", 1L)
                .containsEntry("outboxUncertain", 2L).containsEntry("transactionsReconciliation", 3L);
    }

    @Test
    void anEmptyDrainedDatabaseStillCannotProveUninventoriedCoverage() {
        when(store.observe()).thenReturn(new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(Mode.DRAINING, "update", 1), Instant.now(), Map.of()));
        assertThat(service.status().safeToUpdate()).isFalse();
        assertThat(service.status().blockers()).containsEntry("mutationCoverageUnknown", 1L)
                .containsEntry("callbackCoverageUnknown", 1L).containsEntry("readSideEffectsUnknown", 1L);
    }

    @Test
    void unavailableObservationDoesNotInventAnActiveMode() {
        when(store.observe()).thenThrow(new IllegalStateException("missing table"));
        Status status = service.status();
        assertThat(status.mode()).isNull();
        assertThat(status.revision()).isEqualTo(-1);
        assertThat(status.safeToUpdate()).isFalse();
        assertThat(status.blockers()).containsEntry("observationUnavailable", 1L);
    }

    @Test
    void rejectionAndDatabaseFailureNeverRunTheMutation() {
        Runnable work = mock(Runnable.class);
        when(store.admit(anyString())).thenThrow(new MaintenanceException(503, "draining"));
        assertThatThrownBy(() -> service.executeMutation("start", () -> { work.run(); return true; }))
                .isInstanceOf(MaintenanceException.class);
        verifyNoInteractions(work);
        reset(store);
        when(store.admit(anyString())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.executeMutation("start", () -> { work.run(); return true; }))
                .isInstanceOf(MaintenanceException.class);
        verifyNoInteractions(work);
    }

    @Test
    void successfulWorkCompletesOnlyAfterOuterCommit() {
        bindTransaction();
        when(store.admit("start")).thenReturn(admission);
        assertThat(service.executeMutation("start", () -> "done")).isEqualTo("done");
        verify(store, never()).resolve(any(), anyBoolean());
        finish(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), true);
    }

    @Test
    void rollbackCannotManufactureCompletedExecution() {
        bindTransaction();
        when(store.admit("start")).thenReturn(admission);
        service.executeMutation("start", () -> "provider replied");
        finish(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void exceptionOrAnUncertainResultRemainsUncertain() {
        when(store.admit("start")).thenReturn(admission);
        assertThatThrownBy(() -> service.executeMutation("start", () -> {
            throw new IllegalStateException("provider timeout");
        })).isInstanceOf(IllegalStateException.class);
        verify(store).resolve(admission.id(), false);
        reset(store);
        when(store.admit("start")).thenReturn(admission);
        assertThat(service.executeMutation("start", () -> "UNKNOWN", "DONE"::equals)).isEqualTo("UNKNOWN");
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void nestedWorkReusesAdmissionAndCaughtNestedFailureIsSticky() {
        when(store.admit("start")).thenReturn(admission);
        service.executeMutation("start", () -> {
            try {
                service.executeMutation("nested", () -> { throw new IllegalStateException("timeout"); });
            } catch (IllegalStateException expected) { /* caller cannot erase uncertainty */ }
            return "done";
        });
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        service.executeMutation("start", () -> "next");
        verify(store, times(2)).admit(anyString());
    }

    @Test
    void completionPersistenceFailurePreservesFinancialResult() {
        when(store.admit("start")).thenReturn(admission);
        doThrow(new IllegalStateException("database unavailable")).when(store).resolve(admission.id(), true);
        assertThat(service.executeMutation("start", () -> "committed-result")).isEqualTo("committed-result");
    }

    @Test
    void authenticatedOperatorIsRequiredBeforeChangingState() {
        assertThatThrownBy(() -> service.requestDrain(new Command("update", "maintenance", 0), 0))
                .isInstanceOf(MaintenanceException.class);
        verifyNoInteractions(store);
    }

    @Test
    void committedTransitionIsReportedEvenWhenFinancialObservationFails() {
        Command command = new Command("update", "maintenance", 0);
        when(store.transition(Action.DRAIN, command, 42)).thenReturn(
                new KfeMaintenanceStore.Control(Mode.DRAINING, "update", 1));
        when(store.observe()).thenThrow(new IllegalStateException("missing status table"));
        Status status = service.requestDrain(command, 42);
        assertThat(status.mode()).isEqualTo(Mode.DRAINING);
        assertThat(status.changeId()).isEqualTo("update");
        assertThat(status.revision()).isEqualTo(1);
        assertThat(status.safeToUpdate()).isFalse();
    }

    private void bindTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    @Test
    void caughtNestedUnobservableTransactionCannotExecuteOrClearParent() {
        when(store.admit("start")).thenReturn(admission);
        Runnable work = mock(Runnable.class);
        service.executeMutation("start", () -> {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThatThrownBy(() -> service.executeMutation("nested", () -> { work.run(); return true; }))
                    .isInstanceOf(MaintenanceException.class);
            TransactionSynchronizationManager.setActualTransactionActive(false);
            return true;
        });
        verifyNoInteractions(work);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void nestedTransactionCannotTurnUnboundParentIntoCommitProof() {
        when(store.admit("start")).thenReturn(admission);
        service.executeMutation("start", () -> {
            bindTransaction();
            service.executeMutation("nested", () -> true);
            finish(TransactionSynchronization.STATUS_COMMITTED);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.setActualTransactionActive(false);
            return true;
        });
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest
    @ValueSource(ints = {TransactionSynchronization.STATUS_ROLLED_BACK, TransactionSynchronization.STATUS_UNKNOWN})
    void nestedFailureStaysStickyEvenIfBoundParentCommits(int completion) {
        when(store.admit("start")).thenReturn(admission);
        bindTransaction();
        service.executeMutation("start", () -> {
            var parent = TransactionSynchronizationManager.getSynchronizations();
            TransactionSynchronizationManager.clearSynchronization();
            bindTransaction();
            service.executeMutation("nested", () -> true);
            finish(completion);
            TransactionSynchronizationManager.clearSynchronization();
            TransactionSynchronizationManager.initSynchronization();
            parent.forEach(TransactionSynchronizationManager::registerSynchronization);
            return true;
        });
        finish(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void joinedNestedLocalCommitStillCompletesAfterRootTransaction() {
        when(store.admit("start")).thenReturn(admission);
        bindTransaction();
        service.executeMutation("start", () -> service.executeMutation("nested", () -> true));
        verify(store, never()).resolve(any(), anyBoolean());
        finish(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), true);
    }

    private void finish(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCompletion(status));
    }
}
