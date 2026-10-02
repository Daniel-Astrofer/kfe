package com.kerosene.kfe.service;

import com.kerosene.common.financial.FinancialNotificationPort;
import com.kerosene.kfe.application.transaction.KfeBalanceMovementRecorder;
import com.kerosene.kfe.config.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.maintenance.*;
import com.kerosene.kfe.model.*;
import com.kerosene.kfe.rail.*;
import com.kerosene.kfe.repository.*;
import com.kerosene.kfe.webhook.KfeWebhookDeliveryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KfePaymentRequestMonitorMaintenanceTest {
    private enum Root { STREAM, LIGHTNING_PROBE, FAIL, EXPIRE, LIGHTNING_SETTLE,
        EXPIRED_SETTLED, INSPECT, ONCHAIN_OBSERVE, ONCHAIN_SETTLE }
    private final KfeMaintenanceStore store = mock(KfeMaintenanceStore.class);
    private final KfeMaintenanceService guard = new KfeMaintenanceService(store);
    private final KfeMaintenanceStore.Admission admission = new KfeMaintenanceStore.Admission(UUID.randomUUID(), 0);
    private final KfePaymentRequestRepository requests = mock(KfePaymentRequestRepository.class);
    private final KfeTransactionRepository transactions = mock(KfeTransactionRepository.class);
    private final KfeWalletRepository wallets = mock(KfeWalletRepository.class);
    private final KfeBalanceMovementRepository movements = mock(KfeBalanceMovementRepository.class);
    private final LightningInvoiceGateway lightning = mock(LightningInvoiceGateway.class);
    private final BlockchainClient chain = mock(BlockchainClient.class);
    private final KfePricingService pricing = mock(KfePricingService.class);
    private final KfeBalanceService balances = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRecorder recorder = mock(KfeBalanceMovementRecorder.class);
    private final KfeFeeSettlementService fees = mock(KfeFeeSettlementService.class);
    private final KfeAuditLogService audit = mock(KfeAuditLogService.class);
    private final KfeStatementService statements = mock(KfeStatementService.class);
    private final KfeResponseMapper mapper = mock(KfeResponseMapper.class);
    private final KfeDashboardPublisher dashboard = mock(KfeDashboardPublisher.class);
    private final FinancialNotificationPort notifications = mock(FinancialNotificationPort.class);
    private final KfeWebhookDeliveryService webhooks = mock(KfeWebhookDeliveryService.class);
    private final KfePaymentRequestLightningMonitor self = mock(KfePaymentRequestLightningMonitor.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final KfePaymentRequestEntity request = request();
    private KfePaymentRequestLightningMonitor ln;
    private KfePaymentRequestOnchainMonitor btc;

    @BeforeEach void construct() {
        ln = new KfePaymentRequestLightningMonitor(requests, transactions, lightning, pricing,
                balances, recorder, fees, audit, statements, mapper, dashboard, true, 25, self, webhooks, notifications);
        btc = new KfePaymentRequestOnchainMonitor(requests, transactions, wallets, movements,
                provider(chain), pricing, balances, recorder, fees, audit, statements, mapper,
                dashboard, notifications, transaction, provider(null), provider(null), 50,
                new KfeBitcoinFinalityPolicy());
        ln.setMaintenanceGuard(guard); btc.setMaintenanceGuard(guard);
        when(store.admit(anyString())).thenReturn(admission);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void drainingRejectsEveryRootBeforeEffectsOrCursorChanges(Root root) {
        draining(); rejected(root); noEffects(); zeroCursors();
        verify(store, never()).resolve(any(), anyBoolean());
    }

    @ParameterizedTest @EnumSource(Root.class)
    void uninjectedConstructionRemainsUnavailable(Root root) {
        ln.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        btc.setMaintenanceGuard(KfeMaintenanceGuard.unavailable());
        rejected(root); noEffects(); zeroCursors(); verifyNoInteractions(store);
    }

    @ParameterizedTest @EnumSource(Root.class)
    void admissionStorageOutageCannotStartFinancialWork(Root root) {
        when(store.admit(anyString())).thenThrow(new IllegalStateException("synthetic storage outage"));
        rejected(root); noEffects(); zeroCursors();
    }

    @Test void pollingPausesBeforeProviderEffectsAndDoesNotTryEveryRow() {
        when(lightning.isLive()).thenReturn(true);
        when(requests.findByStatusAndRailOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of(request, request));
        when(requests.findByStatusInAndRailOrderByCreatedAtAsc(any(), any(), any())).thenReturn(List.of(request, request));
        draining();
        ln.reconcileOpenLightningPaymentRequests(); btc.reconcileOpenOnchainPaymentRequests();
        verify(store, times(2)).admit(anyString());
        verify(lightning, never()).getLightningInvoiceStatus(any());
        verifyNoInteractions(chain, balances, transactions, recorder, self, transaction, notifications);
        verify(requests, never()).save(any()); zeroCursors();
    }

    @Test void streamUsesTransactionalProxyAndDoesNotAcknowledgeBeforeReturn() {
        when(requests.findFirstByPaymentHashIgnoreCase("synthetic-hash")).thenReturn(Optional.of(request));
        doAnswer(call -> { zeroCursors(); verify(store, never()).resolve(any(), anyBoolean()); return null; })
                .when(self).settleSettledInvoice(request.getId(), 100L, "synthetic-payload");
        ln.handleStreamInvoiceUpdate(status("SETTLED", 100L, 7, 5));
        verify(self).settleSettledInvoice(request.getId(), 100L, "synthetic-payload");
        verifyNoInteractions(transactions, balances, recorder, fees, statements, dashboard);
        assertThat(ln.getLastAddIndex()).isEqualTo(7); assertThat(ln.getLastSettleIndex()).isEqualTo(5);
        verify(store).resolve(admission.id(), false);
    }

    @Test void failedSettlementDoesNotAdvanceIndicesOrManufactureCompletion() {
        when(requests.findFirstByPaymentHashIgnoreCase("synthetic-hash")).thenReturn(Optional.of(request));
        doThrow(new IllegalStateException("synthetic commit failure"))
                .when(self).settleSettledInvoice(request.getId(), 100L, "synthetic-payload");
        assertThatThrownBy(() -> ln.handleStreamInvoiceUpdate(status("SETTLED", 100L, 9, 8)))
                .isInstanceOf(IllegalStateException.class);
        zeroCursors(); verify(store).resolve(admission.id(), false);
        verifyNoInteractions(balances, transactions, recorder);
    }

    @Test void realSpringProxyCommitsBeforeStreamCursorAdvances() {
        var manager = new SyntheticTransactionManager(false);
        wireTransactionalSettlement(manager);
        ln.handleStreamInvoiceUpdate(status("SETTLED", 100L, 7, 5));
        assertThat(manager.commits).isEqualTo(1);
        assertThat(ln.getLastAddIndex()).isEqualTo(7);
        assertThat(ln.getLastSettleIndex()).isEqualTo(5);
        verify(store).resolve(admission.id(), false);
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test void realSpringProxyCommitFailureLeavesCursorUnchangedAndAdmissionUncertain() {
        var manager = new SyntheticTransactionManager(true);
        wireTransactionalSettlement(manager);
        assertThatThrownBy(() -> ln.handleStreamInvoiceUpdate(status("SETTLED", 100L, 7, 5)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("synthetic commit");
        zeroCursors(); assertThat(manager.commits).isEqualTo(1);
        verify(store).resolve(admission.id(), false);
        verify(store, never()).resolve(any(), eq(true));
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    private void wireTransactionalSettlement(SyntheticTransactionManager manager) {
        var factory = new ProxyFactory(ln);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        var proxy = (KfePaymentRequestLightningMonitor) factory.getProxy();
        when(requests.findFirstByPaymentHashIgnoreCase("synthetic-hash")).thenReturn(Optional.of(request));
        when(requests.findByIdForUpdate(request.getId())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isTrue();
            zeroCursors();
            return Optional.empty(); // Boundary proof only: no invented settlement or balance mutation.
        });
        doAnswer(call -> {
            proxy.settleSettledInvoice(call.getArgument(0), call.getArgument(1), call.getArgument(2));
            return null;
        }).when(self).settleSettledInvoice(request.getId(), 100L, "synthetic-payload");
    }

    private static final class SyntheticTransactionManager extends AbstractPlatformTransactionManager {
        private final boolean failCommit;
        private int commits;
        SyntheticTransactionManager(boolean failCommit) { this.failCommit = failCommit; }
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object tx, TransactionDefinition definition) { }
        protected void doCommit(DefaultTransactionStatus status) {
            commits++;
            if (failCommit) throw new IllegalStateException("synthetic commit failure");
        }
        protected void doRollback(DefaultTransactionStatus status) { }
    }

    @Test void inFlightAndOutOfOrderTelemetryStillRequiresAdmissionAndNeverDecrements() {
        ln.handleStreamInvoiceUpdate(status("IN_FLIGHT", null, 7, 5));
        ln.handleStreamInvoiceUpdate(status("IN_FLIGHT", null, 3, 2));
        assertThat(ln.getLastAddIndex()).isEqualTo(7); assertThat(ln.getLastSettleIndex()).isEqualTo(5);
        verifyNoInteractions(requests, transactions, self, balances);
        draining(); assertThatThrownBy(() -> ln.handleStreamInvoiceUpdate(status("IN_FLIGHT", null, 20, 20)))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        assertThat(ln.getLastAddIndex()).isEqualTo(7); assertThat(ln.getLastSettleIndex()).isEqualTo(5);
        verify(store, never()).resolve(any(), eq(true));
    }

    @Test void alreadyAdmittedParentCanFinishNestedLocalWriteButCannotAuthorizeNewCallback() {
        guard.executeMutation("synthetic.parent", () -> {
            draining(); ln.markFailed(request.getId(), "FAILED", "synthetic");
            return true;
        });
        verify(store, times(1)).admit(anyString()); verify(requests).findByIdForUpdate(request.getId());
        verify(store).resolve(admission.id(), true);
        assertThatThrownBy(() -> ln.handleStreamInvoiceUpdate(status("SETTLED", 100L, 20, 20)))
                .isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class);
        zeroCursors();
    }

    @Test void nullCallbackIsNotAnExecutionAndCoverageRemainsUnknown() {
        ln.handleStreamInvoiceUpdate(null); verifyNoInteractions(store); zeroCursors();
        when(store.observe()).thenReturn(new KfeMaintenanceStore.Observation(
                new KfeMaintenanceStore.Control(KfeMaintenanceGuard.Mode.DRAINING, "synthetic", 1),
                java.time.Instant.now(), Map.of()));
        assertThat(guard.status().safeToUpdate()).isFalse();
        assertThat(guard.status().blockers()).containsEntry("callbackCoverageUnknown", 1L);
    }

    private void rejected(Root root) { assertThatThrownBy(() -> invoke(root)).isInstanceOf(KfeMaintenanceGuard.MaintenanceException.class); }
    private void draining() { when(store.admit(anyString())).thenThrow(new KfeMaintenanceGuard.MaintenanceException(503, "synthetic drain")); }
    private void zeroCursors() { assertThat(ln.getLastAddIndex()).isZero(); assertThat(ln.getLastSettleIndex()).isZero(); }
    private void noEffects() { verifyNoInteractions(requests, transactions, wallets, movements, lightning, chain, pricing, balances, recorder, fees, audit, statements, mapper, dashboard, notifications, webhooks, self, transaction); }
    private void invoke(Root root) {
        switch (root) {
            case STREAM -> ln.handleStreamInvoiceUpdate(status("SETTLED", 100L, 7, 5));
            case LIGHTNING_PROBE -> ln.probeAndMaybeSettle(request);
            case FAIL -> ln.markFailed(request.getId(), "FAILED", "synthetic");
            case EXPIRE -> ln.expireRequest(request.getId());
            case LIGHTNING_SETTLE -> ln.settleSettledInvoice(request.getId(), 100, "synthetic");
            case EXPIRED_SETTLED -> ln.reconcileExpiredButSettled(request.getId(), 100, "synthetic");
            case INSPECT -> ln.inspect(request.getId());
            case ONCHAIN_OBSERVE -> btc.observePaymentRequest(request.getId(), new KfePaymentRequestOnchainMonitor.ObservedPayment("synthetic-tx", 100, 1, "synthetic"));
            case ONCHAIN_SETTLE -> btc.settlePaymentRequest(request.getId(), new KfePaymentRequestOnchainMonitor.ObservedPayment("synthetic-tx", 100, 3, "synthetic"));
        }
    }
    private static CustodyGateway.IncomingLightningInvoiceStatus status(String status, Long sats, long add, long settle) {
        return new CustodyGateway.IncomingLightningInvoiceStatus(status, sats, null, "synthetic-payload", "synthetic-hash", add, settle);
    }
    private static KfePaymentRequestEntity request() {
        var row = new KfePaymentRequestEntity(); row.setUserId(7L); row.setWalletId(UUID.randomUUID());
        row.setStatus(KfePaymentRequestStatus.OPEN); row.setRail(KfeRail.LIGHTNING);
        row.setPaymentHash("synthetic-hash"); row.setAddress("synthetic-address");
        row.setPublicId("synthetic-public-id"); row.setAmountSats(100L); return row;
    }
    private static <T> ObjectProvider<T> provider(T value) {
        @SuppressWarnings("unchecked") ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value); return provider;
    }
}
