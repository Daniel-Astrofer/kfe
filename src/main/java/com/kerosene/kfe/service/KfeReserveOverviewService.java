package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.dto.KfeReserveOverviewResponse;
import com.kerosene.kfe.model.KfeBalanceEntity;
import com.kerosene.kfe.model.KfeWalletEntity;
import com.kerosene.kfe.model.KfeWalletKind;
import com.kerosene.kfe.repository.KfeBalanceRepository;
import com.kerosene.kfe.repository.KfeWalletRepository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * KFE reserve overview (ITEM 9 — vault mesh boundary audit).
 *
 * <p>Reports assets and liabilities separately. Never sums them into a single "total".
 * Liability buckets (AVAILABLE, PENDING, LOCKED) are never added to on-chain assets (observed),
 * as that would double-count.
 */
@Service
public class KfeReserveOverviewService {

    private static final Logger log = LoggerFactory.getLogger(KfeReserveOverviewService.class);

    private final KfeBalanceRepository balanceRepository;
    private final KfeWalletRepository walletRepository;

    public KfeReserveOverviewService(
            KfeBalanceRepository balanceRepository,
            KfeWalletRepository walletRepository) {
        this.balanceRepository = balanceRepository;
        this.walletRepository = walletRepository;
    }

    /**
     * Returns the reserve overview with separate asset and liability sections.
     *
     * <p>Liabilities = sum of user-facing balance buckets (available + pending + locked + hold).
     * Assets = on-chain observed + Lightning channel balance (queried separately).
     * Coverage ratio = assets / liabilities.
     * Status = SOLVENT (>= 1.0), NEAR_INSOLVENT (0.5..1.0), INSOLVENT (< 0.5), UNKNOWN.
     *
     * <p>WATCH_ONLY cold wallets contribute to assets (observed) but NOT to liabilities
     * (they are user-controlled, not platform-liable).
     */
    @Transactional(readOnly = true)
    public KfeReserveOverviewResponse overview() {
        List<KfeBalanceEntity> balances = balanceRepository.findAll();
        Map<UUID, KfeWalletKind> kinds = new HashMap<>();
        for (KfeWalletEntity wallet : walletRepository.findAll()) {
            if (wallet.getId() != null && wallet.getKind() != null) {
                kinds.put(wallet.getId(), wallet.getKind());
            }
        }

        // --- Liabilities: what we owe users ---
        long customerLiabilitiesSats = 0L;   // available across all non-WATCH_ONLY wallets
        long lockedLiabilitiesSats = 0L;      // locked + auto_hold (owed but not spendable)
        long pendingLiabilitiesSats = 0L;     // pending deposits not yet credited

        // --- Assets: what backs the liabilities ---
        long onchainReserveAssetsSats = 0L;   // confirmed UTXOs via vault mesh (observed for now)
        long lightningReserveAssetsSats = 0L; // Lightning channel local balance
        long unconfirmedAssetsSats = 0L;      // mempool deposits not yet confirmed
        long encumberedAssetsSats = 0L;       // UTXOs locked in HTLCs / channel opens

        for (KfeBalanceEntity balance : balances) {
            UUID walletId = balance.getId() != null ? balance.getId().getWalletId() : null;
            KfeWalletKind kind = walletId != null ? kinds.get(walletId) : null;

            if (kind == KfeWalletKind.WATCH_ONLY) {
                // Cold wallets: observed is an asset we can see but not a liability
                onchainReserveAssetsSats += balance.getObservedSats();
                continue;
            }

            // Custodial + internal + system wallets: these are liabilities
            customerLiabilitiesSats += balance.getAvailableSats();
            pendingLiabilitiesSats += balance.getPendingSats();
            lockedLiabilitiesSats += Math.addExact(balance.getLockedSats(), balance.getAutoHoldSats());

            // observed_sats for custodial wallets = on-chain UTXO mirror (asset side)
            onchainReserveAssetsSats += balance.getObservedSats();
        }

        long totalLiabilities = customerLiabilitiesSats + lockedLiabilitiesSats + pendingLiabilitiesSats;
        long totalAssets = onchainReserveAssetsSats + lightningReserveAssetsSats
                + unconfirmedAssetsSats - encumberedAssetsSats;

        long equitySats = totalAssets - totalLiabilities;
        double coverageRatio = totalLiabilities > 0
                ? (double) totalAssets / (double) totalLiabilities
                : Double.POSITIVE_INFINITY;

        String status = computeStatus(coverageRatio, totalAssets, totalLiabilities);

        return new KfeReserveOverviewResponse(
                customerLiabilitiesSats,
                lockedLiabilitiesSats,
                pendingLiabilitiesSats,
                onchainReserveAssetsSats,
                lightningReserveAssetsSats,
                unconfirmedAssetsSats,
                encumberedAssetsSats,
                equitySats,
                coverageRatio,
                null, // snapshotBlockHash — filled when vault mesh provides block context
                Instant.now(),
                status);
    }

    private String computeStatus(double coverageRatio, long totalAssets, long totalLiabilities) {
        if (totalLiabilities <= 0 && totalAssets <= 0) {
            return "UNKNOWN";
        }
        if (coverageRatio >= 1.0) {
            return "SOLVENT";
        }
        if (coverageRatio >= 0.5) {
            return "NEAR_INSOLVENT";
        }
        return "INSOLVENT";
    }
}
