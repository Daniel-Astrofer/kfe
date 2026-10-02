package com.kerosene.kfe.maintenance;

import com.kerosene.kfe.model.KfeExecutionOutboxEntity;
import com.kerosene.kfe.rail.KfeOnchainPaymentGateway;
import com.kerosene.kfe.rail.LightningPaymentGateway;
import com.kerosene.kfe.repository.KfeExecutionOutboxRepository;
import com.kerosene.kfe.service.KfeExecutionClaimLostException;
import com.kerosene.kfe.service.KfeExecutionOutboxProcessor;
import com.kerosene.kfe.service.KfeExecutionOutboxService;
import com.kerosene.kfe.service.KfeExecutionOutboxWorker;
import com.kerosene.kfe.service.KfeExecutionTransactionHelper;
import com.kerosene.kfe.service.KfeLightningOutboundExecutor;
import com.kerosene.kfe.service.KfeOnchainOutboundExecutor;
import com.kerosene.kfe.service.KfePreparedExecutionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real worker, processor, claim service, rail executors and guard; mock durable/provider boundaries. */
class KfeExecutionOutboxMaintenanceTest {
    private enum Root { CLAIM_DUE, CLAIM_IMMEDIATE, HEARTBEAT, PROCESS, WORKER }

    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final AtomicBoolean draining = new AtomicBoolean();
    private final KfeExecutionOutboxRepository repository = mock(KfeExecutionOutboxRepository.class);
    private final KfeExecutionTransactionHelper helper = mock(KfeExecutionTransactionHelper.class);
    private final KfePreparedExecutionService preparedService = mock(KfePreparedExecutionService.class);
    private final KfeOnchainPaymentGateway onchain = mock(KfeOnchainPaymentGateway.class);
    private final LightningPaymentGateway lightning = mock(LightningPaymentGateway.class);
    private final KfeExecutionOutboxEntity candidate = new KfeExecutionOutboxEntity();
    private final KfeExecutionOutboxService.ExecutionClaim claim =
            new KfeExecutionOutboxService.ExecutionClaim(candidate.getId(), UUID.randomUUID());
    private final UUID transactionId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();
    private final KfeOnchainPaymentGateway.PreparedOnchainPayment prepared =
            new KfeOnchainPaymentGateway.PreparedOnchainPayment("raw-fixture", "txid-fixture", 25L,
                    "funded", "combined", "raw-hash", List.of("signer-fixture"), "intent", "{}");
    private KfeExecutionOutboxService service;
    private KfeExecutionOutboxProcessor processor;
    private KfeExecutionOutboxWorker worker;

    @BeforeEach
    void explicitActiveAdmissionFixture() {
        when(store.admit(anyString())).thenAnswer(invocation -> {
            if (draining.get()) {
                throw new KfeMaintenanceGuard.MaintenanceException(503, "KFE is draining.");
            }
            return admission;
        });
        when(store.observe()).thenAnswer(invocation -> new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(draining.get()
                        ? KfeMaintenanceGuard.Mode.DRAINING : KfeMaintenanceGuard.Mode.ACTIVE,
                        "execution-wave", 0), Instant.now(), Map.of()));
        service = new KfeExecutionOutboxService(repository, 600);
        processor = newProcessor(service);
        worker = new KfeExecutionOutboxWorker(service, processor);
        service.setMaintenanceGuard(guard);
        processor.setMaintenanceGuard(guard);
        worker.setMaintenanceGuard(guard);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void drainingRejectsAllFreshRootsBeforeAnyDatabaseHelperOrProviderEffect(Root root) {
        draining.set(true);
        rejectOrPause(root, service, processor, worker);
        assertNoEffects();
        verify(store).admit(operation(root));
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void missingInjectionRemainsUnavailableIncludingScheduledWorker(Root root) {
        KfeExecutionOutboxService unavailableService = new KfeExecutionOutboxService(repository, 600);
        KfeExecutionOutboxProcessor unavailableProcessor = newProcessor(unavailableService);
        KfeExecutionOutboxWorker unavailableWorker =
                new KfeExecutionOutboxWorker(unavailableService, unavailableProcessor);
        rejectOrPause(root, unavailableService, unavailableProcessor, unavailableWorker);
        assertNoEffects();
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void admissionDatabaseOutageFailsClosedWithoutRailEffects(Root root) {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("admission database down"));
        rejectOrPause(root, service, processor, worker);
        assertNoEffects();
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test
    void allSettersAreMandatoryAndRejectNull() throws Exception {
        for (Class<?> type : List.of(KfeExecutionOutboxService.class,
                KfeExecutionOutboxProcessor.class, KfeExecutionOutboxWorker.class)) {
            var annotation = type.getMethod("setMaintenanceGuard", KfeMaintenanceGuard.class)
                    .getAnnotation(Autowired.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.required()).isTrue();
        }
        assertThatThrownBy(() -> service.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> processor.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> worker.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void legitimatelyClaimedTokenDoesNotAuthorizeFreshProcessingAfterDrain() {
        when(repository.claimImmediate(eq(candidate.getId()), any(), anyString(), any(), any())).thenReturn(1);
        var owned = service.claimImmediate(candidate.getId(), "submit").orElseThrow();
        verify(store).resolve(admission.id(), false);
        clearInvocations(repository, store);
        draining.set(true);
        assertUnavailable(() -> processor.process(owned));
        assertNoEffects();
        verify(store).admit("outbox.process");
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test
    void legitimatelyClaimedTokenDoesNotAuthorizeStandaloneHeartbeatAfterDrain() {
        when(repository.claimImmediate(eq(candidate.getId()), any(), anyString(), any(), any())).thenReturn(1);
        var owned = service.claimImmediate(candidate.getId(), "submit").orElseThrow();
        clearInvocations(repository, store);
        draining.set(true);
        assertUnavailable(() -> service.heartbeat(owned));
        assertNoEffects();
        verify(store).admit("outbox.heartbeat");
    }

    @Test
    void workerRootEnclosesActualClaimHeartbeatPrepareRailExecutionAndBroadcastRecordingDuringDrain() {
        prepareDueClaim();
        prepareExecution();
        AtomicReference<UUID> token = new AtomicReference<>();
        when(repository.claimDue(eq(candidate.getId()), anyCollection(), anyCollection(), any(),
                anyString(), any(UUID.class), any())).thenAnswer(invocation -> {
                    token.set(invocation.getArgument(5));
                    draining.set(true);
                    verify(store).admit("outbox.worker-batch");
                    verify(store, never()).resolve(any(), anyBoolean());
                    return 1;
                });
        when(onchain.broadcastPrepared(prepared)).thenAnswer(invocation -> {
            assertThat(draining).isTrue();
            verify(store, never()).resolve(any(), anyBoolean());
            return paymentResult();
        });
        worker.drain();
        verify(store).admit("outbox.worker-batch");
        verify(store, times(1)).admit(anyString());
        verify(repository, times(2)).heartbeat(eq(candidate.getId()), eq(token.get()), any(), any());
        verify(helper).prepare(candidate.getId(), token.get());
        verify(onchain).broadcastPrepared(prepared);
        verify(helper).recordOutboundBroadcast(candidate.getId(), transactionId, token.get(),
                "core-fixture", "txid-fixture", "txid-fixture", 25L, walletId, "{}");
        verify(store).resolve(admission.id(), false);
        // A later invocation on the same thread cannot inherit the finished worker's admission.
        clearInvocations(repository, helper, preparedService, onchain, lightning, store);
        worker.drain();
        assertNoEffects();
        verify(store).admit("outbox.worker-batch");
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @Test
    void standaloneActiveProcessorAdmitsBeforeHeartbeatAndStaysUncertainAfterSuccess() {
        prepareExecution();
        processor.process(claim);
        var order = inOrder(store, repository, helper, preparedService, onchain);
        order.verify(store).admit("outbox.process");
        order.verify(repository).heartbeat(eq(claim.outboxId()), eq(claim.claimToken()), any(), any());
        order.verify(helper).prepare(claim.outboxId(), claim.claimToken());
        order.verify(repository).heartbeat(eq(claim.outboxId()), eq(claim.claimToken()), any(), any());
        order.verify(preparedService).load(candidate.getId(), transactionId, claim.claimToken(),
                "ONCHAIN_OUTBOUND", KfePreparedExecutionService.PayloadType.ONCHAIN,
                KfeOnchainPaymentGateway.PreparedOnchainPayment.class);
        order.verify(onchain).broadcastPrepared(prepared);
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void swallowedProviderAmbiguityRecordsUnknownAndCannotCompleteWorkerRoot() {
        prepareDueClaim();
        prepareExecution();
        when(onchain.broadcastPrepared(prepared)).thenThrow(new KfeOnchainPaymentGateway.ProviderExecutionAmbiguous(
                "broadcast result unknown", "txid-fixture", "ambiguous-fixture", null));
        worker.drain();
        verify(helper).markUnknown(eq(candidate.getId()), eq(transactionId), any(UUID.class),
                eq("txid-fixture"), eq("ambiguous-fixture"), eq("broadcast result unknown"));
        verify(helper, never()).recordOutboundBroadcast(any(), any(), any(), anyString(), anyString(),
                anyString(), anyLong(), any(), anyString());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void swallowedRetryableProviderFailureCannotCompleteProcessorRoot() {
        prepareExecution();
        when(onchain.broadcastPrepared(prepared)).thenThrow(new IllegalStateException("provider interrupted"));
        processor.process(claim);
        verify(helper).markRetryableFailure(candidate.getId(), transactionId, claim.claimToken(),
                "PROVIDER_RETRYABLE_FAILURE", "provider interrupted");
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void queueDatabaseOutageBeforeClaimDoesNotPrepareOrExecuteRails() {
        when(repository.findTop100ClaimCandidates(anyCollection(), anyCollection(), any()))
                .thenThrow(new IllegalStateException("queue database down"));
        assertThatThrownBy(worker::drain).hasMessage("queue database down");
        verify(repository, never()).claimDue(any(), anyCollection(), anyCollection(), any(),
                anyString(), any(), any());
        assertNoExecutionEffects();
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void immediateClaimDatabaseOutageDoesNotPrepareOrExecuteRails() {
        when(repository.claimImmediate(any(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("queue database down"));
        assertThatThrownBy(() -> service.claimImmediate(candidate.getId(), "submit"))
                .hasMessage("queue database down");
        assertNoExecutionEffects();
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void firstHeartbeatDatabaseOutageCannotReachPreparationOrProvider() {
        when(repository.heartbeat(any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("lease database down"));
        assertThatThrownBy(() -> processor.process(claim)).hasMessage("lease database down");
        assertNoExecutionEffects();
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void workerSwallowedPreparationDatabaseOutageCannotCompleteOrReachRail() {
        prepareDueClaim();
        when(repository.heartbeat(any(), any(), any(), any())).thenReturn(1);
        when(helper.prepare(any(), any())).thenThrow(new IllegalStateException("ledger database down"));
        worker.drain();
        verify(helper).prepare(eq(candidate.getId()), any(UUID.class));
        verifyNoInteractions(preparedService, onchain, lightning);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void preparedPayloadDatabaseOutageCannotReachProviderAndStaysUncertain() {
        prepareExecution();
        when(preparedService.load(eq(candidate.getId()), eq(transactionId), any(UUID.class),
                eq("ONCHAIN_OUTBOUND"), eq(KfePreparedExecutionService.PayloadType.ONCHAIN),
                eq(KfeOnchainPaymentGateway.PreparedOnchainPayment.class)))
                .thenThrow(new IllegalStateException("prepared database down"));
        processor.process(claim);
        verifyNoInteractions(onchain, lightning);
        verify(helper).markRetryableFailure(candidate.getId(), transactionId, claim.claimToken(),
                "PROVIDER_RETRYABLE_FAILURE", "prepared database down");
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void lostFirstLeaseRetainsExistingFailureAndDoesNotPrepareOrExecute() {
        assertThatThrownBy(() -> processor.process(claim)).isInstanceOf(KfeExecutionClaimLostException.class);
        assertNoExecutionEffects();
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void lostSecondLeaseIsStillSwallowedWithoutExecutingAndCannotCompleteRoot() {
        prepareExecution();
        when(repository.heartbeat(any(), any(), any(), any())).thenReturn(1, 0);
        processor.process(claim);
        verify(helper).prepare(candidate.getId(), claim.claimToken());
        verifyNoInteractions(preparedService, onchain, lightning);
        verify(helper, never()).markRetryableFailure(any(), any(), any(), any(), any());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void standaloneHeartbeatPreservesFencedTokenAndLeaseDurationButNotCompletion() {
        when(repository.heartbeat(any(), any(), any(), any())).thenAnswer(invocation -> {
            LocalDateTime now = invocation.getArgument(2);
            LocalDateTime expiry = invocation.getArgument(3);
            assertThat(Duration.between(now, expiry)).isEqualTo(Duration.ofSeconds(600));
            return 1;
        });
        assertThat(service.heartbeat(claim)).isTrue();
        verify(repository).heartbeat(eq(candidate.getId()), eq(claim.claimToken()), any(), any());
        verify(store).admit("outbox.heartbeat");
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void unsuccessfulStandaloneHeartbeatStillHasNoContinuationCompletionProof() {
        assertThat(service.heartbeat(claim)).isFalse();
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void nestedHeartbeatCanContinueDuringDrainButCannotCompleteAnOtherwiseCertainParent() {
        when(repository.heartbeat(any(), any(), any(), any())).thenReturn(1);
        boolean renewed = guard.executeMutation("parent.execution", () -> {
            draining.set(true);
            return service.heartbeat(claim);
        });
        assertThat(renewed).isTrue();
        verify(store).admit("parent.execution");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void preparationSkipDoesNotProveExecutionOrCallbackCompletion() {
        when(repository.heartbeat(any(), any(), any(), any())).thenReturn(1);
        processor.process(claim); // Mock helper returns no preparation, as an existing no-op branch.
        verify(helper).prepare(candidate.getId(), claim.claimToken());
        verify(repository, times(1)).heartbeat(any(), any(), any(), any());
        verifyNoInteractions(preparedService, onchain, lightning);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void emptyBatchCanCompleteWithoutCallingProcessor() {
        when(repository.findTop100ClaimCandidates(anyCollection(), anyCollection(), any())).thenReturn(List.of());
        worker.drain();
        assertNoExecutionEffects();
        verify(repository, never()).heartbeat(any(), any(), any(), any());
        verify(store).admit("outbox.worker-batch");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), true);
    }

    @Test
    void nonemptyDueClaimCannotCompleteBeforeProcessing() {
        prepareDueClaim();
        assertThat(service.claimDue("worker")).hasSize(1);
        assertNoExecutionEffects();
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void unsuccessfulClaimCandidatesCanCompleteAnEmptyClaimResult() {
        when(repository.findTop100ClaimCandidates(anyCollection(), anyCollection(), any()))
                .thenReturn(List.of(candidate));
        assertThat(service.claimDue("worker")).isEmpty();
        verify(store).resolve(admission.id(), true);
        assertNoExecutionEffects();
    }

    @Test
    void emptyImmediateClaimCanComplete() {
        assertThat(service.claimImmediate(candidate.getId(), "submit")).isEmpty();
        verify(store).resolve(admission.id(), true);
        assertNoExecutionEffects();
    }

    @Test
    void invalidClaimInputsRemainPureDuringDrain() {
        draining.set(true);
        assertThat(service.claimImmediate(null, "submit")).isEmpty();
        assertThat(service.heartbeat(null)).isFalse();
        assertThat(service.heartbeat(new KfeExecutionOutboxService.ExecutionClaim(null, claim.claimToken()))).isFalse();
        assertThat(service.heartbeat(new KfeExecutionOutboxService.ExecutionClaim(candidate.getId(), null))).isFalse();
        verifyNoInteractions(store);
        assertNoEffects();
    }

    @Test
    void emptyClaimResolutionWaitsForSpringCommit() {
        var template = new TransactionTemplate(new TestTransactions());
        template.executeWithoutResult(status -> {
            assertThat(service.claimImmediate(candidate.getId(), "submit")).isEmpty();
            verify(store, never()).resolve(any(), anyBoolean());
        });
        verify(store).resolve(admission.id(), true);
    }

    @Test
    void evenEmptyClaimCannotCompleteOnSpringRollback() {
        var template = new TransactionTemplate(new TestTransactions());
        template.executeWithoutResult(status -> {
            assertThat(service.claimImmediate(candidate.getId(), "submit")).isEmpty();
            status.setRollbackOnly();
            verify(store, never()).resolve(any(), anyBoolean());
        });
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void successfulNonemptyClaimRemainsUncertainAfterSpringCommit() {
        when(repository.claimImmediate(any(), any(), anyString(), any(), any())).thenReturn(1);
        var template = new TransactionTemplate(new TestTransactions());
        template.executeWithoutResult(status -> {
            assertThat(service.claimImmediate(candidate.getId(), "submit")).isPresent();
            verify(store, never()).resolve(any(), anyBoolean());
        });
        verify(store).resolve(admission.id(), false);
        assertNoExecutionEffects();
    }

    @Test
    void drainingStillReportsAllUnknownCoverageBlockers() {
        draining.set(true);
        var status = guard.status();
        assertThat(status.safeToUpdate()).isFalse();
        assertThat(status.blockers()).containsEntry("mutationCoverageUnknown", 1L)
                .containsEntry("callbackCoverageUnknown", 1L).containsEntry("readSideEffectsUnknown", 1L);
    }

    private void prepareDueClaim() {
        when(repository.findTop100ClaimCandidates(anyCollection(), anyCollection(), any()))
                .thenReturn(List.of(candidate));
        when(repository.claimDue(eq(candidate.getId()), anyCollection(), anyCollection(), any(),
                anyString(), any(UUID.class), any())).thenReturn(1);
    }

    private void prepareExecution() {
        when(repository.heartbeat(any(), any(), any(), any())).thenReturn(1);
        when(helper.prepare(eq(candidate.getId()), any(UUID.class)))
                .thenAnswer(invocation -> preparation(invocation.getArgument(1)));
        when(preparedService.load(eq(candidate.getId()), eq(transactionId), any(UUID.class),
                eq("ONCHAIN_OUTBOUND"), eq(KfePreparedExecutionService.PayloadType.ONCHAIN),
                eq(KfeOnchainPaymentGateway.PreparedOnchainPayment.class)))
                .thenReturn(Optional.of(new KfePreparedExecutionService.StoredPayload<>(prepared, "txid-fixture")));
        when(onchain.broadcastPrepared(prepared)).thenReturn(paymentResult());
        when(onchain.providerName()).thenReturn("core-fixture");
    }

    private KfeExecutionTransactionHelper.PreparationResult preparation(UUID token) {
        return new KfeExecutionTransactionHelper.PreparationResult(true, "ONCHAIN_OUTBOUND", transactionId,
                7L, "wallet-fixture", walletId, "bcrt1qdestination", 50_000L, 25L,
                "memo", "idempotency", "proof", 1L, 6, token);
    }

    private KfeOnchainPaymentGateway.PaymentResult paymentResult() {
        return new KfeOnchainPaymentGateway.PaymentResult("provider-fixture", "txid-fixture", null,
                "SUCCESS", 25L, "{}");
    }

    private KfeExecutionOutboxProcessor newProcessor(KfeExecutionOutboxService outbox) {
        var onchainExecutor = new KfeOnchainOutboundExecutor(helper, onchain, preparedService);
        var lightningExecutor = new KfeLightningOutboundExecutor(helper, lightning, preparedService);
        onchainExecutor.setMaintenanceGuard(guard);
        lightningExecutor.setMaintenanceGuard(guard);
        return new KfeExecutionOutboxProcessor(helper, List.of(onchainExecutor, lightningExecutor), outbox);
    }

    private void rejectOrPause(Root root, KfeExecutionOutboxService outbox,
                               KfeExecutionOutboxProcessor execution, KfeExecutionOutboxWorker scheduled) {
        if (root == Root.WORKER) {
            assertThatCode(scheduled::drain).doesNotThrowAnyException();
        } else {
            assertUnavailable(() -> call(root, outbox, execution));
        }
    }

    private void call(Root root, KfeExecutionOutboxService outbox, KfeExecutionOutboxProcessor execution) {
        switch (root) {
            case CLAIM_DUE -> outbox.claimDue("worker");
            case CLAIM_IMMEDIATE -> outbox.claimImmediate(candidate.getId(), "submit");
            case HEARTBEAT -> outbox.heartbeat(claim);
            case PROCESS -> execution.process(claim);
            case WORKER -> throw new AssertionError("Worker is called separately.");
        }
    }

    private String operation(Root root) {
        return switch (root) {
            case CLAIM_DUE -> "outbox.claim-due";
            case CLAIM_IMMEDIATE -> "outbox.claim-immediate";
            case HEARTBEAT -> "outbox.heartbeat";
            case PROCESS -> "outbox.process";
            case WORKER -> "outbox.worker-batch";
        };
    }

    private void assertUnavailable(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(KfeMaintenanceGuard.MaintenanceException.class,
                failure -> assertThat(failure.httpStatus()).isEqualTo(503));
    }

    private void assertNoEffects() {
        verifyNoInteractions(repository);
        assertNoExecutionEffects();
    }

    private void assertNoExecutionEffects() {
        verifyNoInteractions(helper, preparedService, onchain, lightning);
    }

    /** Actual Spring completion callbacks; no simulated SQL or financial rollback. */
    private static final class TestTransactions extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
