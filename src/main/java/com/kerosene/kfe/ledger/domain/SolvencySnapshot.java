package com.kerosene.kfe.ledger.domain;

/** Immutable solvency result computed from a single liability/asset snapshot. */
public record SolvencySnapshot(
        long totalLiabilitiesSats,
        long eligibleAssetsSats,
        long safetyBufferSats,
        long requiredAssetsSats,
        double coverageRatio,
        double minimumCoverageRatio,
        boolean solvent,
        String status) {
}
