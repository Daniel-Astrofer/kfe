package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;
import com.kerosene.kfe.repository.KfeTransactionRepository;

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

    /** App confirmation rings target; keep refreshing SETTLED rows until this. */
    private static final int UI_CONFIRMATION_TARGET = 6;

    private final KfeTransactionRepository transactionRepository;
    private final KfeExecutionTransactionHelper transactionHelper;
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    private final ObjectProvider<KfeColdWalletObservationService> coldObservationService;
    private final int batchSize;
    private final int minConfirmations;

    public KfeOutboundConfirmationMonitor(
            KfeTransactionRepository transactionRepository,
            KfeExecutionTransactionHelper transactionHelper,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            ObjectProvider<KfeColdWalletObservationService> coldObservationService,
            @Value("${kfe.network-monitor.batch-size:50}") int batchSize,
            @Value("${kfe.network-monitor.onchain.min-confirmations:${bitcoin.min-confirmations:3}}")
            int minConfirmations) {
        this.transactionRepository = transactionRepository;
        this.transactionHelper = transactionHelper;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
        this.coldObservationService = coldObservationService;
        this.batchSize = Math.max(1, batchSize);
        // Allow 0 for mempool-settlement (local/dev). Production should keep >= 1.
        this.minConfirmations = Math.max(0, minConfirmations);
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
                UI_CONFIRMATION_TARGET,
                PageRequest.of(0, batchSize));
        List<KfeTransactionEntity> settledOutbounds = transactionRepository.findOutboundAwaitingConfirmation(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                List.of(KfeTransactionStatus.SETTLED),
                UI_CONFIRMATION_TARGET,
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
                        UI_CONFIRMATION_TARGET,
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
        java.util.OptionalInt found = core.findTransactionConfirmations(txid.trim());
        if (found.isEmpty()) {
            return;
        }
        int confirmations = found.getAsInt();
        if (KfeColdWalletObservationService.isColdObservation(tx)) {
            KfeColdWalletObservationService coldObs = coldObservationService.getIfAvailable();
            if (coldObs != null) {
                coldObs.touchColdConfirmations(tx.getId(), confirmations);
            }
            return;
        }
        persistConfProgress(tx, confirmations, "inbound");
    }

    private void inspect(BitcoinCoreRpcClient core, KfeTransactionEntity tx) {
        String txid = tx.getBlockchainTxid();
        if (txid == null || txid.isBlank()) {
            return;
        }
        java.util.OptionalInt found = core.findTransactionConfirmations(txid.trim());
        if (found.isEmpty()) {
            return;
        }
        int confirmations = found.getAsInt();
        // Cold PSBT / observer rows never hold a custodial LOCKED reserve.
        if (KfeColdWalletObservationService.isColdObservation(tx)) {
            KfeColdWalletObservationService coldObs = coldObservationService.getIfAvailable();
            if (coldObs != null) {
                coldObs.touchColdConfirmations(tx.getId(), confirmations);
            } else {
                persistConfProgress(tx, confirmations, "cold-outbound");
            }
            return;
        }

        // Always commit conf rings first (REQUIRES_NEW). Settle can hang on audit locks without
        // freezing the UI at 0/6.
        persistConfProgress(tx, confirmations, "outbound");

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
            // Confs already committed; unlock can retry next cycle.
            log.warn(
                    "[KFE Outbound Monitor] settle deferred txId={} confs={}: {}",
                    tx.getId(),
                    confirmations,
                    exception.getMessage());
        }
    }

    private void persistConfProgress(KfeTransactionEntity tx, int confirmations, String kind) {
        if (tx == null || confirmations <= tx.getConfirmations()) {
            return;
        }
        transactionHelper.touchOutboundConfirmations(tx.getId(), confirmations);
        log.info(
                "[KFE Outbound Monitor] {} confs txId={} confs={} was={} status={}",
                kind,
                tx.getId(),
                confirmations,
                tx.getConfirmations(),
                tx.getStatus());
    }
}
