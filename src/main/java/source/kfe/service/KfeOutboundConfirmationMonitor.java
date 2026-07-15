package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.rail.BitcoinCoreRpcClient;
import source.kfe.repository.KfeTransactionRepository;

import java.util.List;

/**
 * Production confirmation monitor for on-chain outbounds.
 *
 * <p>After broadcast ({@link KfeExecutionTransactionHelper#recordOutboundBroadcast}), funds remain
 * LOCKED on the source wallet until this monitor observes {@code minConfirmations} on the
 * blockchain txid, then settles the reserved debit.
 */
@Component
@ConditionalOnProperty(name = "kfe.network-monitor.enabled", havingValue = "true", matchIfMissing = true)
public class KfeOutboundConfirmationMonitor {

    private static final Logger log = LoggerFactory.getLogger(KfeOutboundConfirmationMonitor.class);

    private final KfeTransactionRepository transactionRepository;
    private final KfeExecutionTransactionHelper transactionHelper;
    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient;
    private final int batchSize;
    private final int minConfirmations;

    public KfeOutboundConfirmationMonitor(
            KfeTransactionRepository transactionRepository,
            KfeExecutionTransactionHelper transactionHelper,
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreRpcClient,
            @Value("${kfe.network-monitor.batch-size:50}") int batchSize,
            @Value("${kfe.network-monitor.onchain.min-confirmations:${bitcoin.min-confirmations:3}}")
            int minConfirmations) {
        this.transactionRepository = transactionRepository;
        this.transactionHelper = transactionHelper;
        this.bitcoinCoreRpcClient = bitcoinCoreRpcClient;
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

        List<KfeTransactionEntity> candidates = transactionRepository.findOutboundAwaitingConfirmation(
                KfeRail.ONCHAIN,
                KfeDirection.OUTBOUND,
                List.of(KfeTransactionStatus.EXECUTING, KfeTransactionStatus.REQUIRES_RECONCILIATION),
                PageRequest.of(0, batchSize));

        for (KfeTransactionEntity tx : candidates) {
            try {
                inspect(core, tx);
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Outbound Monitor] confirmation check failed txId={}: {}",
                        tx.getId(),
                        exception.getMessage());
            }
        }
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
        if (confirmations < minConfirmations) {
            // Persist partial progress for UI without unlocking yet.
            if (confirmations > tx.getConfirmations()) {
                transactionHelper.touchOutboundConfirmations(tx.getId(), confirmations);
            }
            return;
        }
        boolean settled = transactionHelper.settleOutboundWhenConfirmed(tx.getId(), confirmations);
        if (settled) {
            log.info(
                    "[KFE Outbound Monitor] settled outbound txId={} confs={}",
                    tx.getId(),
                    confirmations);
        }
    }
}
