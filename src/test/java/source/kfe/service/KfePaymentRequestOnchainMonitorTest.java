package source.kfe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import source.common.financial.FinancialNotificationPort;
import source.kfe.application.transaction.KfeBalanceMovementRecorder;
import source.kfe.model.KfePaymentRequestEntity;
import source.kfe.model.KfePaymentRequestStatus;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.rail.BlockchainClient;
import source.kfe.repository.KfePaymentRequestRepository;
import source.kfe.repository.KfeTransactionRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfePaymentRequestOnchainMonitorTest {

    private final KfePaymentRequestRepository paymentRequestRepository = mock(KfePaymentRequestRepository.class);
    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final BlockchainClient blockchainClient = mock(BlockchainClient.class);
    private final KfePricingService pricingService = mock(KfePricingService.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRecorder movementRecorder = mock(KfeBalanceMovementRecorder.class);
    private final KfeFeeSettlementService feeSettlementService = mock(KfeFeeSettlementService.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private final KfeStatementService statementService = mock(KfeStatementService.class);
    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    private final FinancialNotificationPort notificationPort = mock(FinancialNotificationPort.class);
    private final KfePaymentRequestOnchainMonitor monitor = new KfePaymentRequestOnchainMonitor(
            paymentRequestRepository,
            transactionRepository,
            provider(blockchainClient),
            pricingService,
            balanceService,
            movementRecorder,
            feeSettlementService,
            auditLogService,
            statementService,
            dashboardPublisher,
            notificationPort,
            50,
            3);

    @Test
    void recordsObservedPaymentBeforeMinimumConfirmationsWithoutCrediting() throws Exception {
        KfePaymentRequestEntity request = paymentRequest(10_000L);
        when(paymentRequestRepository.findByStatusInAndRailOrderByCreatedAtAsc(
                eq(List.of(KfePaymentRequestStatus.OPEN, KfePaymentRequestStatus.EXPIRED)),
                eq(KfeRail.ONCHAIN),
                any()))
                .thenReturn(List.of(request));
        when(paymentRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(blockchainClient.getAddressTransactions(request.getAddress()))
                .thenReturn(jsonArray("""
                        [{"txid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","amount":0.0001,"confirmations":2}]
                        """));
        when(transactionRepository.findByProviderReferenceForUpdate(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(List.of());
        when(pricingService.quote(KfeRail.ONCHAIN, source.kfe.model.KfeDirection.INBOUND, 10_000L, 0L))
                .thenReturn(new KfePricingService.Quote(10_000L, 9_910L, 0L, 0L, 90L));
        when(transactionRepository.save(any(KfeTransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        monitor.reconcileOpenOnchainPaymentRequests();

        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.OPEN);
        verify(balanceService, never()).creditAvailable(any(), any(), anyLong());
        verify(transactionRepository).save(any(KfeTransactionEntity.class));
        verify(statementService).recordUserStatementIfAbsent(
                eq(request.getUserId()),
                eq(request.getWalletId()),
                any(KfeTransactionEntity.class),
                anyMap());
    }

    @Test
    void settlesConfirmedPaymentRequestAndCreditsWallet() throws Exception {
        KfePaymentRequestEntity request = paymentRequest(10_000L);
        UUID requestId = request.getId();
        when(paymentRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        when(transactionRepository.findByProviderReferenceForUpdate(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(List.of());
        when(pricingService.quote(KfeRail.ONCHAIN, source.kfe.model.KfeDirection.INBOUND, 10_000L, 0L))
                .thenReturn(new KfePricingService.Quote(10_000L, 9_910L, 0L, 0L, 90L));
        when(transactionRepository.save(any(KfeTransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        monitor.settlePaymentRequest(requestId, new KfePaymentRequestOnchainMonitor.ObservedPayment(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                10_000L,
                3,
                "{}"));

        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
        assertThat(request.getPaidTransactionId()).isNotNull();
        verify(balanceService).creditAvailable(request.getWalletId(), "BTC", 9_910L);
        verify(feeSettlementService).creditKeroseneFee(any(KfeTransactionEntity.class));
        verify(notificationPort).notifyPaymentRequestDepositConfirmed(
                eq(request.getUserId()),
                any(UUID.class),
                eq(request.getId()),
                eq(request.getPublicId()),
                eq(request.getWalletId()),
                eq("ONCHAIN"),
                eq(9_910L));
    }

    @Test
    void settlesExpiredPaymentRequestWhenOnchainPaymentWasAlreadyObserved() {
        KfePaymentRequestEntity request = paymentRequest(10_000L);
        request.setStatus(KfePaymentRequestStatus.EXPIRED);
        UUID requestId = request.getId();
        when(paymentRequestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
        when(transactionRepository.findByProviderReferenceForUpdate(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(List.of());
        when(pricingService.quote(KfeRail.ONCHAIN, source.kfe.model.KfeDirection.INBOUND, 10_000L, 0L))
                .thenReturn(new KfePricingService.Quote(10_000L, 9_910L, 0L, 0L, 90L));
        when(transactionRepository.save(any(KfeTransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        monitor.settlePaymentRequest(requestId, new KfePaymentRequestOnchainMonitor.ObservedPayment(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                10_000L,
                3,
                "{}"));

        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
        assertThat(request.getPaidTransactionId()).isNotNull();
        verify(balanceService).creditAvailable(request.getWalletId(), "BTC", 9_910L);
    }

    @Test
    void promotesObservedTransactionToSettledWithoutCreatingDuplicate() {
        KfePaymentRequestEntity request = paymentRequest(10_000L);
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(request.getUserId());
        tx.setIdempotencyKey("payment-request:" + request.getId() + ":aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(source.kfe.model.KfeDirection.INBOUND);
        tx.setDestinationWalletId(request.getWalletId());
        tx.setProviderReference("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        tx.setBlockchainTxid("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        tx.setStatus(KfeTransactionStatus.VALIDATING);
        tx.setConfirmations(1);

        when(paymentRequestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
        when(transactionRepository.findByProviderReferenceForUpdate(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")).thenReturn(List.of(tx));
        when(pricingService.quote(KfeRail.ONCHAIN, source.kfe.model.KfeDirection.INBOUND, 10_000L, 0L))
                .thenReturn(new KfePricingService.Quote(10_000L, 9_910L, 0L, 0L, 90L));
        when(transactionRepository.save(any(KfeTransactionEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        monitor.settlePaymentRequest(request.getId(), new KfePaymentRequestOnchainMonitor.ObservedPayment(
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                10_000L,
                3,
                "{}"));

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
        assertThat(tx.getConfirmations()).isEqualTo(3);
        assertThat(request.getStatus()).isEqualTo(KfePaymentRequestStatus.PAID);
        verify(balanceService).creditAvailable(request.getWalletId(), "BTC", 9_910L);
    }

    private KfePaymentRequestEntity paymentRequest(Long amountSats) {
        KfePaymentRequestEntity request = new KfePaymentRequestEntity();
        request.setPublicId("public-id");
        request.setUserId(42L);
        request.setWalletId(UUID.randomUUID());
        request.setAddressId(UUID.randomUUID());
        request.setAddress("tb1qpaymentrequest");
        request.setRail(KfeRail.ONCHAIN);
        request.setStatus(KfePaymentRequestStatus.OPEN);
        request.setAmountSats(amountSats);
        return request;
    }

    private JsonNode jsonArray(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    private ObjectProvider<BlockchainClient> provider(BlockchainClient client) {
        return new ObjectProvider<>() {
            @Override
            public BlockchainClient getObject(Object... args) {
                return client;
            }

            @Override
            public BlockchainClient getIfAvailable() {
                return client;
            }

            @Override
            public BlockchainClient getIfUnique() {
                return client;
            }

            @Override
            public BlockchainClient getObject() {
                return client;
            }
        };
    }
}
