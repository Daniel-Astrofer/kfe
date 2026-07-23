package com.kerosene.kfe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfePaymentRequestEntity;
import com.kerosene.kfe.model.KfePaymentRequestStatus;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.rail.LightningInvoiceGateway;
import com.kerosene.kfe.repository.KfePaymentRequestRepository;
import com.kerosene.kfe.repository.KfeTransactionRepository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KfeTransactionCancellationServiceTest {

    @Mock
    private KfeTransactionRepository transactionRepository;
    @Mock
    private KfePaymentRequestRepository paymentRequestRepository;
    @Mock
    private KfeBalanceService balanceService;
    @Mock
    private KfeLightningLiquidityService lightningLiquidityService;
    @Mock
    private KfeStatementService statementService;
    @Mock
    private KfeResponseMapper responseMapper;
    @Mock
    private KfeDashboardPublisher dashboardPublisher;
    @Mock
    private KfeAuditLogService auditLogService;
    @Mock
    private LightningInvoiceGateway lightningInvoiceGateway;

    private KfeTransactionCancellationService service;

    @BeforeEach
    void setUp() {
        service = new KfeTransactionCancellationService(
                transactionRepository,
                paymentRequestRepository,
                balanceService,
                lightningLiquidityService,
                statementService,
                responseMapper,
                dashboardPublisher,
                auditLogService,
                lightningInvoiceGateway);
    }

    @Test
    void openPaymentRequestIsCancellableViaHints() {
        UUID txId = UUID.randomUUID();
        UUID prId = UUID.randomUUID();
        KfeTransactionEntity tx = baseTx(txId, KfeTransactionStatus.VALIDATING);
        tx.setIdempotencyKey("payment-request:" + prId + ":txid");

        KfePaymentRequestEntity pr = paymentRequest(prId, KfePaymentRequestStatus.OPEN);

        when(paymentRequestRepository.findByPaidTransactionIdAndUserId(txId, 7L)).thenReturn(Optional.empty());
        when(paymentRequestRepository.findByIdAndUserId(prId, 7L)).thenReturn(Optional.of(pr));

        var hints = service.hintsFor(tx, 7L);
        assertThat(hints.cancellable()).isTrue();
        assertThat(hints.cancelTarget()).isEqualTo("PAYMENT_REQUEST");
        assertThat(hints.paymentRequestId()).isEqualTo(prId);
    }

    @Test
    void settledTransactionIsNotCancellable() {
        KfeTransactionEntity tx = baseTx(UUID.randomUUID(), KfeTransactionStatus.SETTLED);
        when(paymentRequestRepository.findByPaidTransactionIdAndUserId(tx.getId(), 7L))
                .thenReturn(Optional.empty());

        var hints = service.hintsFor(tx, 7L);
        assertThat(hints.cancellable()).isFalse();
    }

    @Test
    void cancelFailsPendingTransactionAndReleasesReserve() {
        UUID txId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeTransactionEntity tx = baseTx(txId, KfeTransactionStatus.LOCKED);
        tx.setSourceWalletId(walletId);
        tx.setTotalDebitSats(5_000L);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);

        when(transactionRepository.findParticipantVisibleById(
                eq(txId), eq(7L), eq(KfeRail.INTERNAL), eq(KfeDirection.INTERNAL)))
                .thenReturn(Optional.of(tx));
        when(paymentRequestRepository.findByPaidTransactionIdAndUserId(txId, 7L)).thenReturn(Optional.empty());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.findById(txId)).thenReturn(Optional.of(tx));
        when(responseMapper.buildDisplayPayload(any(), anyLong())).thenReturn(Map.of("status", "FAILED"));
        when(responseMapper.toTransactionResponse(any(), anyLong()))
                .thenAnswer(inv -> null);

        service.cancelTransaction(7L, txId);

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
        assertThat(tx.getFailureCode()).isEqualTo("USER_CANCELLED");
        verify(balanceService).releaseReserved(walletId, "BTC", 5_000L);
        verify(statementService).recordUserStatement(eq(7L), eq(walletId), eq(tx), anyMap());
        verify(dashboardPublisher).publishAfterCommit(7L);
    }

    @Test
    void cancelOpenPaymentRequestFailsRelatedValidatingTx() {
        UUID prId = UUID.randomUUID();
        UUID txId = UUID.randomUUID();
        KfePaymentRequestEntity pr = paymentRequest(prId, KfePaymentRequestStatus.OPEN);
        pr.setPublicId("abc");
        pr.setRail(KfeRail.LIGHTNING);
        pr.setPaymentHash("deadbeef");
        pr.setWalletId(UUID.randomUUID());

        KfeTransactionEntity related = baseTx(txId, KfeTransactionStatus.VALIDATING);
        related.setDestinationWalletId(pr.getWalletId());
        related.setDirection(KfeDirection.INBOUND);
        related.setIdempotencyKey("payment-request:" + prId + ":txid");

        when(paymentRequestRepository.findByIdAndUserId(prId, 7L)).thenReturn(Optional.of(pr));
        when(paymentRequestRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lightningInvoiceGateway.cancelLightningInvoice(any())).thenReturn(true);
        when(transactionRepository.findTopByIdempotencyKeyStartingWithOrderByCreatedAtDesc(
                "payment-request:" + prId + ":")).thenReturn(Optional.of(related));
        org.mockito.Mockito.lenient()
                .when(transactionRepository.findTop200ByUserIdOrderByCreatedAtDesc(7L))
                .thenReturn(List.of());
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(responseMapper.buildDisplayPayload(any(), anyLong())).thenReturn(Map.of("status", "FAILED"));

        KfePaymentRequestEntity cancelled = service.cancelPaymentRequest(7L, prId);

        assertThat(cancelled.getStatus()).isEqualTo(KfePaymentRequestStatus.CANCELLED);
        assertThat(related.getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
        assertThat(related.getFailureCode()).isEqualTo("USER_CANCELLED");
        verify(lightningInvoiceGateway).cancelLightningInvoice(any());
        verify(dashboardPublisher).publishAfterCommit(7L);
    }

    @Test
    void cancelThrowsWhenNotCancellable() {
        UUID txId = UUID.randomUUID();
        KfeTransactionEntity tx = baseTx(txId, KfeTransactionStatus.SETTLED);
        when(transactionRepository.findParticipantVisibleById(
                eq(txId), eq(7L), eq(KfeRail.INTERNAL), eq(KfeDirection.INTERNAL)))
                .thenReturn(Optional.of(tx));
        when(paymentRequestRepository.findByPaidTransactionIdAndUserId(txId, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cancelTransaction(7L, txId))
                .isInstanceOf(IllegalStateException.class);
    }

    private static KfeTransactionEntity baseTx(UUID id, KfeTransactionStatus status) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        setId(tx, id);
        tx.setUserId(7L);
        tx.setIdempotencyKey("idem-" + id);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.INBOUND);
        tx.setStatus(status);
        return tx;
    }

    private static KfePaymentRequestEntity paymentRequest(UUID id, KfePaymentRequestStatus status) {
        KfePaymentRequestEntity pr = new KfePaymentRequestEntity();
        setId(pr, id);
        pr.setUserId(7L);
        pr.setStatus(status);
        pr.setPublicId("pub123");
        pr.setRail(KfeRail.ONCHAIN);
        return pr;
    }

    private static void setId(Object entity, UUID id) {
        try {
            var field = entity.getClass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
