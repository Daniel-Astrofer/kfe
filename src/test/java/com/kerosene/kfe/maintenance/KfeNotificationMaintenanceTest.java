package com.kerosene.kfe.maintenance;

import com.kerosene.common.financial.FinancialNotificationPort;
import com.kerosene.kfe.integration.KfeRemoteFinancialNotificationClient;
import com.kerosene.kfe.model.KfeFinancialNotificationOutboxEntity;
import com.kerosene.kfe.repository.KfeFinancialNotificationOutboxRepository;
import com.kerosene.kfe.service.KfeFinancialMetrics;
import com.kerosene.kfe.service.KfeFinancialNotificationOutboxService;
import com.kerosene.kfe.service.KfeNotificationOutboxProcessor;
import com.kerosene.kfe.service.KfeNotificationOutboxWorker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfeNotificationMaintenanceTest {
    private enum Root { CLAIM, RETRY, DEAD_LETTER, MARK_DELIVERED, PROCESS, PROCESS_DELIVERABLE, WORKER }

    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final KfeFinancialNotificationOutboxRepository repository =
            mock(KfeFinancialNotificationOutboxRepository.class);
    private final FinancialNotificationPort port = mock(FinancialNotificationPort.class);
    private final KfeFinancialMetrics metrics = mock(KfeFinancialMetrics.class);
    private final KfeFinancialNotificationOutboxEntity item = notification();
    private KfeFinancialNotificationOutboxService outbox;
    private KfeNotificationOutboxProcessor processor;
    private KfeNotificationOutboxWorker worker;

    @BeforeEach
    void explicitlyActiveStoreAndRealGuard() {
        constructServices();
        outbox.setMaintenanceGuard(guard);
        processor.setMaintenanceGuard(guard);
        worker.setMaintenanceGuard(guard);
        when(store.admit(anyString())).thenReturn(admission);
    }

    @AfterEach
    void cleanTransactionContext() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void drainRejectsEveryIndependentRootBeforeAnyRepositoryOrRemoteEffect(Root root) {
        draining();
        assertRejected(() -> invoke(root));
        verifyNoInteractions(repository, port, metrics);
        verify(store, never()).resolve(any(), anyBoolean());
        assertThat(item.getStatus()).isEqualTo("PENDING");
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void constructorDefaultsRemainUnavailable(Root root) {
        constructServices(); // Deliberately omit injection on all three production objects.
        assertRejected(() -> invoke(root));
        verifyNoInteractions(repository, port, metrics, store);
    }

    @Test
    void springRequiresTheGuardForEveryService() throws Exception {
        for (Class<?> service : List.of(KfeFinancialNotificationOutboxService.class,
                KfeNotificationOutboxProcessor.class, KfeNotificationOutboxWorker.class)) {
            assertThat(service.getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class)
                    .getAnnotation(Autowired.class).required()).isTrue();
        }
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void storeOutageFailsClosedBeforeEffects(Root root) {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("synthetic store outage"));
        assertRejected(() -> invoke(root));
        verifyNoInteractions(repository, port, metrics);
    }

    @Test
    void claimPreservesDueStatusesWorkerNormalizationAndFiveMinuteLeaseButStaysUncertain() {
        due(item);
        assertThat(outbox.claimDue("  UNIT-WORKER  ")).containsExactly(item);
        var now = org.mockito.ArgumentCaptor.forClass(Instant.class);
        var until = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repository).claimDue(eq(item.getId()), eq(List.of("PENDING", "FAILED_RETRYABLE")),
                now.capture(), eq("unit-worker"), until.capture());
        assertThat(Duration.between(now.getValue(), until.getValue())).isEqualTo(Duration.ofMinutes(5));
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(admission.id(), true);
    }

    @Test
    void successfulClaimWithMissingReloadCannotBecomeCompletedEmptyBatch() {
        due(item);
        when(repository.findById(item.getId())).thenReturn(Optional.empty());
        assertThat(outbox.claimDue("worker")).isEmpty();
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(admission.id(), true);
    }

    @Test
    void emptyWorkerTickCompletesWithoutInventingDeliveryOrContinuations() {
        when(repository.findTop100ClaimCandidates(anyCollection(), any())).thenReturn(List.of());
        worker.drain();
        verify(store).admit("notification.worker");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), true);
        verifyNoInteractions(port, metrics);
        verify(repository, never()).claimDue(any(), anyCollection(), any(), anyString(), any());
        verify(store, never()).captureContinuation(any(), anyString(), anyBoolean());
    }

    @Test
    void lostClaimRaceWithoutAnyClaimMayCompleteLocally() {
        due(item);
        when(repository.claimDue(eq(item.getId()), anyCollection(), any(), anyString(), any())).thenReturn(0);
        assertThat(outbox.claimDue("worker")).isEmpty();
        verify(repository, never()).findById(any());
        verify(store).resolve(admission.id(), true);
    }

    @Test
    void admittedWorkerFinishesEntireSynchronousBatchDuringDrainAndNextTickCannotClaim() {
        KfeFinancialNotificationOutboxEntity second = notification();
        due(item, second);
        when(repository.claimDue(eq(item.getId()), anyCollection(), any(), anyString(), any()))
                .thenAnswer(invocation -> { draining(); return 1; });
        doAnswer(invocation -> {
            // Root must still be held through actual delivery and each local status write.
            verify(store, never()).resolve(any(), anyBoolean());
            return null;
        }).when(port).notifyPaymentInitiated(anyLong(), any(), any(), anyString(), anyLong());
        doAnswer(invocation -> {
            verify(store, never()).resolve(any(), anyBoolean());
            return 1;
        }).when(repository).markDelivered(any(), any());
        worker.drain();
        verify(port, times(2)).notifyPaymentInitiated(anyLong(), any(), any(), anyString(), anyLong());
        verify(repository).markDelivered(eq(item.getId()), any());
        verify(repository).markDelivered(eq(second.getId()), any());
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        assertRejected(worker::drain);
        verify(repository, times(1)).findTop100ClaimCandidates(anyCollection(), any());
        verify(store, never()).captureContinuation(any(), anyString(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(value = Root.class, names = {"PROCESS", "PROCESS_DELIVERABLE"})
    void oldClaimExpiryAndTransactionIdentityCannotAuthorizeIndependentDeliveryDuringDrain(Root root) {
        item.setStatus("CLAIMED");
        item.setClaimedUntil(Instant.now().minusSeconds(600));
        item.setClaimedBy("previous-process");
        // transactionId is populated by notification(); it is not maintenance provenance.
        draining();
        assertRejected(() -> invoke(root));
        verifyNoInteractions(repository, port, metrics);
        assertThat(item.getStatus()).isEqualTo("CLAIMED");
    }

    @Test
    void alreadyAdmittedParentAllowsNestedFailureStatusDuringDrainButDoesNotCertifyDelivery() {
        guard.executeMutation("test.parent", () -> {
            draining();
            outbox.markRetryableFailure(item.getId(), 2, "synthetic failure");
            return true;
        });
        verify(repository).markRetryableFailure(eq(item.getId()), eq("FAILED_RETRYABLE"), any(),
                eq("synthetic failure"));
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        assertRejected(() -> outbox.markDeadLetter(item.getId(), "later independent attempt"));
        verify(repository, never()).markFinalFailure(any(), anyString(), anyString());
    }

    @Test
    void processorCatchPreservesRetryBehaviorButNeverCertifiesCompletion() {
        failPort();
        Instant before = Instant.now();
        processor.process(item);
        Instant after = Instant.now();
        var next = org.mockito.ArgumentCaptor.forClass(Instant.class);
        verify(repository).markRetryableFailure(eq(item.getId()), eq("FAILED_RETRYABLE"), next.capture(),
                eq("synthetic remote failure"));
        assertThat(next.getValue()).isBetween(before.plusSeconds(2), after.plusSeconds(2));
        verify(repository, never()).markDelivered(any(), any());
        verifyNoInteractions(metrics);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void retryLimitStillUsesDeadLetterAndMetricsWithoutCertifyingCompletion() {
        item.setAttempts(5);
        failPort();
        processor.process(item);
        verify(repository).markFinalFailure(item.getId(), "DEAD_LETTER", "synthetic remote failure");
        verify(metrics).recordNotificationDeadLetter("PAYMENT_PROCESSING");
        verify(repository, never()).markRetryableFailure(any(), anyString(), any(), anyString());
        verify(repository, never()).markDelivered(any(), any());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void workerCatchDoesNotCompleteOrPreventRemainingItemsFromBeingProcessed() {
        KfeFinancialNotificationOutboxEntity second = notification();
        due(item, second);
        KfeNotificationOutboxProcessor failing = mock(KfeNotificationOutboxProcessor.class);
        doThrow(new IllegalStateException("synthetic processing failure")).when(failing).process(item);
        KfeNotificationOutboxWorker catching = new KfeNotificationOutboxWorker(outbox, failing);
        catching.setMaintenanceGuard(guard);
        catching.drain();
        verify(failing).process(item);
        verify(failing).process(second);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(admission.id(), true);
    }

    @Test
    void realRemoteClientSwallowedFailureStillLeavesDeliveryUncertain() {
        item.setPayloadJson("{\"walletId\":\"" + UUID.randomUUID() + "\",\"rail\":\"ONCHAIN\",\"amountSats\":42}");
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        RestTemplate remote = mock(RestTemplate.class);
        when(builder.setConnectTimeout(any())).thenReturn(builder);
        when(builder.setReadTimeout(any())).thenReturn(builder);
        when(builder.build()).thenReturn(remote);
        when(remote.postForEntity(anyString(), any(), eq(Void.class)))
                .thenThrow(new IllegalStateException("synthetic remote timeout"));
        KfeRemoteFinancialNotificationClient actualBestEffortClient = new KfeRemoteFinancialNotificationClient(
                builder, "https://synthetic.example.invalid", "synthetic-unit-test-secret", 1, 1);
        actualBestEffortClient.setMaintenanceGuard(guard);
        KfeNotificationOutboxProcessor actual = new KfeNotificationOutboxProcessor(
                outbox, actualBestEffortClient, metrics);
        actual.setMaintenanceGuard(guard);
        actual.process(item);
        verify(remote).postForEntity(eq("https://synthetic.example.invalid/internal/kfe/notifications/payment-initiated"),
                any(), eq(Void.class));
        // Preserve the legacy local label; never elevate it to recipient proof.
        verify(repository).markDelivered(eq(item.getId()), any());
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(admission.id(), true);
    }

    @Test
    void normalPortReturnAlsoDoesNotProvideReliableRecipientProof() {
        processor.process(item);
        verify(port).notifyPaymentInitiated(7L, item.getTransactionId(), null, "ONCHAIN", 42L);
        verify(repository).markDelivered(eq(item.getId()), any());
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(admission.id(), true);
    }

    @Test
    void unknownEventKeepsOriginalNoDispatchBehaviorWithoutClaimingDeliveryProof() {
        item.setEventType("UNMAPPED_NOTIFICATION");
        processor.process(item);
        verifyNoInteractions(port);
        verify(repository).markDelivered(eq(item.getId()), any());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void zeroRowDeliveredWriteDoesNotCompleteAnAdmission() {
        when(repository.markDelivered(any(), any())).thenReturn(0);
        outbox.markDelivered(item.getId());
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(admission.id(), true);
    }

    @Test
    void statusWriteFailureIsUncertainAndWorkflowDoesNotLeak() {
        when(repository.markDelivered(any(), any())).thenThrow(new IllegalStateException("synthetic database failure"));
        assertThatThrownBy(() -> outbox.markDelivered(item.getId())).isInstanceOf(IllegalStateException.class);
        verify(store).resolve(admission.id(), false);
        draining();
        assertRejected(() -> processor.process(item));
        verifyNoInteractions(port);
    }

    @Test
    void claimFailureLeavesRootUncertainWithoutSending() {
        due(item);
        when(repository.claimDue(eq(item.getId()), anyCollection(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("synthetic claim failure"));
        assertThatThrownBy(worker::drain).isInstanceOf(IllegalStateException.class);
        verify(store).resolve(admission.id(), false);
        verifyNoInteractions(port);
    }

    @Test
    void deliveryIsNeverCompletedJustBecauseItsParentTransactionCommitted() {
        bindTransaction();
        processor.process(item);
        verify(repository).markDelivered(eq(item.getId()), any());
        verify(store, never()).resolve(any(), anyBoolean());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(admission.id(), true);
        verify(store, never()).captureContinuation(any(), anyString(), anyBoolean());
    }

    @Test
    void rollbackCannotTurnAnOtherwiseEmptyClaimIntoCompletedWork() {
        when(repository.findTop100ClaimCandidates(anyCollection(), any())).thenReturn(List.of());
        bindTransaction();
        outbox.claimDue("worker");
        verify(store, never()).resolve(any(), anyBoolean());
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void unknownCompletionRemainsUncertainEvenForAnEmptyClaim() {
        when(repository.findTop100ClaimCandidates(anyCollection(), any())).thenReturn(List.of());
        bindTransaction();
        outbox.claimDue("worker");
        finishTransaction(TransactionSynchronization.STATUS_UNKNOWN);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void unobservableTransactionCannotClaimOrSend() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertRejected(worker::drain);
        verifyNoInteractions(repository, port, metrics);
        verify(store, never()).resolve(admission.id(), true);
    }

    @Test
    void notificationBoundaryDoesNotRemoveAnyUnknownCoverageBlocker() {
        when(store.observe()).thenReturn(new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.DRAINING, "update", 1),
                Instant.now(), Map.of()));
        assertThat(guard.status().safeToUpdate()).isFalse();
        assertThat(guard.status().blockers()).containsEntry("mutationCoverageUnknown", 1L)
                .containsEntry("callbackCoverageUnknown", 1L).containsEntry("readSideEffectsUnknown", 1L);
    }

    private void constructServices() {
        outbox = new KfeFinancialNotificationOutboxService(repository);
        processor = new KfeNotificationOutboxProcessor(outbox, port, metrics);
        worker = new KfeNotificationOutboxWorker(outbox, processor);
    }

    private void invoke(Root root) {
        switch (root) {
            case CLAIM -> outbox.claimDue("worker");
            case RETRY -> outbox.markRetryableFailure(item.getId(), 1, "synthetic failure");
            case DEAD_LETTER -> outbox.markDeadLetter(item.getId(), "synthetic failure");
            case MARK_DELIVERED -> outbox.markDelivered(item.getId());
            case PROCESS -> processor.process(item);
            case PROCESS_DELIVERABLE -> processor.processDeliverable(item);
            case WORKER -> worker.drain();
        }
    }

    private void due(KfeFinancialNotificationOutboxEntity... items) {
        when(repository.findTop100ClaimCandidates(anyCollection(), any())).thenReturn(List.of(items));
        for (KfeFinancialNotificationOutboxEntity notification : items) {
            when(repository.claimDue(eq(notification.getId()), anyCollection(), any(), anyString(), any()))
                    .thenReturn(1);
            when(repository.findById(notification.getId())).thenReturn(Optional.of(notification));
        }
    }

    private void failPort() {
        doThrow(new IllegalStateException("synthetic remote failure")).when(port)
                .notifyPaymentInitiated(anyLong(), any(), any(), anyString(), anyLong());
    }

    private void draining() {
        when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "draining"));
    }

    private static KfeFinancialNotificationOutboxEntity notification() {
        KfeFinancialNotificationOutboxEntity notification = new KfeFinancialNotificationOutboxEntity();
        notification.setEventId(UUID.randomUUID());
        notification.setUserId(7L);
        notification.setTransactionId(UUID.randomUUID());
        notification.setEventType("PAYMENT_PROCESSING");
        notification.setPayloadJson("{\"amountSats\":42}");
        return notification;
    }

    private static void assertRejected(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(KfeMaintenanceGuard.MaintenanceException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(503));
    }

    private static void bindTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void finishTransaction(int status) {
        TransactionSynchronizationManager.getSynchronizations().forEach(
                synchronization -> synchronization.afterCompletion(status));
    }
}
