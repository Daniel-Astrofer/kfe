package com.kerosene.kfe.maintenance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kerosene.common.audit.AuditEventPayloadSanitizer;
import com.kerosene.common.audit.StructuredAuditLogger;
import com.kerosene.kfe.model.KfeAuditLogEntity;
import com.kerosene.kfe.model.KfeLightningLiquidityReservationEntity;
import com.kerosene.kfe.model.KfeLiquidityReservationStatus;
import com.kerosene.kfe.rail.LightningClient;
import com.kerosene.kfe.rail.LightningPaymentGateway;
import com.kerosene.kfe.repository.KfeAuditLogRepository;
import com.kerosene.kfe.repository.KfeLightningLiquidityReservationRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeCapacitySignalStore;
import com.kerosene.kfe.service.KfeHashService;
import com.kerosene.kfe.service.KfeLightningLiquidityService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real admission service with synthetic storage; no PostgreSQL or provider authority. */
class KfeLiquidityAuditMaintenanceTest {
    private enum Root { RESERVE, CONSUME, RELEASE, BREAKER, AUDIT, AUDIT_NEW }
    private enum ReadAdmission { DRAINING, MISSING, OUTAGE }

    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission =
            new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final ObjectProvider<LightningClient> clients = provider();
    private final ObjectProvider<LightningPaymentGateway> gateways = provider();
    private final ObjectProvider<KfeCapacitySignalStore> signals = provider();
    private final LightningClient client = mock(LightningClient.class);
    private final LightningPaymentGateway gateway = mock(LightningPaymentGateway.class);
    private final KfeCapacitySignalStore signalStore = mock(KfeCapacitySignalStore.class);
    private final KfeLightningLiquidityReservationRepository reservations =
            mock(KfeLightningLiquidityReservationRepository.class);
    private final KfeAuditLogRepository events = mock(KfeAuditLogRepository.class);
    private final KfeHashService hashes = spy(new KfeHashService());
    private final ObjectMapper mapper = spy(new ObjectMapper());
    private final StructuredAuditLogger logger = mock(StructuredAuditLogger.class);
    private KfeLightningLiquidityService liquidity = liquidity(500);
    private KfeAuditLogService audit = new KfeAuditLogService(events, hashes, mapper, logger);
    private final UUID transactionId = UUID.randomUUID();
    private final UUID walletId = UUID.randomUUID();
    private final KfeLightningLiquidityReservationEntity held = new KfeLightningLiquidityReservationEntity();

    @BeforeEach
    void setup() {
        liquidity.setMaintenanceGuard(guard);
        audit.setMaintenanceGuard(guard);
        lenient().when(store.admit(anyString())).thenReturn(admission);
        held.setTransactionId(transactionId);
        held.setAmountSats(50);
        held.setStatus(KfeLiquidityReservationStatus.HELD);
    }

    @AfterEach
    void clearTransaction() {
        TransactionSynchronizationManager.clear();
    }

    @ParameterizedTest @EnumSource(Root.class)
    void drainRejectsBeforeProvidersLocksHashesOrManagedMutations(Root root) {
        rejectDrain();
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects();
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest @EnumSource(Root.class)
    void missingMandatoryInjectionIsUnavailableBeforeEffects(Root root) {
        liquidity = liquidity(500);
        audit = new KfeAuditLogService(events, hashes, mapper, logger);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects();
        verifyNoInteractions(store);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void storageOutageRejectsBeforeEffects(Root root) {
        doThrow(new IllegalStateException("synthetic outage")).when(store).admit(anyString());
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class)
                .hasMessage("KFE maintenance admission is unavailable.");
        noEffects();
    }

    @ParameterizedTest @EnumSource(Root.class)
    void unobservableTransactionRejectsBeforeEffects(Root root) {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        noEffects();
    }

    @ParameterizedTest @EnumSource(Root.class)
    void activeDirectCallsPreserveBehaviorButCannotProveTransactionCompletion(Root root) {
        activeRows(root);
        invoke(root);
        verify(store).resolve(admission.id(), false);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertActiveResult(root);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void onlyLocalTerminalWritesCanCompleteAfterObservedCommit(Root root) {
        activeRows(root);
        beginTransaction();
        invoke(root);
        verify(store, never()).resolve(any(), anyBoolean());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), root == Root.CONSUME || root == Root.RELEASE);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void rollbackDoesNotManufactureCompletion(Root root) {
        activeRows(root);
        beginTransaction();
        invoke(root);
        finishTransaction(TransactionSynchronization.STATUS_ROLLED_BACK);
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void unknownTransactionOutcomeRemainsUncertain(Root root) {
        activeRows(root);
        beginTransaction();
        invoke(root);
        finishTransaction(TransactionSynchronization.STATUS_UNKNOWN);
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void admittedParentCanCallNestedRootsAfterDrainStarts(Root root) {
        activeRows(root);
        beginTransaction();
        guard.executeMutation("synthetic.parent", () -> {
            rejectDrain();
            invoke(root);
            return null;
        });
        assertActiveResult(root);
        verify(store, times(1)).admit(anyString());
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), root == Root.CONSUME || root == Root.RELEASE);
        clearInvocations(clients, gateways, signals, client, gateway, signalStore,
                reservations, events, hashes, mapper, logger);
        assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(clients, gateways, signals, client, gateway, signalStore,
                reservations, events, hashes, mapper, logger);
    }

    @ParameterizedTest @EnumSource(ReadAdmission.class)
    void pureObservationsAndDisabledBreakerStayReadable(ReadAdmission condition) {
        activeRows(Root.RESERVE);
        when(gateways.getIfAvailable()).thenReturn(gateway);
        when(gateway.isLive()).thenReturn(true);
        when(reservations.sumAmountByStatus(KfeLiquidityReservationStatus.HELD)).thenReturn(200L);
        KfeLightningLiquidityService disabled = liquidity(0);
        if (condition == ReadAdmission.MISSING) {
            liquidity = liquidity(500);
        } else {
            disabled.setMaintenanceGuard(guard);
            if (condition == ReadAdmission.DRAINING) {
                rejectDrain();
            } else {
                doThrow(new IllegalStateException("synthetic outage")).when(store).admit(anyString());
            }
        }
        assertThat(liquidity.isLive()).isTrue();
        assertThat(liquidity.outboundCapacitySats()).isEqualTo(1_000);
        assertThat(liquidity.heldReservationSats()).isEqualTo(200);
        assertThat(liquidity.freeOutboundCapacitySats()).isEqualTo(800);
        assertThat(liquidity.canCoverOutbound(800)).isTrue();
        assertThat(liquidity.canCoverOutbound(801)).isFalse();
        assertThat(disabled.circuitBreakerOpen()).isFalse();
        verifyNoInteractions(store, signals, signalStore, events, hashes, mapper, logger);
        verify(reservations, never()).acquirePoolLock(anyLong());
        verify(reservations, never()).save(any());
        verify(reservations, never()).saveAndFlush(any());
    }

    @Test
    void disabledBreakerAndNullFinalizationNeedNoProvidersOrAdmission() {
        KfeLightningLiquidityService disabled = liquidity(0);
        assertThat(disabled.circuitBreakerOpen()).isFalse();
        disabled.consumeForTransaction(null);
        disabled.releaseForTransaction(null);
        noEffects();
        verifyNoInteractions(store);
    }

    @Test
    void reservationAdmitsBeforeIdempotencyLookupAndKeepsPoolLockKey() {
        activeRows(Root.RESERVE);
        liquidity.reserveForTransaction(transactionId, 100);
        var order = inOrder(store, reservations, clients, client);
        order.verify(store).admit("lightning-liquidity.reserve");
        order.verify(reservations).findByTransactionId(transactionId);
        order.verify(reservations).acquirePoolLock(0x4B46454C4E4C5154L);
        order.verify(clients).getIfAvailable();
        order.verify(client).getLocalBalance();
        order.verify(reservations).sumAmountByStatus(KfeLiquidityReservationStatus.HELD);
        order.verify(reservations).saveAndFlush(argThat(row -> row.getTransactionId().equals(transactionId)
                && row.getAmountSats() == 100 && row.getStatus() == KfeLiquidityReservationStatus.HELD));
    }

    @Test
    void existingReservationRetainsIdempotencyWithoutNewLockOrProbe() {
        when(reservations.findByTransactionId(transactionId)).thenReturn(Optional.of(held));
        liquidity.reserveForTransaction(transactionId, 100);
        verify(reservations, never()).acquirePoolLock(anyLong());
        verify(reservations, never()).saveAndFlush(any());
        verifyNoInteractions(clients, client);
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest @EnumSource(value = KfeLiquidityReservationStatus.class, names = {"CONSUMED", "RELEASED"})
    void terminalReservationsAreNotRewritten(KfeLiquidityReservationStatus status) {
        when(reservations.findByTransactionId(transactionId)).thenReturn(Optional.of(held));
        held.setStatus(status);
        liquidity.consumeForTransaction(transactionId);
        liquidity.releaseForTransaction(transactionId);
        assertThat(held.getStatus()).isEqualTo(status);
        verify(reservations, never()).save(any());
        verify(store, times(2)).resolve(admission.id(), false);
    }

    @Test
    void insertFailureWithoutExistingTransactionStillPropagates() {
        activeRows(Root.RESERVE);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("synthetic insert failure");
        when(reservations.saveAndFlush(any())).thenThrow(failure);
        assertThatThrownBy(() -> liquidity.reserveForTransaction(transactionId, 100)).isSameAs(failure);
        verify(reservations, times(2)).findByTransactionId(transactionId);
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void caughtDuplicateInsertStillBlocksAfterParentCommit() {
        activeRows(Root.RESERVE);
        when(reservations.findByTransactionId(transactionId)).thenReturn(Optional.empty(), Optional.of(held));
        when(reservations.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("synthetic duplicate"));
        beginTransaction();
        guard.executeMutation("synthetic.parent", () -> {
            liquidity.reserveForTransaction(transactionId, 100);
            return null;
        });
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(reservations, times(2)).findByTransactionId(transactionId);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
    }

    @Test
    void caughtNodeFailureTripsBreakerAndRemainsUncertainAfterCommit() {
        activeRows(Root.BREAKER);
        when(client.getLocalBalance()).thenThrow(new IllegalStateException("synthetic probe failure"));
        beginTransaction();
        guard.executeMutation("synthetic.parent", () -> {
            assertThat(liquidity.circuitBreakerOpen()).isTrue();
            return null;
        });
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(store).resolve(admission.id(), false);
        verify(reservations, never()).sumAmountByStatus(any());
    }

    @Test
    void rejectedBreakerEvaluationDoesNotProbeOrLatchBeforeActiveEvaluation() {
        activeRows(Root.BREAKER);
        when(client.getLocalBalance()).thenReturn(400L);
        rejectDrain();
        assertThatThrownBy(liquidity::circuitBreakerOpen).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        verifyNoInteractions(clients, client, reservations, signals, signalStore);
        doReturn(admission).when(store).admit(anyString());
        // Between floor (500) and recovery (600): a rejection must not have latched.
        when(client.getLocalBalance()).thenReturn(550L);
        assertThat(liquidity.circuitBreakerOpen()).isFalse();
    }

    @Test
    void sustainedStressAndRecoveryKeepExistingBreakerPolicy() {
        activeRows(Root.BREAKER);
        when(signals.getIfAvailable()).thenReturn(signalStore);
        when(signalStore.liquidityRejectsInWindow()).thenReturn(10L);
        assertThat(liquidity.circuitBreakerOpen()).isTrue();
        when(signalStore.liquidityRejectsInWindow()).thenReturn(0L);
        when(client.getLocalBalance()).thenReturn(550L);
        assertThat(liquidity.circuitBreakerOpen()).isTrue();
        when(client.getLocalBalance()).thenReturn(600L);
        assertThat(liquidity.circuitBreakerOpen()).isFalse();
    }

    @ParameterizedTest @EnumSource(value = Root.class, names = {"AUDIT", "AUDIT_NEW"})
    void serializationFallbackDoesNotClearAdmissionOnCommit(Root root) throws Exception {
        activeRows(root);
        doThrow(new JsonProcessingException("synthetic serialization failure") { })
                .when(mapper).writeValueAsString(any());
        beginTransaction();
        invoke(root);
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(events).save(argThat(event -> event.getPayloadHash().equals(new KfeHashService().sha256("{}"))));
        verify(store).resolve(admission.id(), false);
    }

    @ParameterizedTest @EnumSource(value = Root.class, names = {"AUDIT", "AUDIT_NEW"})
    void structuredLoggingFailureLeavesParentUncertainEvenWhenCallerSwallowsIt(Root root) {
        activeRows(root);
        doThrow(new IllegalStateException("synthetic logging failure"))
                .when(logger).persisted(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        beginTransaction();
        guard.executeMutation("synthetic.parent", () -> {
            assertThatThrownBy(() -> invoke(root)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("synthetic logging failure");
            return null;
        });
        finishTransaction(TransactionSynchronization.STATUS_COMMITTED);
        verify(events).save(any());
        verify(store).resolve(admission.id(), false);
    }

    @Test
    void auditChainStillHashesSanitizedPayloadUnderAppenderLockThenLogs() throws Exception {
        activeRows(Root.AUDIT);
        Map<String, Object> payload = Map.of("token", "synthetic-secret", "reason", "settled for user@example.com");
        Map<String, Object> sanitized = AuditEventPayloadSanitizer.sanitize(payload);
        String payloadHash = new KfeHashService().sha256(new ObjectMapper().writeValueAsString(sanitized));
        KfeAuditLogEntity prior = new KfeAuditLogEntity();
        prior.setEventHash("a".repeat(64));
        when(events.findTopByOrderBySequenceNumberDesc()).thenReturn(Optional.of(prior));
        KfeAuditLogEntity saved = audit.record("KFE_SETTLEMENT_COMPLETED", transactionId, walletId,
                null, null, payload);
        assertThat(saved.getPayloadHash()).isEqualTo(payloadHash);
        assertThat(saved.getPreviousHash()).isEqualTo(prior.getEventHash());
        assertThat(saved.getEventHash()).isEqualTo(new KfeHashService().sha256(prior.getEventHash() + "|"
                + payloadHash + "|KFE_SETTLEMENT_COMPLETED|" + transactionId + "|" + walletId + "|null"));
        var order = inOrder(store, hashes, events, logger);
        order.verify(store).admit("audit.record");
        order.verify(hashes).sha256(new ObjectMapper().writeValueAsString(sanitized));
        order.verify(events).lockAuditAppender();
        order.verify(events).findTopByOrderBySequenceNumberDesc();
        order.verify(hashes).sha256(anyString());
        order.verify(events).save(saved);
        order.verify(logger).persisted(any(), any(), any(), eq(transactionId), eq(walletId),
                isNull(), isNull(), eq(payloadHash), eq(saved.getEventHash()), eq(sanitized));
    }

    @Test
    void transactionPropagationContractsRemainDeclared() throws Exception {
        for (String name : new String[]{"reserveForTransaction", "consumeForTransaction", "releaseForTransaction"}) {
            var method = name.equals("reserveForTransaction")
                    ? KfeLightningLiquidityService.class.getMethod(name, UUID.class, long.class)
                    : KfeLightningLiquidityService.class.getMethod(name, UUID.class);
            assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRED);
        }
        Class<?>[] signature = {String.class, UUID.class, UUID.class,
                com.kerosene.kfe.model.KfeTransactionStatus.class,
                com.kerosene.kfe.model.KfeTransactionStatus.class, Map.class};
        assertThat(KfeAuditLogService.class.getMethod("record", signature)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRED);
        assertThat(KfeAuditLogService.class.getMethod("recordInNewTransaction", signature)
                .getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    private void invoke(Root root) {
        switch (root) {
            case RESERVE -> liquidity.reserveForTransaction(transactionId, 100);
            case CONSUME -> liquidity.consumeForTransaction(transactionId);
            case RELEASE -> liquidity.releaseForTransaction(transactionId);
            case BREAKER -> assertThat(liquidity.circuitBreakerOpen()).isFalse();
            case AUDIT -> audit.record("KFE_SETTLEMENT_COMPLETED", transactionId, walletId, null, null, Map.of());
            case AUDIT_NEW -> audit.recordInNewTransaction("KFE_SETTLEMENT_COMPLETED", transactionId, walletId,
                    null, null, Map.of());
        }
    }

    private void activeRows(Root root) {
        lenient().when(clients.getIfAvailable()).thenReturn(client);
        lenient().when(client.getLocalBalance()).thenReturn(1_000L);
        lenient().when(reservations.findByTransactionId(transactionId)).thenReturn(
                root == Root.RESERVE ? Optional.empty() : Optional.of(held));
        lenient().when(events.findTopByOrderBySequenceNumberDesc()).thenReturn(Optional.empty());
        lenient().when(events.save(any())).thenAnswer(call -> call.getArgument(0));
    }

    private void assertActiveResult(Root root) {
        switch (root) {
            case RESERVE -> verify(reservations).saveAndFlush(argThat(row -> row.getAmountSats() == 100
                    && row.getStatus() == KfeLiquidityReservationStatus.HELD));
            case CONSUME -> {
                assertThat(held.getStatus()).isEqualTo(KfeLiquidityReservationStatus.CONSUMED);
                verify(reservations).save(held);
            }
            case RELEASE -> {
                assertThat(held.getStatus()).isEqualTo(KfeLiquidityReservationStatus.RELEASED);
                verify(reservations).save(held);
            }
            case BREAKER -> verify(client).getLocalBalance();
            case AUDIT, AUDIT_NEW -> verify(events).save(argThat(event ->
                    event.getPreviousHash().equals("0".repeat(64))
                            && event.getEventType().equals("KFE_SETTLEMENT_COMPLETED")));
        }
    }

    private void rejectDrain() {
        doThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain")).when(store).admit(anyString());
    }

    private void noEffects() {
        verifyNoInteractions(clients, gateways, signals, client, gateway, signalStore,
                reservations, events, hashes, mapper, logger);
        assertThat(held.getStatus()).isEqualTo(KfeLiquidityReservationStatus.HELD);
        assertThat(held.getAmountSats()).isEqualTo(50);
    }

    private KfeLightningLiquidityService liquidity(long floor) {
        return new KfeLightningLiquidityService(clients, gateways, reservations, signals, 0, floor, 10);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider() { return mock(ObjectProvider.class); }

    private void beginTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private void finishTransaction(int state) {
        var callbacks = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clear();
        callbacks.forEach(callback -> callback.afterCompletion(state));
    }
}
