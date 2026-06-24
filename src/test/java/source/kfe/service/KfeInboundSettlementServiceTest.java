package source.kfe.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import source.common.financial.FinancialNotificationPort;
import source.kfe.model.KfeExecutionOutboxEntity;
import source.kfe.model.KfeIdempotencyEntity;
import source.kfe.model.KfeIdempotencyId;
import source.kfe.model.KfeRail;

import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.repository.KfeBalanceMovementRepository;
import source.kfe.repository.KfeExecutionOutboxRepository;
import source.kfe.repository.KfeIdempotencyRepository;
import source.kfe.repository.KfeTransactionRepository;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class KfeInboundSettlementServiceTest {

    @Mock
    private KfeTransactionRepository transactionRepository;

    @Mock
    private KfeExecutionOutboxRepository outboxRepository;

    @Mock
    private KfeBalanceMovementRepository movementRepository;

    @Mock
    private KfeIdempotencyRepository idempotencyRepository;

    @Mock
    private KfeBalanceService balanceService;

    @Mock
    private KfeAuditLogService auditLogService;

    @Mock
    private KfeStatementService statementService;

    @Mock
    private KfeDashboardPublisher dashboardPublisher;

    @Mock
    private KfeHashService hashService;

    @Mock
    private FinancialNotificationPort notificationPort;

    private KfeInboundSettlementService service;

    @BeforeEach
    void setUp() {
        service = new KfeInboundSettlementService(
                transactionRepository,
                outboxRepository,
                movementRepository,
                idempotencyRepository,
                balanceService,
                auditLogService,
                statementService,
                dashboardPublisher,
                hashService,
                notificationPort
        );
    }

    @Test
    void settleReturnsFalseIfOutboxNotFound() {
        when(outboxRepository.findByIdForUpdate(any(UUID.class))).thenReturn(Optional.empty());

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                UUID.randomUUID(), UUID.randomUUID(), "provider", "ref", "netRef", 100L, 1, "raw"
        ));

        assertFalse(result);
    }

    @Test
    void settleFailsOutboxIfTransactionNotFound() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(any(UUID.class))).thenReturn(Optional.empty());

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                UUID.randomUUID(), outboxId, "provider", "ref", "netRef", 100L, 1, "raw"
        ));

        assertFalse(result);
        verify(outboxRepository).save(outbox);
        assertTrue(outbox.getStatus().equals("FAILED_FINAL"));
    }

    @Test
    void settleReturnsTrueIfTransactionAlreadySettled() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();

        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID txId = tx.getId();
        tx.setStatus(KfeTransactionStatus.SETTLED);

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "provider", "ref", "netRef", 100L, 1, "raw"
        ));

        assertTrue(result);
        verify(outboxRepository).save(outbox);
        assertTrue(outbox.getStatus().equals("DISPATCHED"));
    }

    @Test
    void settleReturnsFalseIfObservedAmountLessThanGrossAmount() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();

        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID txId = tx.getId();
        tx.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        tx.setDestinationWalletId(UUID.randomUUID());
        tx.setGrossAmountSats(1000L);

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "provider", "ref", "netRef", 900L, 1, "raw"
        ));

        assertFalse(result);
        verify(transactionRepository).save(tx);
        assertTrue(tx.getFailureCode().equals("INBOUND_AMOUNT_BELOW_EXPECTED"));
    }

    @Test
    void settleCompletesSuccessfullyForOnchain() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();

        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID txId = tx.getId();
        UUID destWalletId = UUID.randomUUID();
        tx.setUserId(99L);
        tx.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        tx.setDestinationWalletId(destWalletId);
        tx.setGrossAmountSats(1000L);
        tx.setReceiverAmountSats(1000L);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(source.kfe.model.KfeDirection.INBOUND);
        
        tx.setIdempotencyKey("idem123");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));
        when(hashService.sha256(anyString())).thenReturn("hashed");
        
        KfeIdempotencyEntity idemEntity = new KfeIdempotencyEntity();
        when(idempotencyRepository.findById(any(KfeIdempotencyId.class))).thenReturn(Optional.of(idemEntity));

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "provider", "ref", "netRef", 1000L, 3, "raw"
        ));

        assertTrue(result);

        verify(balanceService).creditAvailable(destWalletId, "BTC", 1000L);
        verify(transactionRepository).save(tx);
        verify(movementRepository).save(any());
        verify(auditLogService).record(anyString(), any(), any(), any(), any(), anyMap());
        verify(statementService).recordUserStatement(anyLong(), any(), any(), anyMap());
        verify(outboxRepository).save(outbox);
        verify(dashboardPublisher).publishAfterCommit(99L);
        
        assertTrue(tx.getStatus() == KfeTransactionStatus.SETTLED);
        org.junit.jupiter.api.Assertions.assertEquals("netRef", tx.getBlockchainTxid());
    }

    @Test
    void settleDoesNotCreditAgainWhenProviderReferenceAlreadySettled() {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        UUID outboxId = outbox.getId();

        KfeTransactionEntity tx = new KfeTransactionEntity();
        UUID txId = tx.getId();
        tx.setStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        tx.setDestinationWalletId(UUID.randomUUID());
        tx.setGrossAmountSats(1000L);
        tx.setReceiverAmountSats(1000L);

        KfeTransactionEntity settled = new KfeTransactionEntity();
        settled.setStatus(KfeTransactionStatus.SETTLED);
        settled.setProvider("provider");
        settled.setProviderReference("ref");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(txId)).thenReturn(Optional.of(tx));
        when(transactionRepository.findByProviderReferenceAndStatusForUpdate(
                "ref",
                KfeTransactionStatus.SETTLED)).thenReturn(java.util.List.of(settled));

        boolean result = service.settle(new KfeInboundSettlementService.InboundSettlementProof(
                txId, outboxId, "provider", "ref", "netRef", 1000L, 3, "raw"
        ));

        assertTrue(result);
        assertTrue(outbox.getStatus().equals("DISPATCHED"));
        verify(outboxRepository).save(outbox);
        verify(balanceService, never()).creditAvailable(any(), anyString(), anyLong());
        verify(movementRepository, never()).save(any());
        verify(transactionRepository, never()).save(tx);
        verify(auditLogService, never()).record(anyString(), any(), any(), any(), any(), anyMap());
        verify(statementService, never()).recordUserStatement(anyLong(), any(), any(), anyMap());
        verify(idempotencyRepository, never()).findById(any());
        verify(dashboardPublisher, never()).publishAfterCommit(anyLong());
    }

}
