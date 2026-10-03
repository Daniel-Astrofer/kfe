package com.kerosene.kfe.maintenance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.kfe.application.transaction.KfeInternalPaymentRequestSettlementUseCase;
import com.kerosene.kfe.application.transaction.KfeTransactionIdempotencyUseCase;
import com.kerosene.kfe.application.transaction.KfeTransactionOutboxUseCase;
import com.kerosene.kfe.application.transaction.KfeTransactionStateMachine;
import com.kerosene.kfe.dto.KfeSubmitTransactionRequest;
import com.kerosene.kfe.dto.KfeTransactionResponse;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeExecutionOutboxEntity;
import com.kerosene.kfe.model.KfeIdempotencyEntity;
import com.kerosene.kfe.model.KfeIdempotencyId;
import com.kerosene.kfe.model.KfePaymentRequestEntity;
import com.kerosene.kfe.model.KfePaymentRequestStatus;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.repository.KfeExecutionOutboxRepository;
import com.kerosene.kfe.repository.KfeIdempotencyRepository;
import com.kerosene.kfe.repository.KfePaymentRequestRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeHashService;
import com.kerosene.kfe.service.KfeResponseMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Bounded participants only: mock persistence, real admission and domain algorithms. */
class KfeTransactionParticipantMaintenanceTest {
    enum Root { TRANSITION, AUDIT, RESERVE, COMPLETE, OUTBOX, REQUEST_LOCK, MARK_PAID }
    enum Rejection { DRAIN, DEFAULT, OUTAGE }

    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 7);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeAuditLogService auditLog = mock(KfeAuditLogService.class);
    private final KfeHashService hashes = spy(new KfeHashService());
    private final KfeIdempotencyRepository idempotencies = mock(KfeIdempotencyRepository.class);
    private final KfeResponseMapper responses = mock(KfeResponseMapper.class);
    private final KfeExecutionOutboxRepository outboxes = mock(KfeExecutionOutboxRepository.class);
    private final ObjectMapper json = spy(new ObjectMapper());
    private final KfePaymentRequestRepository requests = mock(KfePaymentRequestRepository.class);
    private KfeTransactionStateMachine stateMachine;
    private KfeTransactionIdempotencyUseCase idempotency;
    private KfeTransactionOutboxUseCase outbox;
    private KfeInternalPaymentRequestSettlementUseCase settlement;

    private final UUID sourceWalletId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final UUID destinationWalletId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private final KfeTransactionEntity tx = new KfeTransactionEntity();
    private final KfeTransactionEntity settledTx = new KfeTransactionEntity();
    private final KfeIdempotencyEntity pending = new KfeIdempotencyEntity();
    private final KfePaymentRequestEntity paymentRequest = new KfePaymentRequestEntity();

    @BeforeEach
    void setUp() {
        constructParticipants();
        inject(guard);
        when(store.admit(anyString())).thenReturn(admission);
        when(store.observe()).thenReturn(new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.ACTIVE, null, 7),
                java.time.Instant.now(), Map.of()));
        tx.setIdempotencyKey("synthetic-key");
        tx.setUserId(7L);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setSourceWalletId(sourceWalletId);
        tx.setDestinationWalletId(destinationWalletId);
        tx.setReceiverAmountSats(100);
        tx.setNetworkFeeSats(3);
        tx.setTotalDebitSats(105);
        tx.setQuorumProposalHash("synthetic-quorum");
        settledTx.setStatus(KfeTransactionStatus.SETTLED);
        pending.setId(new KfeIdempotencyId(7L, "synthetic-key"));
        pending.setRequestHash("synthetic-request-hash");
        pending.setStatus("PENDING");
        paymentRequest.setPublicId("synthetic-public-id");
        paymentRequest.setWalletId(destinationWalletId);
        paymentRequest.setRail(KfeRail.LIGHTNING);
        paymentRequest.setStatus(KfePaymentRequestStatus.OPEN);
        paymentRequest.setAmountSats(100L);
    }

    @AfterEach
    void clearTransactionState() {
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void drainRejectsEveryRootBeforeEffects(Root root) {
        reject(Rejection.DRAIN);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        unchangedAndNoEffects();
        verify(store).admit(operation(root));
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void missingInjectionRejectsEveryRootBeforeEffects(Root root) {
        reject(Rejection.DEFAULT);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        unchangedAndNoEffects();
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void admissionOutageRejectsEveryRootBeforeEffects(Root root) {
        reject(Rejection.OUTAGE);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        unchangedAndNoEffects();
        verify(store).admit(operation(root));
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void activeRootsKeepTheirResultsAndRemainUncertainWithoutTransaction(Root root) {
        activeRows();
        assertThat(guard.status().mode()).isEqualTo(KfeMaintenanceGuard.Mode.ACTIVE);
        Object result = invoke(root);
        verify(store, times(1)).admit(operation(root));
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        switch (root) {
            case TRANSITION -> {
                assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.VALIDATING);
                verify(transactions).save(tx);
                verifyAudit(KfeTransactionStatus.INTENT, KfeTransactionStatus.VALIDATING);
            }
            case AUDIT -> {
                assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.INTENT);
                verifyNoInteractions(transactions);
                verifyAudit(KfeTransactionStatus.INTENT, KfeTransactionStatus.INTENT);
            }
            case RESERVE -> {
                KfeIdempotencyEntity reserved = (KfeIdempotencyEntity) result;
                assertThat(reserved.getId()).isEqualTo(new KfeIdempotencyId(7L, "synthetic-key"));
                assertThat(reserved.getRequestHash()).isEqualTo("synthetic-request-hash");
                assertThat(reserved.getStatus()).isEqualTo("PENDING");
                assertThat(reserved.getTransactionId()).isNull();
                verify(idempotencies).save(reserved);
            }
            case COMPLETE -> {
                assertThat(pending.getTransactionId()).isEqualTo(tx.getId());
                assertThat(pending.getStatus()).isEqualTo("INTENT");
                verify(idempotencies).save(pending);
            }
            case OUTBOX -> {
                KfeExecutionOutboxEntity row = savedOutbox();
                assertThat(result).isEqualTo(row.getId());
                assertThat(row.getTransactionId()).isEqualTo(tx.getId());
                assertThat(row.getOperation()).isEqualTo("ONCHAIN_OUTBOUND");
                assertThat(row.getStatus()).isEqualTo("PENDING");
                assertThat(row.getPayloadHash()).isEqualTo(new KfeHashService().sha256(row.getPayloadJson()));
                assertThat(row.getNextAttemptAt()).isNotNull();
            }
            case REQUEST_LOCK -> {
                assertThat(result).isSameAs(paymentRequest);
                assertThat(paymentRequest.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
                verify(requests).findByPublicIdForUpdate("synthetic-public-id");
                verify(requests, never()).save(any());
            }
            case MARK_PAID -> {
                assertThat(paymentRequest.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
                assertThat(paymentRequest.getPaidTransactionId()).isEqualTo(settledTx.getId());
                verify(requests).save(paymentRequest);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void evenObservedCommitLeavesParticipantUncertain(Root root) {
        activeRows();
        beginTransaction();
        invoke(root);
        verify(store, never()).resolve(any(), anyBoolean());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void rollbackLeavesParticipantUncertain(Root root) {
        activeRows();
        beginTransaction();
        invoke(root);
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void unknownTransactionCompletionLeavesParticipantUncertain(Root root) {
        activeRows();
        beginTransaction();
        invoke(root);
        finishTransaction(TransactionSynchronization.STATUS_UNKNOWN);
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void unobservableTransactionIsRejectedBeforeEffects(Root root) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        unchangedAndNoEffects();
        verify(store).admit(operation(root));
    }

    @ParameterizedTest
    @EnumSource(Root.class)
    void nestedParticipantCanFinishAfterDrainAndTaintsCertainParent(Root root) {
        activeRows();
        guard.executeMutation("synthetic.parent", () -> {
            reject(Rejection.DRAIN);
            invoke(root);
            return true;
        }, result -> result);
        verify(store, times(1)).admit("synthetic.parent");
        verify(store, times(1)).admit(anyString());
        verify(store).resolve(admission.id(), false);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
    }

    @Test
    void admissionPrecedesTransitionDirtyingAndAuditHashing() {
        when(transactions.save(tx)).thenAnswer(call -> {
            verify(store).admit("transaction.transition");
            assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.VALIDATING);
            return tx;
        });
        stateMachine.transition(tx, KfeTransactionStatus.VALIDATING, "SYNTHETIC", Map.of("detail", "test"));
        var order = inOrder(store, transactions, hashes, auditLog);
        order.verify(store).admit("transaction.transition");
        order.verify(transactions).save(tx);
        order.verify(hashes).sha256("synthetic-key");
        order.verify(auditLog).record(eq("SYNTHETIC"), eq(tx.getId()), eq(sourceWalletId),
                eq(KfeTransactionStatus.INTENT), eq(KfeTransactionStatus.VALIDATING), any());
        order.verify(store).resolve(admission.id(), false);
    }

    @Test
    void auditPortFailureAfterTransitionDoesNotManufactureCompletion() {
        doThrow(new IllegalStateException("synthetic audit outage")).when(auditLog)
                .record(anyString(), any(), any(), any(), any(), any());
        assertThatThrownBy(() -> invoke(Root.TRANSITION)).hasMessage("synthetic audit outage");
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.VALIDATING);
        verify(transactions).save(tx);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void invalidTransitionKeepsManagedStateAndSkipsAuditButRemainsUncertain() {
        tx.setStatus(KfeTransactionStatus.SETTLED);
        assertThatThrownBy(() -> stateMachine.transition(tx, KfeTransactionStatus.EXECUTING, "SYNTHETIC", null))
                .hasMessage("Invalid KFE transaction transition from SETTLED to EXECUTING.");
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        verifyNoInteractions(transactions, hashes, auditLog);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void idempotencyConflictIsPropagatedAndDoesNotCompleteAdmission() {
        DataIntegrityViolationException conflict = new DataIntegrityViolationException("synthetic duplicate");
        when(idempotencies.save(any())).thenThrow(conflict);
        assertThatThrownBy(() -> invoke(Root.RESERVE)).isSameAs(conflict);
        verifyNoInteractions(transactions, requests, outboxes, responses);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void idempotencyCompletionSaveFailureRetainsUncertaintyAfterManagedChange() {
        when(idempotencies.save(pending)).thenThrow(new IllegalStateException("synthetic save outage"));
        assertThatThrownBy(() -> invoke(Root.COMPLETE)).hasMessage("synthetic save outage");
        assertThat(pending.getTransactionId()).isEqualTo(tx.getId());
        assertThat(pending.getStatus()).isEqualTo("INTENT");
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void outboxPreservesOrderedPayloadAndFeeTierFields() throws Exception {
        LocalDateTime before = LocalDateTime.now(ZoneOffset.UTC);
        UUID id = outbox.enqueueExternal(tx, request("synthetic-public-id", 4L, 2));
        KfeExecutionOutboxEntity row = savedOutbox();
        assertThat(id).isEqualTo(row.getId());
        String expected = "{\"transactionId\":\"" + tx.getId() + "\",\"idempotencyKey\":\"synthetic-key\","
                + "\"userId\":7,\"rail\":\"ONCHAIN\",\"direction\":\"OUTBOUND\","
                + "\"sourceWalletId\":\"00000000-0000-0000-0000-000000000001\","
                + "\"destinationWalletId\":\"00000000-0000-0000-0000-000000000002\","
                + "\"amountSats\":100,\"networkFeeSats\":3,\"totalDebitSats\":105,"
                + "\"externalReference\":\"synthetic-external\",\"memo\":\"synthetic memo\","
                + "\"quorumProposalHash\":\"synthetic-quorum\",\"feeRateSatsPerVbyte\":4,\"feeTargetBlocks\":2}";
        assertThat(row.getPayloadJson()).isEqualTo(expected);
        assertThat(row.getPayloadHash()).isEqualTo(new KfeHashService().sha256(expected));
        assertThat(row.getNextAttemptAt()).isBetween(before, LocalDateTime.now(ZoneOffset.UTC));
        var order = inOrder(store, json, hashes, outboxes);
        order.verify(store).admit("transaction.outbox-enqueue");
        order.verify(json).writeValueAsString(any());
        order.verify(hashes).sha256(expected);
        order.verify(outboxes).save(row);
        order.verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonpositiveOutboxFeeTierFieldsStayOmitted(long value) throws Exception {
        outbox.enqueueExternal(tx, request(null, value, (int) value));
        var payload = new ObjectMapper().readTree(savedOutbox().getPayloadJson());
        assertThat(payload.has("feeRateSatsPerVbyte")).isFalse();
        assertThat(payload.has("feeTargetBlocks")).isFalse();
    }

    @Test
    void serializationFailureRetainsExactExceptionAndSkipsHashAndSave() throws Exception {
        JsonProcessingException failure = new JsonProcessingException("synthetic serialization failure") { };
        doThrow(failure).when(json).writeValueAsString(any());
        assertThatThrownBy(() -> invoke(Root.OUTBOX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Could not serialize KFE outbox payload.")
                .hasCause(failure);
        verifyNoInteractions(hashes, outboxes);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void outboxHashFailureSkipsSaveAndRemainsUncertain() {
        doThrow(new IllegalStateException("synthetic hash failure")).when(hashes).sha256(anyString());
        assertThatThrownBy(() -> invoke(Root.OUTBOX)).hasMessage("synthetic hash failure");
        verifyNoInteractions(outboxes);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void outboxSaveFailureRemainsUncertain() {
        when(outboxes.save(any())).thenThrow(new IllegalStateException("synthetic outbox save failure"));
        assertThatThrownBy(() -> invoke(Root.OUTBOX)).hasMessage("synthetic outbox save failure");
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void lockFailureAndPaidSaveFailureRemainUncertain() {
        when(requests.findByPublicIdForUpdate(anyString())).thenThrow(new IllegalStateException("synthetic lock failure"));
        assertThatThrownBy(() -> invoke(Root.REQUEST_LOCK)).hasMessage("synthetic lock failure");
        assertThat(paymentRequest.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        when(requests.save(paymentRequest)).thenThrow(new IllegalStateException("synthetic paid save failure"));
        assertThatThrownBy(() -> invoke(Root.MARK_PAID)).hasMessage("synthetic paid save failure");
        assertThat(paymentRequest.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
        assertThat(paymentRequest.getPaidTransactionId()).isEqualTo(settledTx.getId());
        verify(store, times(2)).resolve(admission.id(), false);
    }

    @Test
    void returnedRequestIsNotAuthorityForFreshMarkPaidDuringDrain() {
        activeRows();
        KfePaymentRequestEntity locked = settlement.lockAndValidate(request("synthetic-public-id"));
        reject(Rejection.DRAIN);
        assertThatThrownBy(() -> settlement.markPaid(locked, settledTx))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        assertThat(locked.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        assertThat(locked.getPaidTransactionId()).isNull();
        verify(requests, never()).save(any());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void resolutionOutagePreservesParticipantResultWithoutCertainRetry() {
        doThrow(new IllegalStateException("synthetic resolution outage")).when(store).resolve(admission.id(), false);
        invoke(Root.MARK_PAID);
        assertThat(paymentRequest.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
        verify(requests).save(paymentRequest);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @ParameterizedTest
    @EnumSource(Rejection.class)
    void pureIdempotencyLookupsHashAndNoopsNeedNoAdmission(Rejection rejection) {
        reject(rejection);
        KfeTransactionResponse response = mock(KfeTransactionResponse.class);
        pending.setTransactionId(tx.getId());
        when(idempotencies.findById(pending.getId())).thenReturn(Optional.of(pending));
        when(transactions.findById(tx.getId())).thenReturn(Optional.of(tx));
        when(responses.toTransactionResponse(tx)).thenReturn(response);
        assertThat(idempotency.find(7L, "synthetic-key")).isSameAs(pending);
        assertThat(idempotency.existingResponse(pending, "synthetic-request-hash")).isSameAs(response);
        assertThat(idempotency.getExistingByIdempotency(7L, "synthetic-key", "synthetic-request-hash"))
                .isSameAs(response);
        String canonical = "KFE_TX_REQUEST|7|INTERNAL|INTERNAL|00000000-0000-0000-0000-000000000001"
                + "|00000000-0000-0000-0000-000000000002|100|3|synthetic-external|synthetic-public-id|synthetic memo";
        assertThat(idempotency.requestHash(7L, request("synthetic-public-id")))
                .isEqualTo(new KfeHashService().sha256(canonical));
        verify(hashes).sha256(canonical);
        assertThat(settlement.lockAndValidate(request(null))).isNull();
        assertThat(settlement.lockAndValidate(request(" \t "))).isNull();
        settlement.markPaid(null, null);
        verifyNoInteractions(store, auditLog, json, outboxes, requests);
        verify(transactions, never()).save(any());
        verify(idempotencies, never()).save(any());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void absentPublicIdAndNullMarkPaidStayNoopsWithUnavailableGuard(String publicId) {
        constructParticipants();
        assertThat(settlement.lockAndValidate(request(publicId))).isNull();
        settlement.markPaid(null, null);
        unchangedAndNoEffects();
        verifyNoInteractions(store);
    }

    @Test
    void pureConflictsRetainAllOriginalMessagesDuringDrain() {
        reject(Rejection.DRAIN);
        assertThatThrownBy(() -> idempotency.existingResponse(pending, "other"))
                .hasMessage("Idempotency key was reused with a different transaction payload.");
        assertThatThrownBy(() -> idempotency.existingResponse(pending, "synthetic-request-hash"))
                .hasMessage("Transaction is currently being processed. Please retry.");
        when(idempotencies.findById(pending.getId())).thenReturn(Optional.of(pending));
        assertThatThrownBy(() -> idempotency.getExistingByIdempotency(7L, "synthetic-key", "other"))
                .hasMessage("Idempotency key was reused with a different transaction payload.");
        assertThatThrownBy(() -> idempotency.getExistingByIdempotency(7L, "synthetic-key", "synthetic-request-hash"))
                .hasMessage("Transaction is currently being processed. Please retry.");
        pending.setTransactionId(tx.getId());
        when(transactions.findById(tx.getId())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> idempotency.existingResponse(pending, "synthetic-request-hash"))
                .hasMessage("Idempotent transaction record is missing.");
        assertThatThrownBy(() -> idempotency.getExistingByIdempotency(7L, "synthetic-key", "synthetic-request-hash"))
                .hasMessage("Idempotent transaction record is missing.");
        when(idempotencies.findById(pending.getId())).thenReturn(Optional.empty());
        assertThat(idempotency.find(7L, "synthetic-key")).isNull();
        assertThatThrownBy(() -> idempotency.getExistingByIdempotency(7L, "synthetic-key", "synthetic-request-hash"))
                .hasMessage("Idempotency conflict detected, but no record found.");
        verifyNoInteractions(store, responses, hashes);
        verify(idempotencies, never()).save(any());
        verify(transactions, never()).save(any());
    }

    @Test
    void nullOptionalHashInputsKeepOriginalEmptyStringEncoding() {
        constructParticipants();
        var request = new KfeSubmitTransactionRequest("synthetic-key", KfeRail.INTERNAL, KfeDirection.INTERNAL,
                null, null, 100, 0, null, null);
        String canonical = "KFE_TX_REQUEST|7|INTERNAL|INTERNAL|null|null|100|0|||";
        assertThat(idempotency.requestHash(7L, request)).isEqualTo(new KfeHashService().sha256(canonical));
        verifyNoInteractions(store);
    }

    @Test
    void settersRejectNullWithoutReplacingInjectedGuard() {
        assertThatThrownBy(() -> stateMachine.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> idempotency.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> outbox.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> settlement.setMaintenanceGuard(null)).isInstanceOf(NullPointerException.class);
        reject(Rejection.DRAIN);
        for (Root root : Root.values()) {
            assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        }
        unchangedAndNoEffects();
    }

    @Test
    void existingPropagationAndAbsenceOfTransactionAnnotationsArePreserved() throws Exception {
        var transition = KfeTransactionStateMachine.class.getMethod("transition", KfeTransactionEntity.class,
                KfeTransactionStatus.class, String.class, Map.class).getAnnotation(Transactional.class);
        assertThat(transition).isNotNull();
        assertThat(transition.propagation()).isEqualTo(Propagation.REQUIRED);
        for (Class<?> type : new Class<?>[]{KfeTransactionStateMachine.class, KfeTransactionIdempotencyUseCase.class,
                KfeTransactionOutboxUseCase.class, KfeInternalPaymentRequestSettlementUseCase.class}) {
            assertThat(type.getAnnotation(Transactional.class)).isNull();
            for (var method : type.getDeclaredMethods()) {
                if (!(type == KfeTransactionStateMachine.class && method.getName().equals("transition"))) {
                    assertThat(method.getAnnotation(Transactional.class)).isNull();
                }
            }
        }
    }

    private void constructParticipants() {
        stateMachine = new KfeTransactionStateMachine(transactions, auditLog, hashes);
        idempotency = new KfeTransactionIdempotencyUseCase(idempotencies, transactions, hashes, responses);
        outbox = new KfeTransactionOutboxUseCase(outboxes, hashes, json);
        settlement = new KfeInternalPaymentRequestSettlementUseCase(requests);
    }

    private void inject(KfeMaintenanceGuard supplied) {
        stateMachine.setMaintenanceGuard(supplied);
        idempotency.setMaintenanceGuard(supplied);
        outbox.setMaintenanceGuard(supplied);
        settlement.setMaintenanceGuard(supplied);
    }

    private void reject(Rejection rejection) {
        switch (rejection) {
            case DRAIN -> doThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain"))
                    .when(store).admit(anyString());
            case DEFAULT -> constructParticipants();
            case OUTAGE -> doThrow(new IllegalStateException("synthetic storage outage"))
                    .when(store).admit(anyString());
        }
    }

    private void activeRows() {
        when(idempotencies.save(any())).thenAnswer(call -> call.getArgument(0));
        when(requests.findByPublicIdForUpdate("synthetic-public-id")).thenReturn(Optional.of(paymentRequest));
    }

    private Object invoke(Root root) {
        return switch (root) {
            case TRANSITION -> {
                stateMachine.transition(tx, KfeTransactionStatus.VALIDATING, "SYNTHETIC", Map.of("detail", "test"));
                yield null;
            }
            case AUDIT -> {
                stateMachine.audit(tx, "SYNTHETIC", KfeTransactionStatus.INTENT,
                        KfeTransactionStatus.INTENT, Map.of("detail", "test"));
                yield null;
            }
            case RESERVE -> idempotency.reserve(7L, request("synthetic-public-id"), "synthetic-request-hash");
            case COMPLETE -> { idempotency.complete(pending, tx); yield null; }
            case OUTBOX -> outbox.enqueueExternal(tx, request("synthetic-public-id"));
            case REQUEST_LOCK -> settlement.lockAndValidate(request(" synthetic-public-id "));
            case MARK_PAID -> { settlement.markPaid(paymentRequest, settledTx); yield null; }
        };
    }

    private String operation(Root root) {
        return switch (root) {
            case TRANSITION -> "transaction.transition";
            case AUDIT -> "transaction.audit";
            case RESERVE -> "transaction.idempotency-reserve";
            case COMPLETE -> "transaction.idempotency-complete";
            case OUTBOX -> "transaction.outbox-enqueue";
            case REQUEST_LOCK -> "transaction.internal-request-lock";
            case MARK_PAID -> "transaction.internal-request-mark-paid";
        };
    }

    private KfeSubmitTransactionRequest request(String publicId) {
        return request(publicId, null, null);
    }

    private KfeSubmitTransactionRequest request(String publicId, Long feeRate, Integer targetBlocks) {
        return new KfeSubmitTransactionRequest("synthetic-key", KfeRail.INTERNAL, KfeDirection.INTERNAL,
                sourceWalletId, destinationWalletId, 100, 3, "synthetic-external", "synthetic memo",
                null, null, null, null, publicId, feeRate, targetBlocks, null);
    }

    private void unchangedAndNoEffects() {
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.INTENT);
        assertThat(pending.getStatus()).isEqualTo("PENDING");
        assertThat(pending.getTransactionId()).isNull();
        assertThat(paymentRequest.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        assertThat(paymentRequest.getPaidTransactionId()).isNull();
        verifyNoInteractions(transactions, auditLog, hashes, idempotencies, responses, outboxes, json, requests);
    }

    private void verifyAudit(KfeTransactionStatus from, KfeTransactionStatus to) {
        verify(auditLog).record("SYNTHETIC", tx.getId(), sourceWalletId, from, to,
                Map.of("transactionId", tx.getId().toString(),
                        "idempotencyHash", new KfeHashService().sha256("synthetic-key"), "detail", "test"));
    }

    private KfeExecutionOutboxEntity savedOutbox() {
        ArgumentCaptor<KfeExecutionOutboxEntity> capture = ArgumentCaptor.forClass(KfeExecutionOutboxEntity.class);
        verify(outboxes).save(capture.capture());
        return capture.getValue();
    }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void finishTransaction(int status) {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clear();
        callbacks.forEach(callback -> callback.afterCompletion(status));
    }
}
