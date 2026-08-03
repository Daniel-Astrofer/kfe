package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.config.KfeBitcoinFinalityPolicy;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;
import com.kerosene.kfe.repository.KfeTransactionRepository;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Production confirmation monitor for on-chain outbounds and inbounds.
 *
 * <p>After broadcast ({@link KfeExecutionTransactionHelper#recordOutboundBroadcast}), funds remain
 * LOCKED on the source wallet until this monitor observes {@code minConfirmations} on the
 * blockchain txid, then settles the reserved debit.
 *
 * <p><strong>UI contract:</strong> confirmation rings (0/6…6/6) must advance on every block even
 * when settle fails or hangs. Conf updates run in a short {@code REQUIRES_NEW} TX before settle.
 */
@Component
@ConditionalOnProperty(name = "kfe.network-monitor.enabled", havingValue = "true", matchIfMissing = true)
public class KfeOutboundConfirmationMonitor {

    private static final Logger log = LoggerFactory.getLogger(KfeOutboundConfirmationMonitor.class);

    private final KfeTransactionRepository transactionRepository;
    private final KfeExecutionTransactionHelper transactionHelper;
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    private final ObjectProvider<KfeColdWalletObservationService> coldObservationService;
    private final KfeFinancialMetrics financialMetrics;
    private final int batchSize;
    private final int minConfirmations;
    private final int uiConfirmationTarget;
    private final int maxNotFoundCount;
    private final int notFoundGracePeriodSeconds;

    public KfeOutboundConfirmationMonitor(
            KfeTransactionRepository transactionRepository,
            KfeExecutionTransactionHelper transactionHelper,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            ObjectProvider<KfeColdWalletObservationService> coldObservationService,
            KfeFinancialMetrics financialMetrics,
            @Value("${kfe.network-monitor.batch-size:50}") int batchSize,
            KfeBitcoinFinalityPolicy finalityPolicy,
            @Value("${kfe.network-monitor.onchain.max-not-found-count:5}") int maxNotFoundCount,
            @Value("${kfe.network-monitor.onchain.not-found-grace-period-seconds:300}")
            int notFoundGracePeriodSeconds) {
        this.transactionRepository = transactionRepository;
        this.transactionHelper = transactionHelper;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
        this.coldObservationService = coldObservationService;
        this.financialMetrics = financialMetrics;
        this.batchSize = Math.max(1, batchSize);
        this.minConfirmations = finalityPolicy.getCreditConfirmations();
        this.uiConfirmationTarget = finalityPolicy.getFinalityConfirmations();
        this.maxNotFoundCount = Math.max(1, maxNotFoundCount);
        this.notFoundGracePeriodSeconds = Math.max(30, notFoundGracePeriodSeconds);
    }

    @Scheduled(
            fixedDelayString = "${kfe.network-monitor.fixed-delay-ms:30000}",
            initialDelayString = "${kfe.network-monitor.initial-delay-ms:20000}")
    public void reconcileOutboundConfirmations() {
        BitcoinCoreRpcClient core = bitcoinCoreRpcClient.getIfAvailable();
        if (core == null) {
            return;
        }

        // Prefer unlocks (non-SETTLED) first, then SETTLED ring climbs — two queries so a flood of
        // SETTLED conf bumps cannot delay settle of EXECUTING rows.
        List<KfeTransactionEntity> openOutbounds = transactionRepository.findOutboundAwaitingConfirmation(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                List.of(
                        KfeTransactionStatus.EXECUTING,
                        KfeTransactionStatus.VALIDATING,
                        KfeTransactionStatus.REQUIRES_RECONCILIATION),
                uiConfirmationTarget,
                PageRequest.of(0, batchSize));
        List<KfeTransactionEntity> settledOutbounds = transactionRepository.findOutboundAwaitingConfirmation(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                List.of(KfeTransactionStatus.SETTLED),
                uiConfirmationTarget,
                PageRequest.of(0, batchSize));

        for (KfeTransactionEntity tx : openOutbounds) {
            try {
                inspect(core, tx);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Outbound Monitor] confirmation check failed txId={}: {}",
                        tx.getId(),
                        exception.getMessage());
            }
        }
        for (KfeTransactionEntity tx : settledOutbounds) {
            try {
                inspect(core, tx);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Outbound Monitor] confirmation check failed txId={}: {}",
                        tx.getId(),
                        exception.getMessage());
            }
        }

        // Inbounds: VALIDATING + SETTLED until rings hit 6.
        List<KfeTransactionEntity> openInbounds =
                transactionRepository.findOutboundAwaitingConfirmation(
                        KfeRail.ONCHAIN,
                        KfeDirection.INBOUND,
                        List.of(
                                KfeTransactionStatus.VALIDATING,
                                KfeTransactionStatus.EXECUTING,
                                KfeTransactionStatus.SETTLED),
                        uiConfirmationTarget,
                        PageRequest.of(0, batchSize));
        for (KfeTransactionEntity tx : openInbounds) {
            try {
                inspectInbound(core, tx);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Outbound Monitor] inbound conf check failed txId={}: {}",
                        tx.getId(),
                        exception.getMessage());
            }
        }
    }

    private void inspectInbound(BitcoinCoreRpcClient core, KfeTransactionEntity tx) {
        String txid = tx.getBlockchainTxid();
        if (txid == null || txid.isBlank()) {
            return;
        }
        // ITEM 8: Use full chain status for inbounds too, to detect disappeared deposits
        BitcoinCoreRpcClient.TransactionChainStatus status =
                core.fetchTransactionChainStatus(txid.trim());

        if (status.state() == BitcoinCoreRpcClient.TransactionChainStatus.ChainState.NOT_FOUND
                || status.state() == BitcoinCoreRpcClient.TransactionChainStatus.ChainState.UNKNOWN) {
            return;
        }
        int confirmations = status.confirmations();
        if (KfeColdWalletObservationService.isColdObservation(tx)) {
            KfeColdWalletObservationService coldObs = coldObservationService.getIfAvailable();
            if (coldObs != null) {
                coldObs.touchColdConfirmations(tx.getId(), confirmations);
            }
            return;
        }
        persistConfProgress(tx, confirmations, status.blockHash(), status.blockHeight(), "inbound");
    }

    private void inspect(BitcoinCoreRpcClient core, KfeTransactionEntity tx) {
        String txid = tx.getBlockchainTxid();
        if (txid == null || txid.isBlank()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        BitcoinCoreRpcClient.TransactionChainStatus status = core.fetchTransactionChainStatus(txid.trim());

        // Update network tracking fields
        if (tx.getNetworkFirstSeenAt() == null
                && status.state() != BitcoinCoreRpcClient.TransactionChainStatus.ChainState.UNKNOWN
                && status.state() != BitcoinCoreRpcClient.TransactionChainStatus.ChainState.NOT_FOUND) {
            tx.setNetworkFirstSeenAt(now);
        }
        if (status.state() != BitcoinCoreRpcClient.TransactionChainStatus.ChainState.UNKNOWN
                && status.state() != BitcoinCoreRpcClient.TransactionChainStatus.ChainState.NOT_FOUND) {
            tx.setNetworkLastSeenAt(now);
        }
        tx.setLastChainProbeAt(now);
        tx.setLastChainProbeStatus(status.state().name());

        // ITEM 8: Handle disappeared transactions
        if (status.state() == BitcoinCoreRpcClient.TransactionChainStatus.ChainState.NOT_FOUND) {
            if (tx.getNetworkNotFoundSince() == null) {
                tx.setNetworkNotFoundSince(now);
                tx.setNetworkNotFoundCount(1);
                // Metrics: first not-found occurrence
                String rail = tx.getRail() != null ? tx.getRail().name() : "ONCHAIN";
                financialMetrics.recordNetworkNotFound(rail);
            } else {
                tx.setNetworkNotFoundCount(tx.getNetworkNotFoundCount() + 1);
            }
            long notFoundDuration = java.time.Duration.between(tx.getNetworkNotFoundSince(), now).getSeconds();

            if (tx.getNetworkNotFoundCount() >= maxNotFoundCount
                    && notFoundDuration >= notFoundGracePeriodSeconds) {
                // Policy: After configured limit, query inputs and search for replacement
                handleDisappearedTransaction(core, tx);
            } else {
                // First absences: mark UNKNOWN, don't change ledger
                transactionRepository.save(tx);
                log.info(
                        "[KFE Outbound Monitor] tx disappeared txId={} txid={} notFound={}",
                        tx.getId(), txid, tx.getNetworkNotFoundCount());
            }
            return;
        }

        // Reset not-found tracking when tx is seen again
        if (tx.getNetworkNotFoundSince() != null) {
            tx.setNetworkNotFoundSince(null);
            tx.setNetworkNotFoundCount(0);
        }

        int confirmations = status.confirmations();
        // Track mempool presence
        if (confirmations == 0) {
            tx.setMempoolLastSeenAt(now);
        }

        // Negative confirmations = conflicted / double-spend / reorg reversal.
        if (confirmations < 0) {
            log.error(
                    "[KFE Outbound Monitor] CONFLICTED txId={} txid={} confs={} — entering reconciliation",
                    tx.getId(),
                    txid,
                    confirmations);
            try {
                transactionHelper.markOutboundConflicted(tx.getId(), confirmations);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Outbound Monitor] conflicted resolution failed txId={}: {}",
                        tx.getId(),
                        exception.getMessage());
            }
            transactionRepository.save(tx);
            return;
        }
        // Cold PSBT / observer rows never hold a custodial LOCKED reserve.
        if (KfeColdWalletObservationService.isColdObservation(tx)) {
            KfeColdWalletObservationService coldObs = coldObservationService.getIfAvailable();
            if (coldObs != null) {
                coldObs.touchColdConfirmations(tx.getId(), confirmations);
            } else {
                persistConfProgress(tx, confirmations, status.blockHash(), status.blockHeight(), "cold-outbound");
            }
            transactionRepository.save(tx);
            return;
        }

        // Always commit conf rings first (REQUIRES_NEW). Settle can hang on audit locks without
        // freezing the UI at 0/6.
        persistConfProgress(tx, confirmations, status.blockHash(), status.blockHeight(), "outbound");

        if (tx.getStatus() == KfeTransactionStatus.SETTLED) {
            return;
        }
        if (confirmations < minConfirmations) {
            return;
        }
        try {
            boolean settled = transactionHelper.settleOutboundWhenConfirmed(tx.getId(), confirmations);
            if (settled) {
                log.info(
                        "[KFE Outbound Monitor] settled outbound txId={} confs={}",
                        tx.getId(),
                        confirmations);
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Outbound Monitor] settle deferred txId={} confs={}: {}",
                    tx.getId(),
                    confirmations,
                    exception.getMessage());
        }
    }

    /** ITEM 8: Handle a transaction that has completely disappeared from the network. */
    private void handleDisappearedTransaction(BitcoinCoreRpcClient core, KfeTransactionEntity tx) {
        log.error("[KFE Outbound Monitor] DISAPPEARED txId={} txid={} notFoundCount={}",
                tx.getId(), tx.getBlockchainTxid(), tx.getNetworkNotFoundCount());

        // Query inputs — if all inputs are free, the tx never confirmed, safe to release
        String txid = tx.getBlockchainTxid();
        if (txid != null && !txid.isBlank()) {
            try {
                String replacement = core.findReplacementTxid(txid.trim());
                if (replacement != null) {
                    // Found replacement — associate and keep monitoring replacement
                    tx.setReplacementTxid(replacement);
                    tx.setConfirmations(0);
                    tx.setConfirmationMonitoringActive(false);
                    // Stop monitoring old txid; a new row should be created for replacement
                    transactionRepository.save(tx);
                    log.info("[KFE Outbound Monitor] replacement found {} -> {}", txid, replacement);
                    return;
                }

                // Check if all inputs are free by querying outpoints
                boolean allFree = true;
                com.fasterxml.jackson.databind.JsonNode raw = core.getRawTransaction(txid.trim(), true);
                if (raw != null && !raw.isNull() && !raw.isMissingNode()) {
                    com.fasterxml.jackson.databind.JsonNode vin = raw.path("vin");
                    if (vin.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode input : vin) {
                            String inTxid = input.path("txid").asText(null);
                            com.fasterxml.jackson.databind.JsonNode inVout = input.path("vout");
                            if (inTxid != null && !inTxid.isBlank() && inVout.isIntegralNumber()) {
                                com.fasterxml.jackson.databind.JsonNode outpoint =
                                        core.queryOutpoint(inTxid, inVout.asInt());
                                if (outpoint != null) {
                                    allFree = false;
                                    break;
                                }
                            }
                        }
                    }
                }

                if (allFree) {
                    // All inputs free — safe to release reserved funds
                    tx.setConfirmationMonitoringActive(false);
                    transactionRepository.save(tx);
                    transactionHelper.markFinalFailure(
                            null, tx.getId(), "TX_DISAPPEARED_INPUTS_FREE",
                            "Transaction disappeared from network and all inputs are free.");
                    return;
                }
            } catch (RuntimeException e) {
                log.warn("[KFE Outbound Monitor] disappeared handling failed txId={}: {}",
                        tx.getId(), e.getMessage());
            }
        }

        // Inconclusive — keep reserve locked, mark for reconciliation
        tx.setConfirmationMonitoringActive(false);
        transactionRepository.save(tx);
        transactionHelper.markRequiresReconciliation(
                null, tx.getId(), "TX_DISAPPEARED_INCONCLUSIVE",
                "Transaction disappeared from network; cannot determine input status definitively.");
    }

    private void persistConfProgress(KfeTransactionEntity tx, int confirmations,
                                      String blockHash, Integer blockHeight, String kind) {
        if (tx == null) {
            return;
        }
        // ITEM 10: Allow confirmation decreases while not FINALIZED
        if (confirmations == tx.getConfirmations()) {
            return;
        }
        transactionHelper.touchOutboundConfirmations(tx.getId(), confirmations, blockHash, blockHeight);
        log.info(
                "[KFE Outbound Monitor] {} confs txId={} confs={} was={} status={}",
                kind,
                tx.getId(),
                confirmations,
                tx.getConfirmations(),
                tx.getStatus());
    }
}
