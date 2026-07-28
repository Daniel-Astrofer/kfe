package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.application.transaction.KfeBalanceMovementRecorder;
import com.kerosene.kfe.application.transaction.KfeLedgerMovementTypes;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.repository.KfeBalanceMovementRepository;

import java.util.Map;
import java.util.UUID;

/**
 * Settles Kerosene fee to SYSTEM_PROFIT wallet (ITEM 10 — profit segregation).
 *
 * <p>PROFIT SEGREGATION MODEL (SUBLEDGER):
 * Fees are credited to SYSTEM_PROFIT as a ledger entry within the USERS bucket.
 * This is accounting-only — there is no physical UTXO segregation.
 * The SYSTEM_PROFIT balance is a LIABILITY within USERS until physically moved to a
 * dedicated vault PROFIT bucket.
 *
 * <p>INVARIANT: {@code userDebit = recipientCredit + revenueCredit + networkFee}
 * Every settled transaction must preserve this identity. No sats created or destroyed.
 *
 * <p>CONFIGURATION: {@code kfe.profit.segregation-mode=SUBLEDGER} (default).
 * Future modes: {@code DEDICATED_BUCKET} (separate vault bucket), {@code PERIODIC_TRANSFER}.
 *
 * <p>RECONCILIATION: When {@code kfe.profit.reconcile-with-vault=true}, the
 * SYSTEM_PROFIT balance is included in solvency calculations. Total assets must cover
 * user liabilities + SYSTEM_PROFIT balance + safety buffer.
 */
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
    private final String profitSegregationMode;
    private final boolean profitReconcileWithVault;

    public KfeFeeSettlementService(
            KfeSystemWalletService systemWalletService,
            KfeBalanceService balanceService,
            KfeBalanceMovementRecorder movementRecorder,
            KfeBalanceMovementRepository movementRepository,
            KfeAuditLogService auditLogService,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics,
            @Value("${kfe.profit.segregation-mode:SUBLEDGER}") String profitSegregationMode,
            @Value("${kfe.profit.reconcile-with-vault:true}") boolean profitReconcileWithVault) {
        this.systemWalletService = systemWalletService;
        this.balanceService = balanceService;
        this.movementRecorder = movementRecorder;
        this.movementRepository = movementRepository;
        this.auditLogService = auditLogService;
        this.balanceMetrics = balanceMetrics;
        this.profitSegregationMode = normalizeSegregationMode(profitSegregationMode);
        this.profitReconcileWithVault = profitReconcileWithVault;
    }

    /**
     * Credits the Kerosene fee from a settled transaction to the SYSTEM_PROFIT wallet.
     *
     * <p>PROFIT INVARIANT: Every fee credit preserves the identity:
     * {@code userDebit = recipientCredit + keroseneFee + networkFee}
     *
     * <p>Idempotent: dual inbound paths / retries must not inflate SYSTEM_PROFIT.
     *
     * <p>SEGREGATION: In SUBLEDGER mode, profit is tracked in the ledger but remains
     * part of the USERS bucket's backing assets. It is a liability until physically
     * transferred to a dedicated vault PROFIT bucket.
     *
     * <p>CHANNEL COST ATTRIBUTION: Channel operations (open/close/rebalance) consume
     * on-chain fees. These MUST NOT accidentally consume USERS backing. Channel costs
     * are tracked via {@code KfeChannelLifecycleService} and attributed to CHANNELS or
     * INFRA buckets.
     */
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
                        "keroseneFeeSats", tx.getKeroseneFeeSats(),
                        "segregationMode", profitSegregationMode,
                        "reconcileWithVault", String.valueOf(profitReconcileWithVault)));
        log.info("KFE kerosene fee settled transactionId={} feeSats={} mode={}",
                tx.getId(), tx.getKeroseneFeeSats(), profitSegregationMode);
    }

    /**
     * Returns the current profit segregation mode.
     * @return SUBLEDGER, DEDICATED_BUCKET, or PERIODIC_TRANSFER
     */
    public String profitSegregationMode() {
        return profitSegregationMode;
    }

    /**
     * Returns whether SYSTEM_PROFIT is reconciled against vault-controlled assets.
     */
    public boolean profitReconcileWithVault() {
        return profitReconcileWithVault;
    }

    private void recordFeeSkip() {
        KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordFeeIdempotentSkip();
        }
    }

    private static String normalizeSegregationMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return "SUBLEDGER";
        }
        return mode.trim().toUpperCase();
    }
}
