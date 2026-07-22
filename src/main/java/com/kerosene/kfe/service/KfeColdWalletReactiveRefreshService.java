package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Debounces cold-wallet observation / balance sync after ZMQ signals so a burst of
 * mempool txs or a new block does not stampede RPC (scantxoutset).
 */
@Service
public class KfeColdWalletReactiveRefreshService {

    private static final Logger log = LoggerFactory.getLogger(KfeColdWalletReactiveRefreshService.class);

    private final ObjectProvider<KfeColdWalletObservationService> coldObservationService;
    private final ObjectProvider<KfeCustodialDepositObservationService> custodialDepositObservationService;
    private final ObjectProvider<KfeOnchainBalanceSyncService> balanceSyncService;
    private final KfeMonitoredChainAddressIndex addressIndex;
    private final long debounceMs;
    private final ConcurrentHashMap<UUID, Boolean> pendingWalletIds = new ConcurrentHashMap<>();
    private final AtomicBoolean allColdPending = new AtomicBoolean(false);
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "kfe-cold-zmq-refresh");
        t.setDaemon(true);
        return t;
    });

    public KfeColdWalletReactiveRefreshService(
            ObjectProvider<KfeColdWalletObservationService> coldObservationService,
            ObjectProvider<KfeCustodialDepositObservationService> custodialDepositObservationService,
            ObjectProvider<KfeOnchainBalanceSyncService> balanceSyncService,
            KfeMonitoredChainAddressIndex addressIndex,
            @Value("${kfe.bitcoin.zmq.debounce-ms:400}") long debounceMs) {
        this.coldObservationService = coldObservationService;
        this.custodialDepositObservationService = custodialDepositObservationService;
        this.balanceSyncService = balanceSyncService;
        this.addressIndex = addressIndex;
        // Fast floor: ZMQ already did instant ingest; this pass is accuracy/confs only.
        this.debounceMs = Math.max(200L, debounceMs);
    }

    /** New block tip — refresh every active cold wallet (balance + history observations). */
    public void onNewBlock() {
        allColdPending.set(true);
        scheduleFlush();
    }

    /** Mempool/block tx touched one or more monitored addresses. */
    public void onWalletsTouched(Set<UUID> walletIds) {
        if (walletIds == null || walletIds.isEmpty()) {
            return;
        }
        for (UUID walletId : walletIds) {
            if (walletId != null) {
                pendingWalletIds.put(walletId, Boolean.TRUE);
            }
        }
        scheduleFlush();
    }

    private void scheduleFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }
        scheduler.schedule(this::flushSafe, debounceMs, TimeUnit.MILLISECONDS);
    }

    private void flushSafe() {
        try {
            flush();
        } catch (RuntimeException exception) {
            log.warn("[KFE ZMQ] debounced refresh failed: {}", exception.getMessage());
        } finally {
            flushScheduled.set(false);
            if (allColdPending.get() || !pendingWalletIds.isEmpty()) {
                scheduleFlush();
            }
        }
    }

    private void flush() {
        Set<UUID> targets;
        if (allColdPending.compareAndSet(true, false)) {
            pendingWalletIds.clear();
            targets = addressIndex.allColdWalletIds();
            if (targets.isEmpty()) {
                // Index may be cold after restart — force rebuild once.
                addressIndex.rebuild();
                targets = addressIndex.allColdWalletIds();
            }
        } else {
            targets = Set.copyOf(pendingWalletIds.keySet());
            pendingWalletIds.clear();
        }
        if (targets.isEmpty()) {
            return;
        }

        // observeWallet already resyncs observed_sats — do not also call balance sync
        // (that double scantxoutset load was racing and flipping cold balances).
        KfeColdWalletObservationService coldObs = coldObservationService.getIfAvailable();
        KfeCustodialDepositObservationService custodialObs =
                custodialDepositObservationService.getIfAvailable();
        KfeOnchainBalanceSyncService balanceSync = balanceSyncService.getIfAvailable();
        int ok = 0;
        for (UUID walletId : targets) {
            try {
                // Try cold first; if wallet is custodial the cold service no-ops.
                if (coldObs != null) {
                    coldObs.observeWallet(walletId);
                }
                if (custodialObs != null) {
                    custodialObs.observeWallet(walletId);
                } else if (coldObs == null && balanceSync != null) {
                    balanceSync.syncWallet(walletId);
                }
                ok++;
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE ZMQ] refresh failed walletId={}: {}",
                        walletId,
                        exception.getMessage());
            }
        }
        log.info("[KFE ZMQ] reactive refresh completed wallets={}/{}", ok, targets.size());
    }
}
