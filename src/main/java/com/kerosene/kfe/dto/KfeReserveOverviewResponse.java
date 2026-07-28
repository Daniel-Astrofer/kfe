package com.kerosene.kfe.dto;

import java.time.Instant;

/**
 * KFE reserve overview (ITEM 9 — vault mesh boundary audit).
 *
 * <p>Reports assets and liabilities separately. Never sums them into a single "total".
 * The status reflects real solvency, not a single aggregated number.
 */
public record KfeReserveOverviewResponse(
        // --- Liabilities (what we owe users) ---
        long customerLiabilitiesSats,
        long lockedLiabilitiesSats,
        long pendingLiabilitiesSats,

        // --- Assets (what backs the liabilities) ---
        long onchainReserveAssetsSats,
        long lightningReserveAssetsSats,
        long unconfirmedAssetsSats,
        long encumberedAssetsSats,

        // --- Derived ---
        long equitySats,            // assets - liabilities
        double coverageRatio,       // assets / liabilities
        String snapshotBlockHash,
        Instant snapshotAt,
        String status               // SOLVENT, NEAR_INSOLVENT, INSOLVENT, UNKNOWN
) {}
