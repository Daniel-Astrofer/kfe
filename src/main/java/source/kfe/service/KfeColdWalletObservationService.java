package source.kfe.service;

import java.time.ZoneOffset;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.model.KfeWalletAddressEntity;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.model.KfeWalletStatus;
import source.kfe.rail.BlockchainClient;
import source.common.financial.FinancialNotificationPort;
import source.kfe.repository.KfeTransactionRepository;
import source.kfe.repository.KfeWalletAddressRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.math.BigDecimal;
import java.math.RoundingMode;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Indexes on-chain activity for {@link KfeWalletKind#WATCH_ONLY} wallets into
 * {@code transactions_master} so history/confirmations appear without a full chain indexer.
 *
 * <p>Inbound observations come from UTXO scans (listunspent + scantxoutset). Outbound cold
 * spends are primarily recorded at PSBT broadcast; this service refreshes their confirmations
 * and keeps {@code observed_sats} aligned via {@link KfeOnchainBalanceSyncService}.
 */
@Service
@ConditionalOnProperty(name = "kfe.cold-observation.enabled", havingValue = "true", matchIfMissing = true)
public class KfeColdWalletObservationService {

    private static final Logger log = LoggerFactory.getLogger(KfeColdWalletObservationService.class);
    public static final String PROVIDER_COLD_OBSERVER = "BITCOIN_CORE_COLD_OBSERVER";
    public static final String PROVIDER_COLD_PSBT = "BITCOIN_CORE_COLD_PSBT";
    /** External spend observed when prior cold inbounds leave the UTXO set (e.g. Electrum). */
    public static final String PROVIDER_COLD_EXTERNAL_SPEND = "BITCOIN_CORE_COLD_EXTERNAL_SPEND";

    private final KfeWalletRepository walletRepository;
    private final KfeWalletAddressRepository addressRepository;
    private final KfeTransactionRepository transactionRepository;
    private final ObjectProvider<BlockchainClient> blockchainClient;
    private final KfeStatementService statementService;
    private final KfeDashboardPublisher dashboardPublisher;
    private final ObjectProvider<KfeOnchainBalanceSyncService> balanceSyncService;
    private final source.kfe.repository.KfeBalanceRepository balanceRepository;
    private final KfeWalletDescriptorResolver descriptorResolver;
    private final ObjectProvider<KfeMonitoredChainAddressIndex> addressIndex;
    private final ObjectProvider<FinancialNotificationPort> notificationPort;
    private final TransactionTemplate transactionTemplate;
    private final int batchSize;
    private final int minConfirmations;
    private final int descriptorRange;
    /** Per-wallet single-flight so schedule + ZMQ debounce cannot overwrite with a stale scan. */
    private final ConcurrentHashMap<UUID, Object> walletLocks = new ConcurrentHashMap<>();

    public KfeColdWalletObservationService(
            KfeWalletRepository walletRepository,
            KfeWalletAddressRepository addressRepository,
            KfeTransactionRepository transactionRepository,
            ObjectProvider<BlockchainClient> blockchainClient,
            KfeStatementService statementService,
            KfeDashboardPublisher dashboardPublisher,
            ObjectProvider<KfeOnchainBalanceSyncService> balanceSyncService,
            source.kfe.repository.KfeBalanceRepository balanceRepository,
            KfeWalletDescriptorResolver descriptorResolver,
            ObjectProvider<KfeMonitoredChainAddressIndex> addressIndex,
            ObjectProvider<FinancialNotificationPort> notificationPort,
            TransactionTemplate transactionTemplate,
            @Value("${kfe.cold-observation.batch-size:20}") int batchSize,
            @Value("${kfe.cold-observation.min-confirmations:${bitcoin.min-confirmations:3}}")
            int minConfirmations,
            @Value("${kfe.cold-observation.descriptor-range:200}") int descriptorRange) {
        this.walletRepository = walletRepository;
        this.addressRepository = addressRepository;
        this.transactionRepository = transactionRepository;
        this.blockchainClient = blockchainClient;
        this.statementService = statementService;
        this.dashboardPublisher = dashboardPublisher;
        this.balanceSyncService = balanceSyncService;
        this.balanceRepository = balanceRepository;
        this.descriptorResolver = descriptorResolver;
        this.addressIndex = addressIndex;
        this.notificationPort = notificationPort;
        this.transactionTemplate = transactionTemplate;
        this.batchSize = Math.max(1, batchSize);
        this.minConfirmations = Math.max(0, minConfirmations);
        this.descriptorRange = Math.max(1, descriptorRange);
    }

    @Scheduled(
            fixedDelayString = "${kfe.cold-observation.fixed-delay-ms:60000}",
            initialDelayString = "${kfe.cold-observation.initial-delay-ms:35000}")
    public void reconcileColdWallets() {
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }
        List<KfeWalletEntity> wallets =
                walletRepository.findByKindInAndStatus(
                        List.of(KfeWalletKind.WATCH_ONLY), KfeWalletStatus.ACTIVE);
        int limit = Math.min(batchSize, wallets.size());
        for (int i = 0; i < limit; i++) {
            KfeWalletEntity wallet = wallets.get(i);
            try {
                // TransactionTemplate — never self-invoke @Transactional (proxy skip).
                observeWallet(wallet.getId());
            } catch (RuntimeException exception) {
                log.warn(
                        "[KFE Cold Observation] failed walletId={}: {}",
                        wallet.getId(),
                        exception.getMessage());
            }
        }
    }

    /**
     * Observes one cold wallet. Chain probes run <strong>outside</strong> a DB transaction;
     * ledger/history writes use a short {@link TransactionTemplate} afterwards.
     *
     * <p>Holding a TX across scantxoutset/listunspent exhausted Hikari and (when nested under
     * broadcast+audit) held {@code GLOBAL_AUDIT_APPENDER} for minutes.
     * Serialized per wallet against concurrent schedule/ZMQ observes.
     */
    public void observeWallet(UUID walletId) {
        if (walletId == null) {
            return;
        }
        Object lock = walletLocks.computeIfAbsent(walletId, id -> new Object());
        synchronized (lock) {
            KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
            if (wallet == null || wallet.getKind() != KfeWalletKind.WATCH_ONLY) {
                return;
            }
            BlockchainClient client = blockchainClient.getIfAvailable();
            if (client == null) {
                return;
            }

            // Do NOT rebuild the full address index on every observe (was multi-second).
            // Scheduled refresh + ZMQ start already keep it warm; rebuild only if empty.
            KfeMonitoredChainAddressIndex index = addressIndex.getIfAvailable();
            if (index != null && index.addressCount() == 0) {
                try {
                    index.rebuild();
                } catch (RuntimeException ignored) {
                    // keep previous index
                }
            }

            // Heavy RPC outside any open transaction.
            CollectResult collected = collectInbounds(client, wallet);
            transactionTemplate.executeWithoutResult(status -> applyObserveWallet(walletId, collected));
            // Confirmation probes also stay outside long DB transactions.
            refreshConfirmations(walletId);
        }
    }

    /**
     * Instant path: materialize cold inbound/outbound from a ZMQ {@code rawtx} payload at
     * 0 confirmations (mempool) without scantxoutset. Full {@link #observeWallet} still runs
     * afterwards for balance accuracy and confirmation refresh.
     */
    public void ingestZmqRawTx(KfeBitcoinZmqTxMatcher.ParsedRawTx parsed, Set<UUID> walletIds) {
        if (parsed == null || walletIds == null || walletIds.isEmpty()) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> doIngestZmqRawTx(parsed, walletIds));
    }

    private void doIngestZmqRawTx(KfeBitcoinZmqTxMatcher.ParsedRawTx parsed, Set<UUID> walletIds) {
        String spendTxid = parsed.txid();
        if (spendTxid == null || spendTxid.isBlank()) {
            return;
        }
        KfeMonitoredChainAddressIndex index = addressIndex.getIfAvailable();
        boolean anyChanged = false;
        Set<Long> usersToPublish = new HashSet<>();

        for (UUID walletId : walletIds) {
            if (walletId == null) {
                continue;
            }
            KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
            if (wallet == null || wallet.getKind() != KfeWalletKind.WATCH_ONLY) {
                continue;
            }
            boolean changed = false;
            long inboundSats = 0L;
            long fundingSpentSats = 0L;
            long changeToUs = 0L;
            long externalOut = 0L;
            String externalDest = null;

            // 1) Inbound / change outputs to this cold wallet (0 conf).
            for (KfeBitcoinZmqTxMatcher.ParsedOutput out : parsed.outputs()) {
                if (out == null || out.valueSats() <= 0L) {
                    continue;
                }
                String address = out.address();
                if (address == null || address.isBlank()) {
                    continue;
                }
                if (!addressBelongsToWallet(walletId, address, index)) {
                    externalOut = Math.addExact(externalOut, out.valueSats());
                    if (externalDest == null) {
                        externalDest = address.trim();
                    }
                    continue;
                }
                changeToUs = Math.addExact(changeToUs, out.valueSats());
                if (upsertInboundObservation(
                        wallet,
                        new ObservedInbound(spendTxid, out.valueSats(), 0, address.trim()))) {
                    changed = true;
                    inboundSats = Math.addExact(inboundSats, out.valueSats());
                }
            }

            // 2) Outbound: spend known cold funding *outpoints* (txid:vout), not whole funding txs.
            // Multi-output fundings must not debit full inbound amount when only one vout is spent.
            Set<String> spentFundingTxids = new HashSet<>();
            Set<String> spentOutpoints = new HashSet<>();
            boolean fundingValueIncomplete = false;
            BlockchainClient client = blockchainClient.getIfAvailable();
            for (KfeBitcoinZmqTxMatcher.ParsedInput in : parsed.inputs()) {
                if (in == null || in.fundingTxid() == null || in.fundingTxid().isBlank()) {
                    continue;
                }
                String funding = in.fundingTxid().toLowerCase(Locale.ROOT);
                int vout = (int) Math.max(0L, in.vout());
                if (!fundingOutpointOwnedByWallet(walletId, funding, index)) {
                    continue;
                }
                String outpointKey = funding + ":" + vout;
                if (!spentOutpoints.add(outpointKey)) {
                    continue;
                }
                spentFundingTxids.add(funding);
                long value = resolveOutpointValueSats(client, walletId, funding, vout);
                if (value > 0L) {
                    fundingSpentSats = Math.addExact(fundingSpentSats, value);
                } else {
                    fundingValueIncomplete = true;
                }
            }

            if (!spentFundingTxids.isEmpty()) {
                // One OUTBOUND row per spend tx (not per funding) when multiple fundings coalesce.
                String spendIdem = "cold-ext-spend-tx:" + walletId + ":" + spendTxid;
                if (transactionRepository.findByIdempotencyKey(spendIdem).isEmpty()) {
                    // Also skip if legacy per-funding keys already recorded this spend txid.
                    boolean already = false;
                    for (String funding : spentFundingTxids) {
                        String legacy = "cold-ext-spend:" + walletId + ":" + funding;
                        var existing = transactionRepository.findByIdempotencyKey(legacy);
                        if (existing.isPresent()) {
                            already = true;
                            // Upgrade legacy row with real spend txid / dest when missing.
                            KfeTransactionEntity legacyTx = existing.get();
                            boolean patched = false;
                            if (legacyTx.getBlockchainTxid() == null
                                    || !spendTxid.equalsIgnoreCase(legacyTx.getBlockchainTxid())) {
                                legacyTx.setBlockchainTxid(spendTxid);
                                legacyTx.setProviderReference(spendTxid);
                                patched = true;
                            }
                            if ((legacyTx.getExternalReference() == null
                                    || legacyTx.getExternalReference().isBlank())
                                    && externalDest != null) {
                                legacyTx.setExternalReference(externalDest);
                                patched = true;
                            }
                            if (legacyTx.getConfirmations() < 0) {
                                legacyTx.setConfirmations(0);
                                patched = true;
                            }
                            if (legacyTx.getStatus() == KfeTransactionStatus.SETTLED
                                    && legacyTx.getConfirmations() <= 0) {
                                legacyTx.setStatus(KfeTransactionStatus.VALIDATING);
                                patched = true;
                            }
                            if (patched) {
                                transactionRepository.save(legacyTx);
                                changed = true;
                            }
                        }
                    }
                    if (!already) {
                        // Prefer spend-tx structure when outpoint values incomplete.
                        long amountHint =
                                fundingSpentSats > 0L
                                        ? fundingSpentSats
                                        : sumInboundAmounts(walletId, spentFundingTxids);
                        SpendDetails details =
                                resolveSpendDetails(client, walletId, spendTxid, amountHint);
                        long payment = details.paymentSats() > 0L ? details.paymentSats() : externalOut;
                        long fee = details.feeSats();
                        if (payment <= 0L && fundingSpentSats > 0L) {
                            payment = Math.max(0L, fundingSpentSats - changeToUs);
                        }
                        if (payment <= 0L && fee > 0L) {
                            payment = fee;
                            fee = 0L;
                            if (externalDest == null) {
                                externalDest = "Consolidação / troco";
                            }
                        }
                        if (payment > 0L || fee > 0L) {
                            long pay = Math.max(payment, 1L);
                            String dest =
                                    details.destinationAddress() != null
                                            ? details.destinationAddress()
                                            : externalDest;
                            KfeTransactionEntity tx = new KfeTransactionEntity();
                            tx.setUserId(wallet.getUserId());
                            tx.setIdempotencyKey(spendIdem);
                            tx.setRail(KfeRail.ONCHAIN);
                            tx.setDirection(KfeDirection.OUTBOUND);
                            tx.setSourceWalletId(wallet.getId());
                            tx.setExternalReference(dest);
                            tx.setMemo(
                                    dest != null
                                            ? "Envio Electrum"
                                            : "Envio detectado (Electrum / carteira externa)");
                            tx.setGrossAmountSats(pay);
                            tx.setReceiverAmountSats(pay);
                            tx.setNetworkFeeSats(Math.max(0L, fee));
                            tx.setKeroseneFeeSats(0L);
                            tx.setTotalDebitSats(Math.addExact(pay, Math.max(0L, fee)));
                            tx.setProvider(PROVIDER_COLD_EXTERNAL_SPEND);
                            tx.setProviderReference(spendTxid);
                            tx.setBlockchainTxid(spendTxid);
                            tx.setConfirmations(0);
                            tx.setStatus(KfeTransactionStatus.VALIDATING);
                            tx = transactionRepository.save(tx);
                            statementService.recordUserStatement(
                                    wallet.getUserId(), wallet.getId(), tx, statementPayload(tx));
                            notifyColdOutboundSafe(wallet, tx);
                            changed = true;
                            log.info(
                                    "[KFE Cold Observation] ZMQ instant outbound walletId={} spendTxid={} paymentSats={} outpoints={} dest={} confs=0",
                                    walletId,
                                    spendTxid,
                                    pay,
                                    spentOutpoints.size(),
                                    dest);
                        }
                    }
                }
            }

            // Optimistic observed balance so the app moves before scantxoutset.
            // Only apply spend deltas when per-outpoint values are complete (no full-funding guess).
            if (changed || fundingSpentSats > 0L || inboundSats > 0L) {
                KfeOnchainBalanceSyncService sync = balanceSyncService.getIfAvailable();
                if (sync != null) {
                    long previous = readObservedSats(walletId);
                    long next = previous;
                    if (fundingSpentSats > 0L && !fundingValueIncomplete) {
                        next = Math.max(0L, previous - fundingSpentSats + changeToUs);
                    } else if (inboundSats > 0L && fundingSpentSats <= 0L) {
                        next = Math.addExact(previous, inboundSats);
                    } else if (inboundSats > 0L && fundingSpentSats > 0L && !fundingValueIncomplete) {
                        next = Math.max(0L, previous - fundingSpentSats + changeToUs);
                        // changeToUs already includes inbound change on this tx; do not double-add.
                    }
                    if (next != previous) {
                        try {
                            long written = sync.applyObserved(
                                    walletId,
                                    ChainProbeResult.optimisticDelta(next, "zmq-optimistic"));
                            if (written >= 0L && written != previous) {
                                changed = true;
                            }
                        } catch (RuntimeException exception) {
                            log.debug(
                                    "[KFE Cold Observation] optimistic balance skip walletId={}: {}",
                                    walletId,
                                    exception.getMessage());
                        }
                    } else if (fundingValueIncomplete && fundingSpentSats <= 0L) {
                        log.debug(
                                "[KFE Cold Observation] ZMQ optimistic spend deferred walletId={} (outpoint values incomplete)",
                                walletId);
                    }
                }
            }

            if (changed) {
                anyChanged = true;
                usersToPublish.add(wallet.getUserId());
            }
        }

        if (anyChanged) {
            for (Long userId : usersToPublish) {
                if (userId != null) {
                    dashboardPublisher.publishAfterCommit(userId);
                }
            }
        }
    }

    /** True when the funding txid is a known cold inbound for this wallet (index or DB). */
    private boolean fundingOutpointOwnedByWallet(
            UUID walletId, String fundingTxid, KfeMonitoredChainAddressIndex index) {
        if (walletId == null || fundingTxid == null || fundingTxid.isBlank()) {
            return false;
        }
        String funding = fundingTxid.trim().toLowerCase(Locale.ROOT);
        if (index != null) {
            UUID owner = index.walletIdForFundingTxid(funding);
            if (walletId.equals(owner)) {
                return true;
            }
        }
        String idem = "cold-obs:" + walletId + ":" + funding;
        return transactionRepository.findByIdempotencyKey(idem).isPresent();
    }

    /**
     * Value of a spent outpoint: prefer funding rawtx vout; single-vout inbound row as last
     * resort only (avoids multi-output full-amount debit).
     */
    private long resolveOutpointValueSats(
            BlockchainClient client, UUID walletId, String fundingTxid, int vout) {
        if (fundingTxid == null || fundingTxid.isBlank() || vout < 0) {
            return 0L;
        }
        String funding = fundingTxid.trim().toLowerCase(Locale.ROOT);
        if (client != null) {
            try {
                JsonNode raw = client.getRawTransaction(funding, true);
                if (raw != null && !raw.isNull()) {
                    JsonNode vouts = raw.path("vout");
                    if (vouts.isArray()) {
                        for (JsonNode node : vouts) {
                            int n = node.path("n").isIntegralNumber()
                                    ? node.path("n").asInt()
                                    : -1;
                            // Some Core responses omit n and rely on array order.
                            if (n < 0) {
                                continue;
                            }
                            if (n == vout) {
                                long sats = amountFromBtcField(node, "value");
                                if (sats > 0L) {
                                    return sats;
                                }
                            }
                        }
                        if (vout < vouts.size()) {
                            long sats = amountFromBtcField(vouts.get(vout), "value");
                            if (sats > 0L) {
                                return sats;
                            }
                        }
                    }
                }
            } catch (RuntimeException exception) {
                log.debug(
                        "[KFE Cold Observation] outpoint value lookup failed {}/{}: {}",
                        funding,
                        vout,
                        exception.getMessage());
            }
        }
        // Last resort: cold inbound row for this funding only when vout==0 and row exists
        // (typical single-output deposit). Multi-vout without RPC stays incomplete.
        if (vout == 0) {
            var inbound = transactionRepository.findByIdempotencyKey(
                    "cold-obs:" + walletId + ":" + funding);
            if (inbound.isPresent()) {
                long amount = Math.max(0L, inbound.get().getReceiverAmountSats());
                if (amount <= 0L) {
                    amount = Math.max(0L, inbound.get().getGrossAmountSats());
                }
                return amount;
            }
        }
        return 0L;
    }

    private long sumInboundAmounts(UUID walletId, Set<String> fundingTxids) {
        long total = 0L;
        if (fundingTxids == null || fundingTxids.isEmpty()) {
            return 0L;
        }
        for (String funding : fundingTxids) {
            if (funding == null || funding.isBlank()) {
                continue;
            }
            var inbound = transactionRepository.findByIdempotencyKey(
                    "cold-obs:" + walletId + ":" + funding.trim().toLowerCase(Locale.ROOT));
            if (inbound.isEmpty()) {
                continue;
            }
            long amount = Math.max(0L, inbound.get().getReceiverAmountSats());
            if (amount <= 0L) {
                amount = Math.max(0L, inbound.get().getGrossAmountSats());
            }
            total = Math.addExact(total, amount);
        }
        return total;
    }

    private boolean addressBelongsToWallet(
            UUID walletId, String address, KfeMonitoredChainAddressIndex index) {
        if (walletId == null || address == null || address.isBlank()) {
            return false;
        }
        if (index != null) {
            UUID owner = index.walletIdForAddress(address);
            if (walletId.equals(owner)) {
                return true;
            }
            if (owner != null) {
                return false;
            }
        }
        return addressRepository
                .findFirstByAddressIgnoreCase(address.trim())
                .map(row -> walletId.equals(row.getWalletId()))
                .orElse(false);
    }

    /**
     * Applies a pre-probed collect under a short DB transaction (no chain RPC here).
     */
    private void applyObserveWallet(UUID walletId, CollectResult collected) {
        KfeWalletEntity wallet = walletRepository.findById(walletId).orElse(null);
        if (wallet == null || wallet.getKind() != KfeWalletKind.WATCH_ONLY) {
            return;
        }
        if (collected == null) {
            return;
        }

        boolean changed = false;
        for (ObservedInbound inbound : collected.observations().values()) {
            if (upsertInboundObservation(wallet, inbound)) {
                changed = true;
            }
        }
        // Electrum spends: use live outpoints (txid:vout). Txid-only hid partial spends when
        // another output of the same funding tx was still unspent.
        if (recordExternalSpendsForMissingUtxos(
                wallet, collected.liveOutpoints(), collected.liveUnspentTxids())) {
            changed = true;
        }

        long previousObserved = readObservedSats(wallet.getId());
        // Prefer live UTXO sum AFTER mempool spend adjustments (Electrum parity).
        // Never fall back to mempool-blind syncWallet — that reinflates vs Electrum.
        long afterObserved = previousObserved;
        KfeOnchainBalanceSyncService sync = balanceSyncService.getIfAvailable();
        try {
            if (sync != null && collected.authoritativeLive()) {
                afterObserved = collected.liveUnspentSats();
                long written = sync.applyObserved(
                        wallet.getId(),
                        ChainProbeResult.liveMempoolAware(
                                afterObserved,
                                collected.liveOutpointCount(),
                                "cold-collect"));
                if (written >= 0L) {
                    afterObserved = written;
                }
                log.info(
                        "[KFE Cold Observation] walletId={} liveOutpoints={} liveSats={} mbtc={} descriptorOk={} previous={}",
                        walletId,
                        collected.liveOutpointCount(),
                        afterObserved,
                        BigDecimal.valueOf(afterObserved).movePointLeft(5),
                        collected.descriptorScanOk(),
                        previousObserved);
            } else if (sync != null) {
                log.info(
                        "[KFE Cold Observation] probe deferred walletId={} previous={} descriptorOk={} addressOk={} — keeping previous (no mempool-blind fallback)",
                        walletId,
                        previousObserved,
                        collected.descriptorScanOk(),
                        collected.addressMergeOk());
                afterObserved = previousObserved;
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Cold Observation] balance resync failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
            afterObserved = previousObserved;
        }
        // Ghost delta rows only when live probe is incomplete (otherwise outpoint spends are truth).
        if (!collected.authoritativeLive()
                && !changed
                && recordExternalSpendFromBalanceDrop(wallet, previousObserved, afterObserved)) {
            changed = true;
        }

        if (changed || sync != null) {
            dashboardPublisher.publishAfterCommit(wallet.getUserId());
        }
    }

    /**
     * Confirmation refresh: load open cold txs, probe chain outside the DB TX, then touch rows.
     */
    public void refreshConfirmations(UUID walletId) {
        if (walletId == null) {
            return;
        }
        BlockchainClient client = blockchainClient.getIfAvailable();
        if (client == null) {
            return;
        }
        List<KfeTransactionEntity> open = transactionRepository.findByWalletIdAndStatusIn(
                walletId,
                List.of(
                        KfeTransactionStatus.EXECUTING,
                        KfeTransactionStatus.VALIDATING,
                        KfeTransactionStatus.SETTLED));
        for (KfeTransactionEntity tx : open) {
            if (!isColdObservation(tx) || tx.getBlockchainTxid() == null || tx.getBlockchainTxid().isBlank()) {
                continue;
            }
            if (tx.getStatus() == KfeTransactionStatus.SETTLED && tx.getConfirmations() >= 6) {
                continue;
            }
            int confs = -1;
            try {
                if (client instanceof source.kfe.rail.BitcoinCoreRpcClient core) {
                    var found = core.findTransactionConfirmations(tx.getBlockchainTxid().trim());
                    if (found.isPresent()) {
                        confs = found.getAsInt();
                    }
                } else {
                    var raw = client.getRawTransaction(tx.getBlockchainTxid().trim(), true);
                    if (raw != null && raw.path("confirmations").isIntegralNumber()) {
                        confs = raw.path("confirmations").asInt();
                    }
                }
            } catch (RuntimeException ignored) {
                continue;
            }
            if (confs >= 0) {
                final int next = confs;
                final UUID txId = tx.getId();
                transactionTemplate.executeWithoutResult(status -> touchColdConfirmations(txId, next));
            }
        }
    }

    /**
     * Materializes a cold PSBT broadcast as an on-chain OUTBOUND history row (no ledger reserve).
     */
    @Transactional
    public KfeTransactionEntity recordColdPsbtBroadcast(
            Long userId,
            UUID walletId,
            UUID workflowId,
            String txid,
            long amountSats,
            long feeSats,
            String destinationAddress) {
        if (txid == null || txid.isBlank()) {
            throw new IllegalArgumentException("txid is required for cold PSBT observation.");
        }
        String idempotencyKey = "cold-psbt:" + workflowId;
        return transactionRepository.findByIdempotencyKey(idempotencyKey).orElseGet(() -> {
            long safeAmount = Math.max(0L, amountSats);
            long safeFee = Math.max(0L, feeSats);
            KfeTransactionEntity tx = new KfeTransactionEntity();
            tx.setUserId(userId);
            tx.setIdempotencyKey(idempotencyKey);
            tx.setRail(KfeRail.ONCHAIN);
            tx.setDirection(KfeDirection.OUTBOUND);
            tx.setSourceWalletId(walletId);
            tx.setExternalReference(destinationAddress);
            tx.setMemo("Envio carteira fria");
            tx.setGrossAmountSats(safeAmount);
            tx.setReceiverAmountSats(safeAmount);
            tx.setNetworkFeeSats(safeFee);
            tx.setKeroseneFeeSats(0L);
            tx.setTotalDebitSats(Math.addExact(safeAmount, safeFee));
            tx.setProvider(PROVIDER_COLD_PSBT);
            tx.setProviderReference(txid.trim());
            tx.setBlockchainTxid(txid.trim());
            tx.setConfirmations(0);
            tx.setStatus(KfeTransactionStatus.EXECUTING);
            tx = transactionRepository.save(tx);
            statementService.recordUserStatement(
                    userId,
                    walletId,
                    tx,
                    statementPayload(tx));
            dashboardPublisher.publishAfterCommit(userId);
            return tx;
        });
    }

    @Transactional
    public boolean touchColdConfirmations(UUID transactionId, int confirmations) {
        KfeTransactionEntity tx = transactionRepository.findByIdForUpdate(transactionId).orElse(null);
        if (tx == null) {
            return false;
        }
        int next = Math.max(0, confirmations);
        boolean statusChange = false;
        boolean confChanged = false;
        if (next > tx.getConfirmations()) {
            tx.setConfirmations(next);
            confChanged = true;
        }
        if (isColdObservation(tx)
                && next >= minConfirmations
                && tx.getStatus() != KfeTransactionStatus.SETTLED
                && tx.getStatus() != KfeTransactionStatus.FAILED) {
            tx.setStatus(KfeTransactionStatus.SETTLED);
            statusChange = true;
            // Upsert: same transactionId, displayStatus → CONFIRMED; createdAt unchanged.
            statementService.recordUserStatement(
                    tx.getUserId(),
                    firstWallet(tx),
                    tx,
                    statementPayload(tx));
            if (PROVIDER_COLD_EXTERNAL_SPEND.equals(tx.getProvider())
                    || PROVIDER_COLD_PSBT.equals(tx.getProvider())) {
                KfeWalletEntity wallet = walletRepository.findById(firstWallet(tx)).orElse(null);
                if (wallet != null) {
                    notifyColdOutboundSafe(wallet, tx);
                }
            } else if (PROVIDER_COLD_OBSERVER.equals(tx.getProvider())) {
                KfeWalletEntity wallet = walletRepository.findById(firstWallet(tx)).orElse(null);
                if (wallet != null) {
                    FinancialNotificationPort port = notificationPort.getIfAvailable();
                    if (port != null) {
                        try {
                            port.notifyDepositConfirmed(
                                    wallet.getUserId(),
                                    tx.getId(),
                                    wallet.getId(),
                                    "ONCHAIN",
                                    Math.max(0L, tx.getReceiverAmountSats()),
                                    next);
                        } catch (RuntimeException exception) {
                            log.warn(
                                    "[KFE Cold Observation] confirm notify failed: {}",
                                    exception.getMessage());
                        }
                    }
                }
            }
        }
        transactionRepository.save(tx);
        if (statusChange || confChanged) {
            // Statement display payload is frozen at first write — refresh confs/status for UI.
            try {
                statementService.refreshTransactionDisplayPayload(tx, statementPayload(tx));
            } catch (RuntimeException ignored) {
                // non-fatal
            }
            dashboardPublisher.publishAfterCommit(tx.getUserId());
            if (confChanged) {
                log.info(
                        "[KFE Cold Observation] confs updated txId={} confs={} status={}",
                        tx.getId(),
                        tx.getConfirmations(),
                        tx.getStatus());
            }
        }
        return statusChange || confChanged;
    }

    private CollectResult collectInbounds(BlockchainClient client, KfeWalletEntity wallet) {
        // Descriptor-first (receive+change gap) = Electrum truth for balance. Then merge
        // known addresses. Dedupe by outpoint so we never double-count.
        Map<String, BlockchainClient.AddressUtxo> byOutpoint = new LinkedHashMap<>();
        boolean hasDescriptor = descriptorResolver.resolveReceiveDescriptor(wallet) != null;
        boolean descriptorScanOk = mergeDescriptorOutpoints(client, wallet, byOutpoint);
        boolean addressMergeOk = false;
        List<KfeWalletAddressEntity> addresses =
                addressRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId());
        for (KfeWalletAddressEntity addressEntity : addresses) {
            String address = addressEntity.getAddress();
            if (address == null || address.isBlank()) {
                continue;
            }
            try {
                List<BlockchainClient.AddressUtxo> utxos =
                        client.getUnspentOutputsMerged(address.trim());
                addressMergeOk = true;
                for (BlockchainClient.AddressUtxo utxo : utxos) {
                    putOutpoint(byOutpoint, utxo, address.trim());
                }
            } catch (RuntimeException e) {
                // keep other results — do not treat soft-empty as success
            }
        }

        // scantxoutset ignores mempool: drop outpoints already spent in mempool (Electrum does).
        // When a spend is found, pull mempool change outputs back into the live set.
        applyMempoolSpendAdjustments(client, wallet.getId(), byOutpoint);

        Map<String, ObservedInbound> byTxid = new LinkedHashMap<>();
        java.util.Set<String> liveUnspentTxids = new java.util.HashSet<>();
        java.util.Set<String> liveOutpoints = new java.util.HashSet<>();
        for (BlockchainClient.AddressUtxo utxo : byOutpoint.values()) {
            if (utxo.txid() == null || utxo.txid().isBlank() || utxo.valueSats() <= 0L) {
                continue;
            }
            String txid = utxo.txid().trim();
            String txidKey = txid.toLowerCase(java.util.Locale.ROOT);
            liveUnspentTxids.add(txidKey);
            liveOutpoints.add(txidKey + ":" + utxo.vout());
            ObservedInbound existing = byTxid.get(txid);
            if (existing == null) {
                byTxid.put(
                        txid,
                        new ObservedInbound(
                                txid,
                                utxo.valueSats(),
                                Math.max(0, utxo.confirmations()),
                                utxo.address()));
            } else {
                byTxid.put(
                        txid,
                        new ObservedInbound(
                                txid,
                                Math.addExact(existing.amountSats(), utxo.valueSats()),
                                Math.max(existing.confirmations(), utxo.confirmations()),
                                existing.address() != null ? existing.address() : utxo.address()));
            }
            // Persist gap addresses so future scans and FE filters stay accurate.
            materializeMonitoredAddress(wallet.getId(), utxo.address());
        }
        // Do NOT merge listreceived amounts into live balance. listreceived can report
        // cumulative / historical values and inflate observed_sats vs Electrum.
        long liveSats = 0L;
        for (BlockchainClient.AddressUtxo utxo : byOutpoint.values()) {
            if (utxo.valueSats() > 0L) {
                liveSats = Math.addExact(liveSats, utxo.valueSats());
            }
        }
        return new CollectResult(
                byTxid,
                java.util.Collections.unmodifiableSet(liveUnspentTxids),
                java.util.Collections.unmodifiableSet(liveOutpoints),
                liveSats,
                byOutpoint.size(),
                descriptorScanOk,
                addressMergeOk,
                hasDescriptor);
    }

    private void materializeMonitoredAddress(UUID walletId, String address) {
        if (address == null || address.isBlank()) {
            return;
        }
        String trimmed = address.trim();
        try {
            if (addressRepository.findFirstByAddressIgnoreCase(trimmed).isPresent()) {
                return;
            }
            source.kfe.model.KfeWalletAddressEntity row = new source.kfe.model.KfeWalletAddressEntity();
            row.setWalletId(walletId);
            row.setAddress(trimmed);
            row.setAddressRole(source.kfe.model.KfeWalletAddressRole.MONITOR);
            row.setStatus(source.kfe.model.KfeWalletAddressStatus.ACTIVE);
            addressRepository.save(row);
        } catch (RuntimeException ignored) {
            // unique race — ignore
        }
    }

    /**
     * Aligns UTXO set with Electrum: remove mempool-spent outpoints and re-add mempool
     * change/payment outputs that belong to this wallet.
     */
    private void applyMempoolSpendAdjustments(
            BlockchainClient client,
            UUID walletId,
            Map<String, BlockchainClient.AddressUtxo> byOutpoint) {
        java.util.Set<String> spendingTxids = new java.util.LinkedHashSet<>();
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        for (Map.Entry<String, BlockchainClient.AddressUtxo> entry : byOutpoint.entrySet()) {
            BlockchainClient.AddressUtxo utxo = entry.getValue();
            if (utxo.txid() == null || utxo.txid().isBlank()) {
                continue;
            }
            if (!client.isOutpointUnspentIncludingMempool(utxo.txid().trim(), utxo.vout())) {
                toRemove.add(entry.getKey());
                String spendTx = client.findSpendingTxid(utxo.txid().trim(), utxo.vout());
                if (spendTx != null && !spendTx.isBlank()) {
                    spendingTxids.add(spendTx.trim());
                }
            }
        }
        for (String key : toRemove) {
            byOutpoint.remove(key);
        }
        if (!toRemove.isEmpty()) {
            log.info(
                    "[KFE Cold Observation] mempool-filtered spentOutpoints={} spendingTxs={}",
                    toRemove.size(),
                    spendingTxids.size());
        }
        for (String spendTxid : spendingTxids) {
            addMempoolOutputsForWallet(client, walletId, spendTxid, byOutpoint);
        }
    }

    private void addMempoolOutputsForWallet(
            BlockchainClient client,
            UUID walletId,
            String spendTxid,
            Map<String, BlockchainClient.AddressUtxo> byOutpoint) {
        try {
            JsonNode raw = client.getRawTransaction(spendTxid, true);
            if (raw == null || raw.isNull()) {
                return;
            }
            JsonNode vouts = raw.path("vout");
            if (!vouts.isArray()) {
                return;
            }
            KfeMonitoredChainAddressIndex index = addressIndex.getIfAvailable();
            int confs = raw.path("confirmations").isIntegralNumber()
                    ? Math.max(0, raw.path("confirmations").asInt())
                    : 0;
            for (JsonNode vout : vouts) {
                if (!vout.path("n").isIntegralNumber()) {
                    continue;
                }
                int n = vout.path("n").asInt();
                String address = text(vout.path("scriptPubKey"), "address");
                if (address == null || address.isBlank()) {
                    JsonNode addrs = vout.path("scriptPubKey").path("addresses");
                    if (addrs.isArray() && !addrs.isEmpty()) {
                        address = addrs.get(0).asText();
                    }
                }
                if (address == null || address.isBlank()) {
                    continue;
                }
                boolean ours = false;
                KfeMonitoredChainAddressIndex idx = addressIndex.getIfAvailable();
                if (idx != null && walletId.equals(idx.walletIdForAddress(address))) {
                    ours = true;
                } else {
                    var row = addressRepository.findFirstByAddressIgnoreCase(address.trim());
                    if (row.isPresent() && walletId.equals(row.get().getWalletId())) {
                        ours = true;
                    }
                }
                if (!ours) {
                    continue;
                }
                long valueSats = amountFromBtcField(vout, "value");
                if (valueSats <= 0L) {
                    continue;
                }
                putOutpoint(
                        byOutpoint,
                        new BlockchainClient.AddressUtxo(
                                spendTxid,
                                n,
                                valueSats,
                                text(vout.path("scriptPubKey"), "hex"),
                                confs,
                                address.trim()),
                        address.trim());
                materializeMonitoredAddress(walletId, address.trim());
            }
        } catch (RuntimeException exception) {
            log.debug(
                    "[KFE Cold Observation] mempool output absorb failed txid={}: {}",
                    spendTxid,
                    exception.getMessage());
        }
    }

    private record CollectResult(
            Map<String, ObservedInbound> observations,
            java.util.Set<String> liveUnspentTxids,
            java.util.Set<String> liveOutpoints,
            long liveUnspentSats,
            int liveOutpointCount,
            boolean descriptorScanOk,
            boolean addressMergeOk,
            boolean hasDescriptor) {

        /**
         * Authoritative live total requires a completed descriptor scan when a descriptor exists.
         * Address-only soft success must not zero after a failed scantxoutset.
         * When no descriptor can be built, address merge alone is accepted.
         */
        boolean authoritativeLive() {
            if (hasDescriptor) {
                return descriptorScanOk;
            }
            return addressMergeOk;
        }
    }

    /** @return true if at least one descriptor scan completed without throwing */
    private boolean mergeDescriptorOutpoints(
            BlockchainClient client,
            KfeWalletEntity wallet,
            Map<String, BlockchainClient.AddressUtxo> byOutpoint) {
        String receive = descriptorResolver.resolveReceiveDescriptor(wallet);
        if (receive == null) {
            return false;
        }
        boolean ok = mergeScanOutpoints(client, receive, byOutpoint);
        String change = KfeWalletDescriptorResolver.toChangeDescriptor(receive);
        if (change != null) {
            // Both receive and change must succeed for authoritative live when change exists.
            boolean changeOk = mergeScanOutpoints(client, change, byOutpoint);
            ok = ok && changeOk;
        }
        return ok;
    }

    /** @return true if scan completed (even with zero UTXOs) */
    private boolean mergeScanOutpoints(
            BlockchainClient client,
            String descriptor,
            Map<String, BlockchainClient.AddressUtxo> byOutpoint) {
        try {
            for (BlockchainClient.AddressUtxo utxo :
                    client.getUnspentOutputsFromScan(descriptor, descriptorRange)) {
                putOutpoint(byOutpoint, utxo, utxo.address());
            }
            return true;
        } catch (RuntimeException exception) {
            log.debug("[KFE Cold Observation] descriptor scan failed: {}", exception.getMessage());
            return false;
        }
    }

    private static void putOutpoint(
            Map<String, BlockchainClient.AddressUtxo> byOutpoint,
            BlockchainClient.AddressUtxo utxo,
            String fallbackAddress) {
        if (utxo == null || utxo.txid() == null || utxo.txid().isBlank() || utxo.valueSats() <= 0L) {
            return;
        }
        String key = utxo.txid().trim().toLowerCase(java.util.Locale.ROOT) + ":" + utxo.vout();
        BlockchainClient.AddressUtxo existing = byOutpoint.get(key);
        if (existing == null || utxo.confirmations() > existing.confirmations()) {
            String address = utxo.address() != null ? utxo.address() : fallbackAddress;
            byOutpoint.put(
                    key,
                    new BlockchainClient.AddressUtxo(
                            utxo.txid().trim(),
                            utxo.vout(),
                            utxo.valueSats(),
                            utxo.scriptPubKey(),
                            Math.max(0, utxo.confirmations()),
                            address));
        }
    }

    private static final java.util.regex.Pattern TXID = java.util.regex.Pattern.compile("^[0-9a-fA-F]{64}$");

    private boolean looksLikeTxid(String value) {
        return value != null && TXID.matcher(value.trim()).matches();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText().trim() : null;
    }

    private String txidFromReceivedEntry(JsonNode entry) {
        String direct = text(entry, "txid");
        if (looksLikeTxid(direct)) {
            return direct;
        }
        JsonNode txids = entry.path("txids");
        if (txids.isArray() && txids.size() > 0) {
            String txid = txids.get(0).asText();
            return looksLikeTxid(txid) ? txid : null;
        }
        return null;
    }

    private int confirmations(JsonNode node) {
        JsonNode confirmations = node.path("confirmations");
        return confirmations.isIntegralNumber() ? Math.max(0, confirmations.asInt()) : 0;
    }

    private long amountSats(JsonNode node) {
        long sats = satsField(node, "sats", "satoshis", "amountSats", "amount_sats", "valueSats", "value_sats");
        if (sats > 0L) {
            return sats;
        }
        long amount = amountFromBtcField(node, "amount");
        if (amount > 0L) {
            return amount;
        }
        return amountFromBtcField(node, "value");
    }

    private long satsField(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isIntegralNumber()) {
                return Math.max(0L, value.asLong());
            }
            if (value.isTextual()) {
                try {
                    return Math.max(0L, Long.parseLong(value.asText()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return 0L;
    }

    private long amountFromBtcField(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isNumber()) {
            return 0L;
        }
        BigDecimal btc = value.decimalValue();
        if (btc.signum() <= 0) {
            return 0L;
        }
        return btc.multiply(new BigDecimal("100000000"))
                .setScale(0, RoundingMode.DOWN)
                .longValue();
    }

    private boolean upsertInboundObservation(KfeWalletEntity wallet, ObservedInbound inbound) {
        String idempotencyKey = "cold-obs:" + wallet.getId() + ":" + inbound.txid();
        KfeTransactionEntity existing =
                transactionRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        int confs = Math.max(0, inbound.confirmations());
        KfeTransactionStatus status =
                confs >= minConfirmations ? KfeTransactionStatus.SETTLED : KfeTransactionStatus.VALIDATING;

        if (existing != null) {
            boolean changed = false;
            if (confs > existing.getConfirmations()) {
                existing.setConfirmations(confs);
                changed = true;
            }
            if (inbound.amountSats() > existing.getReceiverAmountSats()) {
                existing.setGrossAmountSats(inbound.amountSats());
                existing.setReceiverAmountSats(inbound.amountSats());
                changed = true;
            }
            if (status == KfeTransactionStatus.SETTLED
                    && existing.getStatus() != KfeTransactionStatus.SETTLED) {
                existing.setStatus(KfeTransactionStatus.SETTLED);
                changed = true;
            }
            if (changed) {
                transactionRepository.save(existing);
                statementService.recordUserStatement(
                        wallet.getUserId(), wallet.getId(), existing, statementPayload(existing));
            }
            return changed;
        }

        // Avoid duplicating payment-request or PSBT rows that already use the same txid.
        if (!transactionRepository
                .findByBlockchainTxidAndUserId(inbound.txid(), wallet.getUserId())
                .isEmpty()) {
            return false;
        }

        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(wallet.getUserId());
        tx.setIdempotencyKey(idempotencyKey);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.INBOUND);
        tx.setDestinationWalletId(wallet.getId());
        tx.setExternalReference(inbound.address());
        tx.setMemo("Recebimento carteira fria");
        tx.setGrossAmountSats(inbound.amountSats());
        tx.setReceiverAmountSats(inbound.amountSats());
        tx.setNetworkFeeSats(0L);
        tx.setKeroseneFeeSats(0L);
        tx.setTotalDebitSats(0L);
        tx.setProvider(PROVIDER_COLD_OBSERVER);
        tx.setProviderReference(inbound.txid());
        tx.setBlockchainTxid(inbound.txid());
        tx.setConfirmations(confs);
        tx.setStatus(status);
        tx = transactionRepository.save(tx);
        statementService.recordUserStatement(
                wallet.getUserId(), wallet.getId(), tx, statementPayload(tx));
        notifyColdInboundSafe(wallet, tx);
        return true;
    }

    private void notifyColdInboundSafe(KfeWalletEntity wallet, KfeTransactionEntity tx) {
        FinancialNotificationPort port = notificationPort.getIfAvailable();
        if (port == null || tx == null) {
            return;
        }
        try {
            port.notifyDepositDetected(
                    wallet.getUserId(),
                    tx.getId(),
                    wallet.getId(),
                    "ONCHAIN",
                    Math.max(0L, tx.getReceiverAmountSats()),
                    Math.max(0, tx.getConfirmations()));
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Cold Observation] inbound notify failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
    }

    private void notifyColdOutboundSafe(KfeWalletEntity wallet, KfeTransactionEntity tx) {
        FinancialNotificationPort port = notificationPort.getIfAvailable();
        if (port == null || tx == null) {
            return;
        }
        try {
            if (tx.getStatus() == KfeTransactionStatus.SETTLED) {
                port.notifyOutboundConfirmed(
                        wallet.getUserId(),
                        tx.getId(),
                        wallet.getId(),
                        "ONCHAIN",
                        Math.max(0L, tx.getReceiverAmountSats()),
                        Math.max(0, tx.getConfirmations()));
            } else {
                port.notifyOutboundDetected(
                        wallet.getUserId(),
                        tx.getId(),
                        wallet.getId(),
                        "ONCHAIN",
                        Math.max(0L, tx.getReceiverAmountSats()),
                        Math.max(0, tx.getConfirmations()),
                        tx.getExternalReference());
            }
        } catch (RuntimeException exception) {
            log.warn(
                    "[KFE Cold Observation] outbound notify failed walletId={}: {}",
                    wallet.getId(),
                    exception.getMessage());
        }
    }

    private boolean refreshColdOutboundConfirmations(BlockchainClient client, UUID walletId) {
        // Include SETTLED so conf rings keep climbing past minConfirmations (e.g. 3 → 6).
        List<KfeTransactionEntity> open = transactionRepository.findByWalletIdAndStatusIn(
                walletId,
                List.of(
                        KfeTransactionStatus.EXECUTING,
                        KfeTransactionStatus.VALIDATING,
                        KfeTransactionStatus.SETTLED));
        boolean changed = false;
        for (KfeTransactionEntity tx : open) {
            if (!isColdObservation(tx) || tx.getBlockchainTxid() == null || tx.getBlockchainTxid().isBlank()) {
                continue;
            }
            // Stop hammering RPC once we have a full ring of confs.
            if (tx.getStatus() == KfeTransactionStatus.SETTLED && tx.getConfirmations() >= 6) {
                continue;
            }
            if (!(client instanceof source.kfe.rail.BitcoinCoreRpcClient core)) {
                // Generic client: try getrawtransaction confirmations field if present.
                try {
                    var raw = client.getRawTransaction(tx.getBlockchainTxid().trim(), true);
                    if (raw != null && raw.path("confirmations").isIntegralNumber()) {
                        if (touchColdConfirmations(tx.getId(), raw.path("confirmations").asInt())) {
                            changed = true;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // keep previous confs
                }
                continue;
            }
            var found = core.findTransactionConfirmations(tx.getBlockchainTxid().trim());
            if (found.isPresent() && touchColdConfirmations(tx.getId(), found.getAsInt())) {
                changed = true;
            }
        }
        return changed;
    }

    private long readObservedSats(UUID walletId) {
        return balanceRepository.findByWalletIds(List.of(walletId)).stream()
                .findFirst()
                .map(source.kfe.model.KfeBalanceEntity::getObservedSats)
                .orElse(0L);
    }

    /**
     * When chain observed balance drops without a PSBT row, materialize a single OUTBOUND for the
     * delta (covers Electrum spends of UTXOs that were never inbound-indexed).
     */
    private boolean recordExternalSpendFromBalanceDrop(
            KfeWalletEntity wallet, long previousObserved, long afterObserved) {
        if (previousObserved <= 0L || afterObserved < 0L || afterObserved >= previousObserved) {
            return false;
        }
        long spent = previousObserved - afterObserved;
        if (spent <= 0L) {
            return false;
        }
        String idempotencyKey =
                "cold-ext-delta:" + wallet.getId() + ":" + previousObserved + "->" + afterObserved;
        if (transactionRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return false;
        }
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(wallet.getUserId());
        tx.setIdempotencyKey(idempotencyKey);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setSourceWalletId(wallet.getId());
        tx.setMemo("Envio detectado (Electrum / carteira externa)");
        tx.setGrossAmountSats(spent);
        tx.setReceiverAmountSats(spent);
        tx.setNetworkFeeSats(0L);
        tx.setKeroseneFeeSats(0L);
        tx.setTotalDebitSats(spent);
        tx.setProvider(PROVIDER_COLD_EXTERNAL_SPEND);
        tx.setProviderReference(idempotencyKey);
        tx.setConfirmations(0);
        // Never SETTLED at 0 confs — FE would show fake "Confirmado 6/6".
        tx.setStatus(KfeTransactionStatus.VALIDATING);
        transactionRepository.save(tx);
        statementService.recordUserStatement(
                wallet.getUserId(), wallet.getId(), tx, statementPayload(tx));
        log.info(
                "[KFE Cold Observation] external spend from balance drop walletId={} spentSats={} {}->{}",
                wallet.getId(),
                spent,
                previousObserved,
                afterObserved);
        return true;
    }

    /**
     * When funding UTXOs previously indexed as cold inbounds are no longer unspent, create
     * OUTBOUND history rows so Electrum (or any external) spends appear in the app.
     *
     * <p>Uses live outpoints ({@code txid:vout}) so partial spends of multi-output fundings are
     * detected. Requires a non-null live set (empty after a full scan = fully spent; null = probe
     * failed — do not invent spends).
     */
    private boolean recordExternalSpendsForMissingUtxos(
            KfeWalletEntity wallet,
            java.util.Set<String> liveOutpoints,
            java.util.Set<String> liveUnspentTxids) {
        if (liveOutpoints == null && liveUnspentTxids == null) {
            return false;
        }
        List<KfeTransactionEntity> priorInbounds =
                transactionRepository.findByDestinationWalletIdAndProvider(
                        wallet.getId(), PROVIDER_COLD_OBSERVER);
        if (priorInbounds.isEmpty()) {
            return false;
        }
        java.util.Set<String> liveOps = new java.util.HashSet<>();
        if (liveOutpoints != null) {
            for (String op : liveOutpoints) {
                if (op != null && !op.isBlank()) {
                    liveOps.add(op.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        java.util.Set<String> liveTx = new java.util.HashSet<>();
        if (liveUnspentTxids != null) {
            for (String txid : liveUnspentTxids) {
                if (txid != null && !txid.isBlank()) {
                    liveTx.add(txid.trim().toLowerCase(java.util.Locale.ROOT));
                }
            }
        }
        BlockchainClient client = blockchainClient.getIfAvailable();
        boolean changed = false;
        for (KfeTransactionEntity inbound : priorInbounds) {
            String fundingTxid = inbound.getBlockchainTxid();
            if (fundingTxid == null || fundingTxid.isBlank()) {
                continue;
            }
            String key = fundingTxid.trim().toLowerCase(java.util.Locale.ROOT);
            boolean anyOutpointLive = false;
            if (!liveOps.isEmpty()) {
                String prefix = key + ":";
                for (String op : liveOps) {
                    if (op.startsWith(prefix)) {
                        anyOutpointLive = true;
                        break;
                    }
                }
            } else {
                // Fallback when only txid set is available (legacy callers / partial collect).
                anyOutpointLive = liveTx.contains(key);
            }
            if (anyOutpointLive) {
                // Partial spend: probe individual vouts that left the live set.
                if (recordPartialOutpointSpends(wallet, client, key, inbound, liveOps)) {
                    changed = true;
                }
                continue;
            }
            // Fully gone from live UTXO set — record whole funding as spent.
            if (materializeExternalSpend(wallet, client, key, null, inboundAmount(inbound))) {
                changed = true;
            }
        }
        return changed;
    }

    private static long inboundAmount(KfeTransactionEntity inbound) {
        long amount = Math.max(0L, inbound.getReceiverAmountSats());
        if (amount <= 0L) {
            amount = Math.max(0L, inbound.getGrossAmountSats());
        }
        return amount;
    }

    /**
     * When the funding txid still has at least one live outpoint, detect spent sibling vouts via
     * Core mempool/chain and materialize one OUTBOUND per spend tx.
     */
    private boolean recordPartialOutpointSpends(
            KfeWalletEntity wallet,
            BlockchainClient client,
            String fundingTxidKey,
            KfeTransactionEntity inbound,
            java.util.Set<String> liveOps) {
        if (client == null) {
            return false;
        }
        long totalInbound = inboundAmount(inbound);
        if (totalInbound <= 0L) {
            return false;
        }
        // Count live sibling outs for proportional fallback (amount unknown per vout).
        int liveSiblingCount = 0;
        String prefix = fundingTxidKey + ":";
        for (String op : liveOps) {
            if (op.startsWith(prefix)) {
                liveSiblingCount++;
            }
        }
        java.util.LinkedHashMap<String, Integer> spendTxToVout = new java.util.LinkedHashMap<>();
        for (int v = 0; v < 16; v++) {
            String outpoint = fundingTxidKey + ":" + v;
            if (liveOps.contains(outpoint)) {
                continue;
            }
            String spendTxid = client.findSpendingTxid(fundingTxidKey, v);
            if (spendTxid == null || spendTxid.isBlank()) {
                continue;
            }
            spendTxToVout.putIfAbsent(spendTxid.trim().toLowerCase(Locale.ROOT), v);
        }
        if (spendTxToVout.isEmpty()) {
            return false;
        }
        boolean changed = false;
        int spentParts = spendTxToVout.size();
        long perPart =
                spentParts + liveSiblingCount > 0
                        ? Math.max(1L, totalInbound / Math.max(1, spentParts + liveSiblingCount))
                        : totalInbound;
        for (Map.Entry<String, Integer> entry : spendTxToVout.entrySet()) {
            String spendTxid = entry.getKey();
            // Prefer resolveSpendDetails for accurate payment/fee when Core has the spend tx.
            long fallbackAmount =
                    spentParts == 1 && liveSiblingCount == 0 ? totalInbound : perPart;
            if (materializeExternalSpend(wallet, client, fundingTxidKey, spendTxid, fallbackAmount)) {
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Creates a single OUTBOUND external-spend row if not already present (by funding key, spend
     * tx key, or blockchain txid).
     */
    private boolean materializeExternalSpend(
            KfeWalletEntity wallet,
            BlockchainClient client,
            String fundingTxidKey,
            String knownSpendTxid,
            long amountHintSats) {
        if (amountHintSats <= 0L) {
            return false;
        }
        String spendTxid = knownSpendTxid;
        if ((spendTxid == null || spendTxid.isBlank()) && client != null) {
            for (int v = 0; v < 16 && (spendTxid == null || spendTxid.isBlank()); v++) {
                spendTxid = client.findSpendingTxid(fundingTxidKey, v);
            }
        }
        String idempotencyKey;
        if (spendTxid != null && !spendTxid.isBlank()) {
            String spendKey =
                    "cold-ext-spend-tx:"
                            + wallet.getId()
                            + ":"
                            + spendTxid.trim().toLowerCase(Locale.ROOT);
            if (transactionRepository.findByIdempotencyKey(spendKey).isPresent()) {
                return false;
            }
            boolean alreadyByTxid = transactionRepository
                    .findByBlockchainTxidAndUserId(spendTxid.trim(), wallet.getUserId())
                    .stream()
                    .anyMatch(row -> PROVIDER_COLD_EXTERNAL_SPEND.equals(row.getProvider())
                            && wallet.getId().equals(row.getSourceWalletId()));
            if (alreadyByTxid) {
                return false;
            }
            // Prefer spend-tx idempotency (ZMQ path) when we know the real spend.
            idempotencyKey = spendKey;
        } else {
            idempotencyKey = "cold-ext-spend:" + wallet.getId() + ":" + fundingTxidKey;
            if (transactionRepository.findByIdempotencyKey(idempotencyKey).isPresent()) {
                return false;
            }
        }
        // Also block legacy per-funding key when using spend-tx key.
        String legacyKey = "cold-ext-spend:" + wallet.getId() + ":" + fundingTxidKey;
        if (!idempotencyKey.equals(legacyKey)
                && transactionRepository.findByIdempotencyKey(legacyKey).isPresent()) {
            return false;
        }

        SpendDetails details = resolveSpendDetails(client, wallet.getId(), spendTxid, amountHintSats);
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setUserId(wallet.getUserId());
        tx.setIdempotencyKey(idempotencyKey);
        tx.setRail(KfeRail.ONCHAIN);
        tx.setDirection(KfeDirection.OUTBOUND);
        tx.setSourceWalletId(wallet.getId());
        tx.setExternalReference(details.destinationAddress());
        tx.setMemo(
                details.destinationAddress() != null
                        ? "Envio Electrum"
                        : "Envio detectado (Electrum / carteira externa)");
        tx.setGrossAmountSats(details.paymentSats());
        tx.setReceiverAmountSats(details.paymentSats());
        tx.setNetworkFeeSats(details.feeSats());
        tx.setKeroseneFeeSats(0L);
        tx.setTotalDebitSats(Math.addExact(details.paymentSats(), details.feeSats()));
        tx.setProvider(PROVIDER_COLD_EXTERNAL_SPEND);
        tx.setProviderReference(spendTxid != null ? spendTxid : fundingTxidKey);
        tx.setBlockchainTxid(spendTxid != null ? spendTxid : fundingTxidKey);
        tx.setConfirmations(details.confirmations());
        // Never mark SETTLED at 0 confs — FE maps SETTLED → "Confirmado" + fake 6 rings.
        tx.setStatus(
                details.confirmations() >= minConfirmations
                        ? KfeTransactionStatus.SETTLED
                        : KfeTransactionStatus.VALIDATING);
        tx = transactionRepository.save(tx);
        statementService.recordUserStatement(
                wallet.getUserId(), wallet.getId(), tx, statementPayload(tx));
        notifyColdOutboundSafe(wallet, tx);
        log.info(
                "[KFE Cold Observation] external spend recorded walletId={} fundingTxid={} spendTxid={} paymentSats={} dest={} confs={}",
                wallet.getId(),
                fundingTxidKey,
                spendTxid,
                details.paymentSats(),
                details.destinationAddress(),
                details.confirmations());
        return true;
    }

    private record SpendDetails(
            String destinationAddress, long paymentSats, long feeSats, int confirmations) {
    }

    private SpendDetails resolveSpendDetails(
            BlockchainClient client, UUID walletId, String spendTxid, long fundingAmountSats) {
        long payment = Math.max(0L, fundingAmountSats);
        long fee = 0L;
        int confs = 0;
        String dest = null;
        if (client == null || spendTxid == null || spendTxid.isBlank()) {
            return new SpendDetails(null, payment, fee, confs);
        }
        try {
            JsonNode raw = client.getRawTransaction(spendTxid.trim(), true);
            if (raw == null || raw.isNull()) {
                return new SpendDetails(null, payment, fee, confs);
            }
            if (raw.path("confirmations").isIntegralNumber()) {
                confs = Math.max(0, raw.path("confirmations").asInt());
            }
            JsonNode vouts = raw.path("vout");
            long changeToUs = 0L;
            long externalOut = 0L;
            if (vouts.isArray()) {
                for (JsonNode vout : vouts) {
                    long valueSats = amountFromBtcField(vout, "value");
                    if (valueSats <= 0L) {
                        continue;
                    }
                    String address = text(vout.path("scriptPubKey"), "address");
                    if (address == null || address.isBlank()) {
                        JsonNode addrs = vout.path("scriptPubKey").path("addresses");
                        if (addrs.isArray() && !addrs.isEmpty()) {
                            address = addrs.get(0).asText();
                        }
                    }
                    boolean ours = false;
                    if (address != null && !address.isBlank()) {
                        KfeMonitoredChainAddressIndex idx = addressIndex.getIfAvailable();
                        if (idx != null && walletId.equals(idx.walletIdForAddress(address))) {
                            ours = true;
                        } else {
                            var row = addressRepository.findFirstByAddressIgnoreCase(address.trim());
                            ours = row.isPresent() && walletId.equals(row.get().getWalletId());
                        }
                    }
                    if (ours) {
                        changeToUs = Math.addExact(changeToUs, valueSats);
                    } else {
                        externalOut = Math.addExact(externalOut, valueSats);
                        if (dest == null && address != null && !address.isBlank()) {
                            dest = address.trim();
                        }
                    }
                }
            }
            // Payment to external = funding - change back to us (fee is residual).
            if (externalOut > 0L) {
                payment = externalOut;
                long residual = fundingAmountSats - changeToUs - externalOut;
                fee = Math.max(0L, residual);
            } else if (changeToUs > 0L && changeToUs < fundingAmountSats) {
                // Self-send / consolidate: only fee left the wallet.
                payment = 0L;
                fee = Math.max(0L, fundingAmountSats - changeToUs);
                if (fee <= 0L) {
                    payment = fundingAmountSats;
                }
            }
            if (payment <= 0L && fee > 0L) {
                payment = fee; // show fee as movement when pure consolidation
                fee = 0L;
                if (dest == null) {
                    dest = "Consolidação / troco";
                }
            }
        } catch (RuntimeException exception) {
            log.debug(
                    "[KFE Cold Observation] resolveSpendDetails failed txid={}: {}",
                    spendTxid,
                    exception.getMessage());
        }
        return new SpendDetails(dest, Math.max(0L, payment), Math.max(0L, fee), confs);
    }

    static boolean isColdObservation(KfeTransactionEntity tx) {
        if (tx == null) {
            return false;
        }
        String provider = tx.getProvider();
        return PROVIDER_COLD_OBSERVER.equals(provider)
                || PROVIDER_COLD_PSBT.equals(provider)
                || PROVIDER_COLD_EXTERNAL_SPEND.equals(provider);
    }

    private static UUID firstWallet(KfeTransactionEntity tx) {
        if (tx.getSourceWalletId() != null) {
            return tx.getSourceWalletId();
        }
        return tx.getDestinationWalletId();
    }

    private static Map<String, Object> statementPayload(KfeTransactionEntity tx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transactionId", tx.getId().toString());
        payload.put("id", tx.getId().toString());
        payload.put("status", tx.getStatus().name());
        payload.put("displayStatus", KfeTransactionStatus.displayStatusOf(tx.getStatus()));
        payload.put("rail", tx.getRail().name());
        payload.put("direction", tx.getDirection().name());
        payload.put("grossAmountSats", tx.getGrossAmountSats());
        payload.put("receiverAmountSats", tx.getReceiverAmountSats());
        payload.put("networkFeeSats", tx.getNetworkFeeSats());
        payload.put("confirmations", tx.getConfirmations());
        payload.put("provider", tx.getProvider());
        payload.put("blockchainTxid", tx.getBlockchainTxid());
        payload.put("memo", tx.getMemo());
        payload.put("externalReference", tx.getExternalReference());
        if (tx.getCreatedAt() != null) {
            payload.put("createdAt", tx.getCreatedAt().atZone(java.time.ZoneOffset.UTC).toInstant());
        }
        if (tx.getUpdatedAt() != null) {
            payload.put("updatedAt", tx.getUpdatedAt().atZone(java.time.ZoneOffset.UTC).toInstant());
        }
        // Wallet ids so FE can filter cold history without relying on dashboard only.
        if (tx.getSourceWalletId() != null) {
            payload.put("sourceWalletId", tx.getSourceWalletId().toString());
            payload.put("walletId", tx.getSourceWalletId().toString());
        }
        if (tx.getDestinationWalletId() != null) {
            payload.put("destinationWalletId", tx.getDestinationWalletId().toString());
            payload.putIfAbsent("walletId", tx.getDestinationWalletId().toString());
        }
        return payload;
    }

    private record ObservedInbound(String txid, long amountSats, int confirmations, String address) {
    }
}
