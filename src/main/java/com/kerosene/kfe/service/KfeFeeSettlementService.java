package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import source.kfe.application.transaction.KfeBalanceMovementRecorder;
import source.kfe.application.transaction.KfeLedgerMovementTypes;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.repository.KfeBalanceMovementRepository;

import java.util.Map;
import java.util.UUID;

@Service
public class KfeFeeSettlementService {

    private static final Logger log = LoggerFactory.getLogger(KfeFeeSettlementService.class);
    private static final String MOVEMENT_TYPE = KfeLedgerMovementTypes.CREDIT_KEROSENE_FEE;

    private final KfeSystemWalletService systemWalletService;
    private final KfeBalanceService balanceService;
    private final KfeBalanceMovementRecorder movementRecorder;
    private final KfeBalanceMovementRepository movementRepository;
    private final KfeAuditLogService auditLogService;
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics;

    public KfeFeeSettlementService(
            KfeSystemWalletService systemWalletService,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfeBalanceMovementRepository movementRepository,
            KfeAuditLogService auditLogService,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics) {
        this.systemWalletService = systemWalletService;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.movementRepository = movementRepository;
        this.auditLogService = auditLogService;
        this.balanceMetrics = balanceMetrics;
    }

    public void creditKeroseneFee(KfeTransactionEntity tx) {
        if (tx == null || tx.getId() == null || tx.getKeroseneFeeSats() <= 0L) {
            return;
        }
        // Idempotent: dual inbound paths / retries must not inflate SYSTEM_PROFIT.
        if (movementRepository.existsByTransactionIdAndMovementType(tx.getId(), MOVEMENT_TYPE)) {
            log.debug(
                    "KFE kerosene fee already settled transactionId={} — skip",
                    tx.getId());
            recordFeeSkip();
            return;
        }

        UUID profitWalletId = systemWalletService.requireProfitWalletId();
        // Record movement first under unique index; only credit if row is new (race-safe).
        boolean wrote = movementRecorder.record(
                tx.getId(),
                profitWalletId,
                MOVEMENT_TYPE,
                tx.getKeroseneFeeSats(),
                null,
                "AVAILABLE");
        if (!wrote) {
            log.debug(
                    "KFE kerosene fee race lost transactionId={} — skip credit",
                    tx.getId());
            recordFeeSkip();
            return;
        }
        balanceService.creditAvailable(profitWalletId, KfeSystemWalletService.ASSET_BTC, tx.getKeroseneFeeSats());
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

    private void recordFeeSkip() {
        KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordFeeIdempotentSkip();
        }
    }
}
