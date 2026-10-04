package com.kerosene.kfe.paymentexecution.domain.model;

public record SettlementSolvencySnapshot(boolean solvent, double coverageRatio, double minimumCoverageRatio,
        long totalLiabilitiesSats, long eligibleAssetsSats, long safetyBufferSats) {}
