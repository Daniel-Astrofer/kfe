package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.model.KfeBalanceEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.repository.KfeBalanceRepository;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.repository.KfeWalletRepository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Periodic dual-ledger / cold freshness checks. Logs + metrics only — no auto-release of locks.
 */
@Service
@ConditionalOnProperty(name = "kfe.balance-reconciliation.enabled", havingValue = "true", matchIfMissing = true)
public class KfeBalanceReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(KfeBalanceReconciliationJob.class);

    private final KfeWalletRepository walletRepository;
    private final KfeBalanceRepository balanceRepository;
    private final KfeTransactionRepository transactionRepository;
    private final ObjectProvider<KfeOnchainBalanceSyncService> balanceSyncService;
    private final ObjectProvider<KfeColdWalletObservationService> coldObservationService;
    private final KfeBalanceMetrics metrics;
    private final long driftThresholdSats;
    private final int lockedStuckMinutes;
    private final int coldBatchSize;

    public KfeBalanceReconciliationJob(
            KfeWalletRepository walletRepository,
            KfeBalanceRepository balanceRepository,
            KfeTransactionRepository transactionRepository,
            ObjectProvider<KfeOnchainBalanceSyncService> balanceSyncService,
            ObjectProvider<KfeColdWalletObservationService> coldObservationService,
            KfeBalanceMetrics metrics,
            @Value("${kfe.balance-reconciliation.drift-threshold-sats:1000}") long driftThresholdSats,
            @Value("${kfe.balance-reconciliation.locked-stuck-minutes:30}") int lockedStuckMinutes,
            @Value("${kfe.balance-reconciliation.cold-batch-size:20}") int coldBatchSize) {
        this.walletRepository = walletRepository;
        this.balanceRepository = balanceRepository;
        this.transactionRepository = transactionRepository;
        this.balanceSyncService = balanceSyncService;
        this.coldObservationService = coldObservationService;
        this.metrics = metrics;
        this.driftThresholdSats = Math.max(0L, driftThresholdSats);
        this.lockedStuckMinutes = Math.max(1, lockedStuckMinutes);
        this.coldBatchSize = Math.max(1, coldBatchSize);
    }

    @Scheduled(
            fixedDelayString = "${kfe.balance-reconciliation.fixed-delay-ms:180000}",
            initialDelayString = "${kfe.balance-reconciliation.initial-delay-ms:90000}")
    public void reconcile() {
        try {
            checkCustodialDrift();
        } catch (RuntimeException exception) {
            log.warn("[KFE Balance Recon] custodial drift pass failed: {}", exception.getMessage());
        }
        try {
            refreshStaleColdWallets();
        } catch (RuntimeException exception) {
            log.warn("[KFE Balance Recon] cold refresh pass failed: {}", exception.getMessage());
        }
        try {
            checkLockedStuck();
        } catch (RuntimeException exception) {
            log.warn("[KFE Balance Recon] locked-stuck pass failed: {}", exception.getMessage());
        }
    }

    @Transactional(readOnly = true)
    protected void checkCustodialDrift() {
        List<KfeWalletEntity> wallets = walletRepository.findByKindInAndStatus(
                List.of(KfeWalletKind.CUSTODIAL_ONCHAIN), KfeWalletStatus.ACTIVE);
        if (wallets.isEmpty()) {
            return;
        }
        Map<UUID, KfeBalanceEntity> balances = indexBalances(
                balanceRepository.findByWalletIds(wallets.stream().map(KfeWalletEntity::getId).toList()));
        KfeOnchainBalanceSyncService sync = balanceSyncService.getIfAvailable();
        for (KfeWalletEntity wallet : wallets) {
            KfeBalanceEntity balance = balances.get(wallet.getId());
            if (balance == null) {
                continue;
            }
            long ledger = balance.getAvailableSats() + balance.getLockedSats();
            long observed = balance.getObservedSats();
            long absDrift = Math.abs(ledger - observed);
            metrics.recordDrift(KfeWalletKind.CUSTODIAL_ONCHAIN, absDrift);
            if (absDrift > driftThresholdSats) {
                log.warn(
                        "[KFE Balance Recon] custodial drift walletId={} available={} locked={} observed={} driftSats={}",
                        wallet.getId(),
                        balance.getAvailableSats(),
                        balance.getLockedSats(),
                        observed,
                        absDrift);
                // Soft re-probe; non-fatal.
                if (sync != null) {
                    try {
                        sync.syncWallet(wallet.getId());
                    } catch (RuntimeException exception) {
                        log.debug(
                                "[KFE Balance Recon] resync failed walletId={}: {}",
                                wallet.getId(),
                                exception.getMessage());
                    }
                }
            }
        }
    }

    protected void refreshStaleColdWallets() {
        KfeColdWalletObservationService cold = coldObservationService.getIfAvailable();
        if (cold == null) {
            return;
        }
        List<KfeWalletEntity> wallets = walletRepository.findByKindInAndStatus(
                List.of(KfeWalletKind.WATCH_ONLY), KfeWalletStatus.ACTIVE);
        int limit = Math.min(coldBatchSize, wallets.size());
        for (int i = 0; i < limit; i++) {
            try {
                cold.observeWallet(wallets.get(i).getId());
            } catch (RuntimeException exception) {
                log.debug(
                        "[KFE Balance Recon] cold observe failed walletId={}: {}",
                        wallets.get(i).getId(),
                        exception.getMessage());
            }
        }
    }

    @Transactional(readOnly = true)
    protected void checkLockedStuck() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(lockedStuckMinutes);
        List<KfeWalletEntity> wallets = walletRepository.findByKindInAndStatus(
                List.of(KfeWalletKind.INTERNAL, KfeWalletKind.CUSTODIAL_ONCHAIN),
                KfeWalletStatus.ACTIVE);
        Map<UUID, KfeBalanceEntity> balances = indexBalances(
                balanceRepository.findByWalletIds(wallets.stream().map(KfeWalletEntity::getId).toList()));
        for (KfeWalletEntity wallet : wallets) {
            KfeBalanceEntity balance = balances.get(wallet.getId());
            if (balance == null || balance.getLockedSats() <= 0L) {
                continue;
            }
            // Open outbound still holding locks for too long.
            var open = transactionRepository.findByWalletIdAndStatusIn(
                    wallet.getId(),
                    List.of(
                            KfeTransactionStatus.EXECUTING,
                            KfeTransactionStatus.VALIDATING,
                            KfeTransactionStatus.REQUIRES_RECONCILIATION));
            boolean stuck = open.stream().anyMatch(tx -> {
                LocalDateTime created = tx.getCreatedAt();
                return created != null && created.isBefore(cutoff);
            });
            if (stuck || open.isEmpty()) {
                // empty open + locked > 0 is also suspicious (orphan lock)
                log.warn(
                        "[KFE Balance Recon] locked funds walletId={} lockedSats={} openTxs={} stuckOrOrphan={}",
                        wallet.getId(),
                        balance.getLockedSats(),
                        open.size(),
                        stuck || open.isEmpty());
                metrics.recordLockedStuck();
            }
        }
    }

    private static Map<UUID, KfeBalanceEntity> indexBalances(List<KfeBalanceEntity> balances) {
        Map<UUID, KfeBalanceEntity> map = new HashMap<>();
        for (KfeBalanceEntity balance : balances) {
            if (balance.getId() != null && balance.getId().getWalletId() != null) {
                map.put(balance.getId().getWalletId(), balance);
            }
        }
        return map;
    }
}
