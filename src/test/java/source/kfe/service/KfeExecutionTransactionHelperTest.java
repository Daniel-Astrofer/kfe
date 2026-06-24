package source.kfe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import source.kfe.model.KfeExecutionOutboxEntity;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.repository.KfeBalanceMovementRepository;
import source.kfe.repository.KfeExecutionOutboxRepository;
import source.kfe.repository.KfeIdempotencyRepository;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.repository.KfeWalletRepository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class KfeExecutionTransactionHelperTest {

    private final KfeExecutionOutboxRepository outboxRepository = mock(KfeExecutionOutboxRepository.class);
    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final KfeWalletRepository walletRepository = mock(KfeWalletRepository.class);
    private final KfeIdempotencyRepository idempotencyRepository = mock(KfeIdempotencyRepository.class);
    private final KfeBalanceMovementRepository movementRepository = mock(KfeBalanceMovementRepository.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private final KfeStatementService statementService = mock(KfeStatementService.class);
    private final KfeDashboardPublisher dashboardPublisher = mock(KfeDashboardPublisher.class);
    private final KfeHashService hashService = mock(KfeHashService.class);

    private final KfeExecutionTransactionHelper helper = new KfeExecutionTransactionHelper(
            outboxRepository,
            transactionRepository,
            walletRepository,
            idempotencyRepository,
            movementRepository,
            balanceService,
            auditLogService,
            statementService,
            dashboardPublisher,
            hashService,
            new ObjectMapper());

    @Test
    void settleOutboundOnlyDispatchesOutboxWhenTransactionAlreadySettled() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        UUID walletId = UUID.randomUUID();
        KfeExecutionOutboxEntity outbox = claimedOutbox(transactionId);
        KfeTransactionEntity tx = mock(KfeTransactionEntity.class);
        when(tx.getStatus()).thenReturn(KfeTransactionStatus.SETTLED);
        when(tx.getProviderReference()).thenReturn("existing-provider-ref");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(tx));

        helper.settleOutbound(outboxId, transactionId, "provider", "new-provider-ref", "txid", 12L, walletId, "{}");

        assertThat(outbox.getStatus()).isEqualTo("DISPATCHED");
        assertThat(outbox.getProviderReference()).isEqualTo("new-provider-ref");
        assertThat(outbox.getDispatchedAt()).isNotNull();
        assertThat(outbox.getClaimedBy()).isNull();
        assertThat(outbox.getClaimedAt()).isNull();
        verifyNoTerminalSideEffects(tx);
        verify(outboxRepository).save(outbox);
    }

    @Test
    void markFinalFailureOnlyFinalizesOutboxWhenTransactionAlreadyFailed() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        KfeExecutionOutboxEntity outbox = claimedOutbox(transactionId);
        outbox.setAttempts(3);
        KfeTransactionEntity tx = mock(KfeTransactionEntity.class);
        when(tx.getStatus()).thenReturn(KfeTransactionStatus.FAILED);
        when(tx.getFailureCode()).thenReturn("PROVIDER_FINAL_FAILURE");
        when(tx.getFailureMessage()).thenReturn("provider rejected payment");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(tx));

        helper.markFinalFailure(outboxId, transactionId, "NEW_FAILURE", "should not replay");

        assertThat(outbox.getStatus()).isEqualTo("FAILED_FINAL");
        assertThat(outbox.getAttempts()).isEqualTo(3);
        assertThat(outbox.getLastError()).isEqualTo("PROVIDER_FINAL_FAILURE: provider rejected payment");
        assertThat(outbox.getNextAttemptAt()).isNull();
        assertThat(outbox.getClaimedBy()).isNull();
        assertThat(outbox.getClaimedAt()).isNull();
        verifyNoTerminalSideEffects(tx);
        verify(outboxRepository).save(outbox);
    }

    @Test
    void duplicateFinalFailureDoesNotReleaseReserveAgain() {
        UUID outboxId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();
        KfeExecutionOutboxEntity outbox = claimedOutbox(transactionId);
        KfeTransactionEntity tx = mock(KfeTransactionEntity.class);
        when(tx.getStatus()).thenReturn(KfeTransactionStatus.FAILED);
        when(tx.getFailureCode()).thenReturn("PROVIDER_FINAL_FAILURE");
        when(tx.getFailureMessage()).thenReturn("provider rejected payment");

        when(outboxRepository.findByIdForUpdate(outboxId)).thenReturn(Optional.of(outbox));
        when(transactionRepository.findByIdForUpdate(transactionId)).thenReturn(Optional.of(tx));

        helper.markFinalFailure(outboxId, transactionId, "PROVIDER_FINAL_FAILURE", "provider rejected payment");

        assertThat(outbox.getStatus()).isEqualTo("FAILED_FINAL");
        assertThat(outbox.getLastError()).isEqualTo("PROVIDER_FINAL_FAILURE: provider rejected payment");
        verify(balanceService, never()).releaseReserved(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong());
        verify(movementRepository, never()).save(org.mockito.ArgumentMatchers.any());
        verify(transactionRepository, never()).save(tx);
        verify(outboxRepository).save(outbox);
    }

    private KfeExecutionOutboxEntity claimedOutbox(UUID transactionId) {
        KfeExecutionOutboxEntity outbox = new KfeExecutionOutboxEntity();
        outbox.setTransactionId(transactionId);
        outbox.setOperation("ONCHAIN_OUTBOUND");
        outbox.setStatus("PROCESSING");
        outbox.setClaimedBy("worker");
        outbox.setClaimedAt(LocalDateTime.now());
        outbox.setPayloadHash("payload-hash");
        return outbox;
    }

    private void verifyNoTerminalSideEffects(KfeTransactionEntity tx) {
        verifyNoInteractions(
                balanceService,
                movementRepository,
                statementService,
                idempotencyRepository,
                auditLogService,
                dashboardPublisher);
        verify(transactionRepository, never()).save(tx);
    }
}
