package source.kfe.service;

import org.junit.jupiter.api.Test;
import source.kfe.application.transaction.KfeBalanceMovementRecorder;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class KfeFeeSettlementServiceTest {

    private final KfeSystemWalletService systemWalletService = mock(KfeSystemWalletService.class);
    private final KfeBalanceService balanceService = mock(KfeBalanceService.class);
    private final KfeBalanceMovementRecorder movementRecorder = mock(KfeBalanceMovementRecorder.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private final KfeFeeSettlementService service = new KfeFeeSettlementService(
            systemWalletService,
            balanceService,
            movementRecorder,
            auditLogService);

    @Test
    void skipsTransactionsWithoutKeroseneFee() {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setKeroseneFeeSats(0L);

        service.creditKeroseneFee(tx);

        verify(systemWalletService, never()).requireProfitWalletId();
    }

    @Test
    void creditsKeroseneFeeToSystemProfitWallet() {
        UUID profitWalletId = UUID.randomUUID();
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setKeroseneFeeSats(900L);
        tx.setStatus(KfeTransactionStatus.SETTLED);
        org.mockito.Mockito.when(systemWalletService.requireProfitWalletId()).thenReturn(profitWalletId);

        service.creditKeroseneFee(tx);

        verify(balanceService).creditAvailable(profitWalletId, KfeSystemWalletService.ASSET_BTC, 900L);
        verify(movementRecorder).record(tx.getId(), profitWalletId, "CREDIT_KEROSENE_FEE", 900L, null, "AVAILABLE");
    }
}
