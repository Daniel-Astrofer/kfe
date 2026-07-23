package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.kerosene.kfe.model.KfeWalletAddressEntity;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.model.KfeWalletStatus;
import com.kerosene.kfe.rail.BlockchainClient;
import com.kerosene.kfe.model.KfeBalanceEntity;
import com.kerosene.kfe.repository.KfeWalletAddressRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Keeps {@code observed_sats} aligned with the blockchain for on-chain custody kinds.
 *
 * <ul>
 *   <li>{@link KfeWalletKind#WATCH_ONLY} — cold wallet: only chain balance is meaningful.
 *       Writes are gated by {@link ChainProbeResult} quality (Electrum mempool-aware preferred).</li>
 *   <li>{@link KfeWalletKind#CUSTODIAL_ONCHAIN} — dual model: internal ledger
 *       (available/locked) authorizes spends; {@code observed_sats} mirrors chain for
 *       reconciliation/display.</li>
 *   <li>{@link KfeWalletKind#INTERNAL} — not scanned (pooled / ledger-only).</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "kfe.onchain-balance-sync.enabled", havingValue = "true", matchIfMissing = true)
public class KfeOnchainBalanceSyncService {

    private static final Logger log = LoggerFactory.getLogger(KfeOnchainBalanceSyncService.class);
    private static final String ASSET_BTC = "BTC";
    /** Scheduled probe kinds. WATCH_ONLY is refreshed by cold-observation (+ ZMQ) to avoid dual scantxoutset thrash. */
    private static final List<KfeWalletKind> SCHEDULED_SYNC_KINDS = List.of(
            KfeWalletKind.CUSTODIAL_ONCHAIN);
    private static final List<KfeWalletKind> CHAIN_SYNC_KINDS = List.of(
            KfeWalletKind.WATCH_ONLY,
            KfeWalletKind.CUSTODIAL_ONCHAIN);

    private final KfeWalletRepository walletRepository;
    private final KfeWalletAddressRepository addressRepository;
    private final KfeBalanceService balanceService;
    private final ObjectProvider<BlockchainClient> blockchainClient;
    private final KfeDashboardPublisher dashboardPublisher;
    private final TransactionTemplate transactionTemplate;
    private final KfeWalletDescriptorResolver descriptorResolver;
    private final ObjectProvider<KfeBalanceMetrics> balanceMetrics;
    private final int batchSize;
    private final int descriptorRange;
    /**
     * Fresh LIVE_MEMPOOL_AWARE probes are protected from OPTIMISTIC_DELTA for this many seconds
     * (ZMQ must not overwrite a just-completed full Electrum-parity collect).
     */
    private final long optimisticLiveTtlSeconds;

    public KfeOnchainBalanceSyncService(
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            KfeBalanceService balanceService,
            ObjectProvider<BlockchainClient> blockchainClient,
            KfeDashboardPublisher dashboardPublisher,
            TransactionTemplate transactionTemplate,
            KfeWalletDescriptorResolver descriptorResolver,
            ObjectProvider<KfeBalanceMetrics> balanceMetrics,
            @Value("${kfe.onchain-balance-sync.batch-size:50}") int batchSize,
            @Value("${kfe.onchain-balance-sync.descriptor-range:1000}") int descriptorRange,
            @Value("${kfe.onchain-balance-sync.optimistic-live-ttl-seconds:120}") long optimisticLiveTtlSeconds) {
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.balanceService = balanceService;
        this.blockchainClient = blockchainClient;
        this.dashboardPublisher = dashboardPublisher;
        this.transactionTemplate = transactionTemplate;
        this.descriptorResolver = descriptorResolver;
        this.balanceMetrics = balanceMetrics;
        this.batchSize = Math.max(1, batchSize);
        this.descriptorRange = Math.max(1, descriptorRange);
        this.optimisticLiveTtlSeconds = Math.max(0L, optimisticLiveTtlSeconds);
    }

    @Scheduled(
            fixedDelayString = "${kfe.onchain-balance-sync.fixed-delay-ms:45000}",
            initialDelayString = "${kfe.onchain-balance-sync.initial-delay-ms:25000}")
    public void reconcileActiveOnchainWallets() {
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }
        List<KfeWalletEntity> wallets = walletRepository
                .findByKindInAndStatus(SCHEDULED_SYNC_KINDS, KfeWalletStatus.ACTIVE);
        int limit = Math.min(batchSize, wallets.size());
        for (int i = 0; i < limit; i++) {
            try {
                syncWallet(wallets.get(i).getId());
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Onchain Balance] sync failed walletId={}: {}",
                        wallets.get(i).getId(),
                        exception.getMessage());
            }
        }
    }

    /**
     * Probe the chain and write absolute {@code observed_sats}. Safe to call after import or inbound detect.
     *
     * <p>Chain RPC runs <strong>outside</strong> a DB transaction; only the balance write uses
     * {@link TransactionTemplate} (FOR UPDATE). Holding Hikari connections across scantxoutset
     * starved the API and readiness probes.
     *
     * <p>For {@link KfeWalletKind#WATCH_ONLY}, descriptor-only probes are quality
     * {@link ProbeQuality#CONFIRMED_UTXO_SET} and will not overwrite a non-zero previous
     * observed (cold live path owns Electrum parity).
     */
    public long syncWallet(UUID walletId) {
        KfeWalletEntity wallet = walletRepository.findById(walletId)
                .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
        if (!CHAIN_SYNC_KINDS.contains(wallet.getKind())) {
            return 0L;
        }
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            log.debug("[KFE Onchain Balance] blockchain client unavailable walletId={}", walletId);
            return -1L;
        }

        final long chainSats;
        try {
            chainSats = probeChainBalanceSats(client, wallet);
        } catch (RuntimeException exception) {
            // Never write a partial/failed probe — that is what made cold balances oscillate.
            log.warn(
                    "[KFE Onchain Balance] probe failed walletId={} — keeping previous observed: {}",
                    walletId,
                    exception.getMessage());
            return -1L;
        }

        ProbeQuality quality = ProbeQuality.CONFIRMED_UTXO_SET;
        boolean authoritative = wallet.getKind() != KfeWalletKind.WATCH_ONLY;
        ChainProbeResult probe = new ChainProbeResult(
                chainSats,
                quality,
                authoritative,
                0,
                "syncWallet-descriptor");
        return applyObserved(walletId, probe);
    }

    /**
     * Legacy absolute write — treated as confirmed UTXO set for cold (will not clobber live).
     * Prefer {@link #applyObserved(UUID, ChainProbeResult)}.
     */
    public long applyObserved(UUID walletId, long chainSats) {
        if (chainSats < 0L) {
            return -1L;
        }
        return applyObserved(walletId, ChainProbeResult.confirmedUtxoSet(chainSats, 0, "legacy-absolute"));
    }

    /**
     * Write observed balance only when probe quality allows it for the wallet kind.
     */
    public long applyObserved(UUID walletId, ChainProbeResult probe) {
        if (probe == null || probe.quality() == ProbeQuality.UNKNOWN) {
            log.info(
                    "[KFE Onchain Balance] applyObserved deferred walletId={} quality=UNKNOWN source={}",
                    walletId,
                    probe != null ? probe.source() : "null");
            return -1L;
        }
        if (probe.sats() < 0L) {
            return -1L;
        }
        Long result = transactionTemplate.execute(status -> {
            KfeWalletEntity wallet = walletRepository.findById(walletId)
                    .orElseThrow(() -> new IllegalArgumentException("KFE wallet not found."));
            if (!CHAIN_SYNC_KINDS.contains(wallet.getKind())) {
                return 0L;
            }
            KfeBalanceEntity balance = balanceService.requireForUpdate(walletId, ASSET_BTC);
            long previous = balance.getObservedSats();
            ProbeQuality previousQuality = parseProbeQuality(balance.getObservedProbeQuality());
            LocalDateTime previousProbeAt = balance.getObservedProbeAt();
            Decision decision = decideWrite(
                    wallet.getKind(),
                    previous,
                    previousQuality,
                    previousProbeAt,
                    probe,
                    optimisticLiveTtlSeconds);
            if (!decision.write()) {
                log.info(
                        "[KFE Onchain Balance] applyObserved deferred walletId={} kind={} previous={} next={} quality={} prevQuality={} source={} reason={}",
                        walletId,
                        wallet.getKind(),
                        previous,
                        probe.sats(),
                        probe.quality(),
                        previousQuality,
                        probe.source(),
                        decision.reason());
                recordProbeMetric(probe.quality(), "deferred", wallet.getKind());
                recordDeferMetrics(probe.quality(), decision.reason());
                return previous;
            }
            balanceService.setObserved(
                    walletId,
                    ASSET_BTC,
                    probe.sats(),
                    probe.quality().name(),
                    probe.source());
            if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
                balanceService.zeroSpendableBucketsIfNeeded(walletId, ASSET_BTC);
            }
            dashboardPublisher.publishAfterCommit(wallet.getUserId());
            recordProbeMetric(probe.quality(), "written", wallet.getKind());
            log.info(
                    "[KFE Onchain Balance] applyObserved walletId={} kind={} previous={} chainSats={} quality={} outpoints={} source={}",
                    walletId,
                    wallet.getKind(),
                    previous,
                    probe.sats(),
                    probe.quality(),
                    probe.outpointCount(),
                    probe.source());
            return probe.sats();
        });
        return result == null ? -1L : result;
    }

    private void recordProbeMetric(ProbeQuality quality, String result, KfeWalletKind kind) {
        KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordProbe(quality, result, kind);
        }
    }

    private void recordDeferMetrics(ProbeQuality quality, String reason) {
        KfeBalanceMetrics metrics = balanceMetrics.getIfAvailable();
        if (metrics == null || reason == null) {
            return;
        }
        if (quality == ProbeQuality.OPTIMISTIC_DELTA) {
            metrics.recordOptimisticDeferred(reason);
        }
        if (reason.startsWith("optimistic-will-not-clobber")
                || reason.startsWith("confirmed-utxo-set-will-not-clobber")
                || reason.contains("monotonic")) {
            metrics.recordProbeMonotonicDefer(reason);
        }
    }

    /**
     * Policy for whether a probe may replace {@code previousObserved}.
     * Package-visible for unit tests. Prefer the overload with previous quality/time.
     */
    static Decision decideWrite(KfeWalletKind kind, long previousObserved, ChainProbeResult probe) {
        return decideWrite(kind, previousObserved, null, null, probe, 120L);
    }

    /**
     * Full policy including monotonic protection of fresh LIVE probes against OPTIMISTIC clobber.
     */
    static Decision decideWrite(
            KfeWalletKind kind,
            long previousObserved,
            ProbeQuality previousQuality,
            LocalDateTime previousProbeAt,
            ChainProbeResult probe,
            long optimisticLiveTtlSeconds) {
        if (probe == null || probe.quality() == ProbeQuality.UNKNOWN) {
            return Decision.defer("unknown-quality");
        }
        long next = probe.sats();
        if (kind != KfeWalletKind.WATCH_ONLY) {
            // Custodial: absolute chain mirror is always accepted when we have a probe.
            return Decision.write("custodial-absolute");
        }

        // Monotonic: OPTIMISTIC must not overwrite a fresh LIVE_MEMPOOL_AWARE collect.
        if (probe.quality() == ProbeQuality.OPTIMISTIC_DELTA
                && previousQuality == ProbeQuality.LIVE_MEMPOOL_AWARE
                && previousProbeAt != null
                && optimisticLiveTtlSeconds > 0L) {
            long ageSec = Duration.between(previousProbeAt, LocalDateTime.now(java.time.ZoneOffset.UTC)).getSeconds();
            if (ageSec >= 0L && ageSec < optimisticLiveTtlSeconds) {
                return Decision.defer("optimistic-will-not-clobber-fresh-live");
            }
        }

        return switch (probe.quality()) {
            case LIVE_MEMPOOL_AWARE -> {
                if (probe.authoritative()) {
                    yield Decision.write("live-mempool-aware");
                }
                yield Decision.defer("live-not-authoritative");
            }
            case CONFIRMED_UTXO_SET -> {
                // Mempool-blind: only seed empty cold wallets (import). Never reinflate after live/optimistic.
                if (previousObserved <= 0L) {
                    yield Decision.write("confirmed-seed-empty");
                }
                yield Decision.defer("confirmed-utxo-set-will-not-clobber-live");
            }
            case OPTIMISTIC_DELTA -> {
                // Never collapse to zero via optimistic path (incomplete spend amounts).
                if (next == 0L && previousObserved > 0L) {
                    yield Decision.defer("optimistic-zero-refused");
                }
                if (next == previousObserved) {
                    yield Decision.defer("optimistic-unchanged");
                }
                // Refuse implausible wipe (e.g. multi-output funding debited as whole inbound).
                if (previousObserved > 0L
                        && next < previousObserved
                        && next * 10L < previousObserved) {
                    yield Decision.defer("optimistic-drop-too-large");
                }
                yield Decision.write("optimistic-delta");
            }
            case UNKNOWN -> Decision.defer("unknown");
        };
    }

    static ProbeQuality parseProbeQuality(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ProbeQuality.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    long probeChainBalanceSats(BlockchainClient client, KfeWalletEntity wallet) {
        if (wallet.getKind() == KfeWalletKind.WATCH_ONLY) {
            // Confirmed-only descriptor total — cold live path must prefer mempool-aware collect.
            // Do not cap range below cold observation default (aligned via shared resolver + config).
            int descRange = Math.max(descriptorRange, 50);
            String receive = descriptorResolver.resolveReceiveDescriptor(wallet);
            if (receive == null) {
                return probeAddressBalance(client, wallet.getId());
            }
            return probeDescriptorBalance(client, wallet, descRange);
        }
        long fromAddresses = probeAddressBalance(client, wallet.getId());
        long fromDescriptor = 0L;
        try {
            fromDescriptor = probeDescriptorBalance(client, wallet, descriptorRange);
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Onchain Balance] descriptor probe failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
        return Math.max(fromDescriptor, fromAddresses);
    }

    private long probeDescriptorBalance(BlockchainClient client, KfeWalletEntity wallet, int range) {
        String receive = descriptorResolver.resolveReceiveDescriptor(wallet);
        if (receive == null) {
            return 0L;
        }
        int safeRange = Math.max(1, range);
        long total = client.getConfirmedBalanceForDescriptor(receive, safeRange);
        String change = KfeWalletDescriptorResolver.toChangeDescriptor(receive);
        if (change != null) {
            total = Math.addExact(total, client.getConfirmedBalanceForDescriptor(change, safeRange));
        }
        return total;
    }

    private long probeAddressBalance(BlockchainClient client, UUID walletId) {
        List<String> addresses = addressRepository.findByWalletIdOrderByCreatedAtDesc(walletId).stream()
                .map(KfeWalletAddressEntity::getAddress)
                .filter(address -> address != null && !address.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (addresses.isEmpty()) {
            return 0L;
        }
        // listunspent only sees Core-wallet-imported scripts; scantxoutset(addr) always works.
        long fromListUnspent = client.getUnspentBalanceForAddresses(addresses);
        long fromScan = 0L;
        for (String address : addresses) {
            try {
                fromScan = Math.addExact(fromScan, client.getConfirmedBalanceForAddress(address));
            } catch (RuntimeException ignored) {
                // keep partial
            }
        }
        return Math.max(fromListUnspent, fromScan);
    }

    private long readObservedSats(UUID walletId) {
        return balanceService.requireForUpdate(walletId, ASSET_BTC).getObservedSats();
    }

    record Decision(boolean write, String reason) {
        static Decision write(String reason) {
            return new Decision(true, reason);
        }

        static Decision defer(String reason) {
            return new Decision(false, reason);
        }
    }
}
