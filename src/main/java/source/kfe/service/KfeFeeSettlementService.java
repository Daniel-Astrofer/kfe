package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import source.kfe.application.transaction.KfeBalanceMovementRecorder;
import source.kfe.model.KfeTransactionEntity;

import java.util.Map;
import java.util.UUID;

@Service
public class KfeFeeSettlementService {

    private static final Logger log = LoggerFactory.getLogger(KfeFeeSettlementService.class);

    private final KfeSystemWalletService systemWalletService;
    private final KfeBalanceService balanceService;
    private final KfeBalanceMovementRecorder movementRecorder;
    private final KfeAuditLogService auditLogService;

    public KfeFeeSettlementService(
            KfeSystemWalletService systemWalletService,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfeAuditLogService auditLogService) {
        this.systemWalletService = systemWalletService;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.auditLogService = auditLogService;
    }

    public void creditKeroseneFee(KfeTransactionEntity tx) {
        if (tx == null || tx.getKeroseneFeeSats() <= 0L) {
            return;
        }

        UUID profitWalletId = systemWalletService.requireProfitWalletId();
        balanceService.creditAvailable(profitWalletId, KfeSystemWalletService.ASSET_BTC, tx.getKeroseneFeeSats());
        movementRecorder.record(
                tx.getId(),
                profitWalletId,
                "CREDIT_KEROSENE_FEE",
                tx.getKeroseneFeeSats(),
                null,
                "AVAILABLE");
        auditLogService.record(
                "KFE_KEROSENE_FEE_SETTLED",
                tx.getId(),
                profitWalletId,
                null,
                tx.getStatus(),
                Map.of(
                        "transactionId", tx.getId().toString(),
                        "profitWalletId", profitWalletId.toString(),
                        "keroseneFeeSats", tx.getKeroseneFeeSats()));
        log.info("KFE kerosene fee settled transactionId={} feeSats={}", tx.getId(), tx.getKeroseneFeeSats());
    }
}
