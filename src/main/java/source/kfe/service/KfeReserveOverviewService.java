package source.kfe.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.dto.KfeReserveOverviewResponse;
import source.kfe.model.KfeBalanceEntity;
import source.kfe.model.KfeWalletEntity;
import source.kfe.model.KfeWalletKind;
import source.kfe.repository.KfeBalanceRepository;
import source.kfe.repository.KfeWalletRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class KfeReserveOverviewService {

    private final KfeBalanceRepository balanceRepository;
    private final KfeWalletRepository walletRepository;

    public KfeReserveOverviewService(
            KfeBalanceRepository balanceRepository,
            KfeWalletRepository walletRepository) {
        this.balanceRepository = balanceRepository;
        this.walletRepository = walletRepository;
    }

    @Transactional(readOnly = true)
    public KfeReserveOverviewResponse overview() {
        List<KfeBalanceEntity> balances = balanceRepository.findAll();
        Map<UUID, KfeWalletKind> kinds = new HashMap<>();
        for (KfeWalletEntity wallet : walletRepository.findAll()) {
            if (wallet.getId() != null && wallet.getKind() != null) {
                kinds.put(wallet.getId(), wallet.getKind());
            }
        }

        long availableSats = 0;
        long pendingSats = 0;
        long lockedSats = 0;
        long holdSats = 0;
        // Cold observed only — never sum custodial observed with available (dual-ledger).
        long coldObservedSats = 0;
        for (KfeBalanceEntity balance : balances) {
            UUID walletId = balance.getId() != null ? balance.getId().getWalletId() : null;
            KfeWalletKind kind = walletId != null ? kinds.get(walletId) : null;
            if (kind == KfeWalletKind.WATCH_ONLY) {
                coldObservedSats += balance.getObservedSats();
                continue;
            }
            // System + internal + custodial: ledger buckets only for platform reserve.
            availableSats += balance.getAvailableSats();
            pendingSats += balance.getPendingSats();
            lockedSats += balance.getLockedSats();
            holdSats += balance.getAutoHoldSats();
        }
        long reservedSats = lockedSats + holdSats;
        // Platform spendable + cold observed (user-visible assets under observation).
        long totalSats = availableSats + pendingSats + reservedSats + coldObservedSats;
        return new KfeReserveOverviewResponse(
                btc(totalSats), 0.0, 0.0, 0.0,
                btc(reservedSats), 0.0,
                btc(availableSats), 0.0,
                availableSats > 0,
                state(availableSats, reservedSats, coldObservedSats));
    }

    private String state(long availableSats, long reservedSats, long observedSats) {
        if (availableSats <= 0 && reservedSats <= 0 && observedSats <= 0) return "EMPTY";
        if (availableSats <= 0) return "RESERVED";
        if (reservedSats > availableSats) return "TIGHT";
        return "HEALTHY";
    }

    private double btc(long sats) {
        return sats / 100_000_000.0;
    }
}
