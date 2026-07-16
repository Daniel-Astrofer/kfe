package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import source.kfe.model.KfeBalanceEntity;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.repository.KfeBalanceRepository;
import source.kfe.repository.KfeWalletRepository;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;

@Service
public class KfeBalanceService {

    private static final Logger log = LoggerFactory.getLogger(KfeBalanceService.class);

    private final KfeBalanceRepository balanceRepository;
    private final KfeHashService hashService;
    private final KfeWalletRepository walletRepository;
    private final BalanceEventPublisher balanceEventPublisher;

    public KfeBalanceService(KfeBalanceRepository balanceRepository,
                             KfeHashService hashService,
                             KfeWalletRepository walletRepository,
                             BalanceEventPublisher balanceEventPublisher) {
        this.balanceRepository = balanceRepository;
        this.hashService = hashService;
        this.walletRepository = walletRepository;
        this.balanceEventPublisher = balanceEventPublisher;
    }

    public KfeBalanceEntity createEmptyBalance(UUID walletId, String asset) {
        String normalizedAsset = asset != null ? asset : "BTC";
        String initialHash = hashService.initialBalanceHash(walletId.toString(), normalizedAsset);
        KfeBalanceEntity balance = KfeBalanceEntity.empty(walletId, normalizedAsset, initialHash);
        balance.setBalanceSignature(hashService.balanceHash(balance));
        return balanceRepository.save(balance);
    }

    public KfeBalanceEntity requireForUpdate(UUID walletId, String asset) {
        return balanceRepository.findByWalletIdAndAssetForUpdate(walletId, asset != null ? asset : "BTC")
                .orElseThrow(() -> new IllegalArgumentException("KFE balance not found for wallet " + walletId + "."));
    }

    public KfeBalanceEntity reserve(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        balance.reserve(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(walletId, saved, -amountSats, "reserva", "AVAILABLE");
        return saved;
    }

    public KfeBalanceEntity settleReservedDebit(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        balance.settleReservedDebit(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(walletId, saved, 0L, "liquidação de débito", "LOCKED");
        return saved;
    }

    public KfeBalanceEntity releaseReserved(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        balance.releaseReserved(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(walletId, saved, amountSats, "liberação de reserva", "AVAILABLE");
        return saved;
    }

    public KfeBalanceEntity creditAvailable(UUID walletId, String asset, long amountSats) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        balance.creditAvailable(amountSats);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(walletId, saved, amountSats, "crédito", "AVAILABLE");
        return saved;
    }

    public KfeBalanceEntity setObserved(UUID walletId, String asset, long observedSats) {
        return setObserved(walletId, asset, observedSats, null, null);
    }

    /**
     * Absolute observed write with optional probe metadata (quality / source) for monotonic policy.
     */
    public KfeBalanceEntity setObserved(
            UUID walletId,
            String asset,
            long observedSats,
            String probeQuality,
            String probeSource) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        long oldObserved = balance.getObservedSats();
        balance.setObservedBalance(observedSats);
        if (probeQuality != null && !probeQuality.isBlank()) {
            balance.setObservedProbeMeta(
                    probeQuality.trim(),
                    java.time.LocalDateTime.now(),
                    probeSource != null && !probeSource.isBlank() ? probeSource.trim() : null);
        }
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(walletId, saved, observedSats - oldObserved, "observado", "OBSERVED");
        return saved;
    }

    /**
     * Increments observed balance. Prefer {@link #setObserved} with an absolute chain probe for
     * WATCH_ONLY / CUSTODIAL_ONCHAIN — observed must reflect the blockchain, not a private ledger.
     */
    public KfeBalanceEntity creditObserved(UUID walletId, String asset, long amountSats) {
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("observed credit amount must be positive.");
        }
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        long next = balance.getObservedSats() + amountSats;
        balance.setObservedBalance(next);
        sign(balance);
        KfeBalanceEntity saved = balanceRepository.save(balance);
        publishBalanceSnapshot(walletId, saved, amountSats, "crédito observado", "OBSERVED");
        return saved;
    }

    /**
     * Cold wallets must never carry spendable internal buckets. Clears available/pending/locked/auto-hold
     * if any non-zero residual leaked in (defensive).
     */
    public KfeBalanceEntity zeroSpendableBucketsIfNeeded(UUID walletId, String asset) {
        KfeBalanceEntity balance = requireForUpdate(walletId, asset);
        if (balance.getAvailableSats() == 0L
                && balance.getPendingSats() == 0L
                && balance.getLockedSats() == 0L
                && balance.getAutoHoldSats() == 0L) {
            return balance;
        }
        log.warn(
                "Clearing non-zero spendable buckets on cold/watch-only walletId={} available={} pending={} locked={} autoHold={}",
                walletId,
                balance.getAvailableSats(),
                balance.getPendingSats(),
                balance.getLockedSats(),
                balance.getAutoHoldSats());
        balance.setAvailableSats(0L);
        balance.setPendingSats(0L);
        balance.setLockedSats(0L);
        balance.setAutoHoldSats(0L);
        balance.setNonce(balance.getNonce() + 1);
        sign(balance);
        return balanceRepository.save(balance);
    }

    private void sign(KfeBalanceEntity balance) {
        String hash = hashService.balanceHash(balance);
        balance.setLastHash(hash);
        balance.setBalanceSignature(hash);
    }

    private void publishBalanceSnapshot(
            UUID walletId,
            KfeBalanceEntity balance,
            long deltaSats,
            String context,
            String bucket) {
        try {
            walletRepository.findById(walletId).ifPresent(wallet -> {
                KfeWalletKind kind = wallet.getKind() != null ? wallet.getKind() : KfeWalletKind.INTERNAL;
                long primarySats = primarySatsFor(kind, balance);
                BigDecimal newBalance = BigDecimal.valueOf(primarySats).movePointLeft(8);
                BigDecimal amount = BigDecimal.valueOf(deltaSats).movePointLeft(8);
                BalanceUpdateEvent event = new BalanceUpdateEvent(
                        wallet.getId().toString(),
                        wallet.getLabel(),
                        wallet.getUserId(),
                        newBalance,
                        amount,
                        context,
                        kind.name(),
                        balance.getAvailableSats(),
                        balance.getLockedSats(),
                        balance.getPendingSats(),
                        balance.getObservedSats(),
                        primarySats,
                        bucket);
                balanceEventPublisher.publishBalanceUpdateAfterCommit(event);
            });
        } catch (Exception e) {
            log.error("Failed to publish balance update for walletId={}", walletId, e);
        }
    }

    /** Primary UI balance: cold=observed; spendable kinds=available. */
    static long primarySatsFor(KfeWalletKind kind, KfeBalanceEntity balance) {
        if (kind == KfeWalletKind.WATCH_ONLY) {
            return balance.getObservedSats();
        }
        return balance.getAvailableSats();
    }

    static long primarySatsFor(KfeWalletEntity wallet, KfeBalanceEntity balance) {
        KfeWalletKind kind = wallet != null && wallet.getKind() != null
                ? wallet.getKind()
                : KfeWalletKind.INTERNAL;
        return primarySatsFor(kind, balance);
    }

    @SuppressWarnings("unused")
    private static String normalizeContext(String context) {
        return context == null ? "" : context.trim().toLowerCase(Locale.ROOT);
    }
}
