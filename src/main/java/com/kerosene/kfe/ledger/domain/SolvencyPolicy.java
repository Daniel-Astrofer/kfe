package com.kerosene.kfe.ledger.domain;

import java.math.BigInteger;

/** Pure, fail-closed solvency calculation with checked configuration and arithmetic. */
public final class SolvencyPolicy {
    private static final BigInteger TEN_THOUSAND = BigInteger.valueOf(10_000L);
    private final Config config;

    public SolvencyPolicy(Config config) {
        this.config = config;
    }

    public SolvencySnapshot evaluate(long customerLiabilitiesSats, long systemProfitSats,
            long inFlightWithdrawalSats, long eligibleAssetsSats) {
        nonNegative(customerLiabilitiesSats, "customerLiabilitiesSats");
        nonNegative(systemProfitSats, "systemProfitSats");
        nonNegative(inFlightWithdrawalSats, "inFlightWithdrawalSats");
        nonNegative(eligibleAssetsSats, "eligibleAssetsSats");

        BigInteger total = BigInteger.valueOf(customerLiabilitiesSats)
                .add(config.reconcileProfit ? BigInteger.valueOf(systemProfitSats) : BigInteger.ZERO)
                .add(BigInteger.valueOf(inFlightWithdrawalSats));
        long totalLong = total.longValueExact();
        long buffer = total.multiply(BigInteger.valueOf(config.safetyBufferBps))
                .divide(TEN_THOUSAND).longValueExact();
        long required = Math.addExact(totalLong, buffer);
        double coverage = totalLong == 0L
                ? Double.POSITIVE_INFINITY
                : (double) eligibleAssetsSats / (double) totalLong;
        boolean assetsKnown = eligibleAssetsSats > 0L || totalLong > 0L;
        boolean solvent = assetsKnown && coverage >= config.minimumCoverageRatio
                && eligibleAssetsSats >= required;
        String status = !assetsKnown ? "UNKNOWN"
                : coverage < 1.0 ? "INSOLVENT"
                : !solvent ? "NEAR_INSOLVENT"
                : "SOLVENT";
        return new SolvencySnapshot(totalLong, eligibleAssetsSats, buffer, required, coverage,
                config.minimumCoverageRatio, solvent, status);
    }

    public record Config(long safetyBufferBps, double minimumCoverageRatio, boolean reconcileProfit) {
        public Config {
            if (safetyBufferBps < 0L || safetyBufferBps > 10_000L) {
                throw new IllegalArgumentException("safetyBufferBps must be between 0 and 10000");
            }
            if (!Double.isFinite(minimumCoverageRatio) || minimumCoverageRatio < 1.0) {
                throw new IllegalArgumentException("minimumCoverageRatio must be finite and at least 1.0");
            }
        }
    }

    private static void nonNegative(long value, String name) {
        if (value < 0L) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
    }
}
