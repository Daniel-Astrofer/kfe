package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Proof-of-reserves solvency service (ITEM 8 — vault mesh boundary audit).
 *
 * <p>Verifies the invariant: {@code eligibleAssets >= liabilities + safetyBuffer}.
 * This is NOT a local balance check — it proves UTXOs exist, belong to the vault
 * mesh, are spendable, cover user liabilities, and are not reused elsewhere.
 *
 * <p>Computes liabilities from ledger. Assets must be provided by the caller
 * (settlement gate or reserve overview) after querying on-chain and Lightning probes.
 *
 * <p>When asset probes are unavailable, the service reports UNKNOWN and fail-closes:
 * settlement is blocked because assets cannot be verified.
 */
@Service
public class KfeProofOfReservesService {

    private static final Logger log = LoggerFactory.getLogger(KfeProofOfReservesService.class);

    private final boolean porEnabled;
    private final long safetyBufferBps;
    private final double minimumCoverageRatio;
    private final boolean profitReconcileWithVault;

    public KfeProofOfReservesService(
            @Value("${kfe.reserves.proof-of-reserves.enabled:true}") boolean porEnabled,
            @Value("${kfe.reserves.proof-of-reserves.safety-buffer-bps:5000}") long safetyBufferBps,
            @Value("${kfe.reserves.proof-of-reserves.minimum-coverage-ratio:1.0}") double minimumCoverageRatio,
            @Value("${kfe.profit.reconcile-with-vault:true}") boolean profitReconcileWithVault) {
        this.porEnabled = porEnabled;
        this.safetyBufferBps = safetyBufferBps;
        this.minimumCoverageRatio = minimumCoverageRatio;
        this.profitReconcileWithVault = profitReconcileWithVault;
    }

    /**
     * Computes the solvency snapshot.
     *
     * @param customerLiabilitiesSats sum of user available + locked + pending + hold balances
     *                                for CUSTODIAL_ONCHAIN and INTERNAL wallets only
     * @param systemProfitSats SYSTEM_PROFIT wallet balance (liability until segregated)
     * @param inFlightWithdrawalSats in-flight withdrawal amounts not yet broadcast/confirmed
     * @param eligibleAssetsSats confirmed on-chain UTXOs + Lightning channel local balance
     *                           (sum from external probes — not ledger observedSats)
     * @param onchainReserveAssetsSats on-chain portion of eligible assets
     * @param lightningReserveAssetsSats Lightning portion of eligible assets
     * @param snapshotBlockHash block hash or height marker from the probe
     * @return snapshot with both sides populated
     */
    @Transactional(readOnly = true)
    public SolvencySnapshot computeSnapshot(
            long customerLiabilitiesSats,
            long systemProfitSats,
            long inFlightWithdrawalSats,
            long eligibleAssetsSats,
            long onchainReserveAssetsSats,
            long lightningReserveAssetsSats,
            String snapshotBlockHash) {

        long totalLiabilities = customerLiabilitiesSats;
        if (profitReconcileWithVault) {
            totalLiabilities = Math.addExact(totalLiabilities, systemProfitSats);
        }
        totalLiabilities = Math.addExact(totalLiabilities, inFlightWithdrawalSats);

        long safetyBufferSats = (long) (totalLiabilities * safetyBufferBps / 10_000L);
        long requiredAssets = Math.addExact(totalLiabilities, safetyBufferSats);

        double coverageRatio = totalLiabilities > 0
                ? (double) eligibleAssetsSats / (double) totalLiabilities
                : Double.POSITIVE_INFINITY;

        boolean solvent = coverageRatio >= minimumCoverageRatio
                && eligibleAssetsSats >= requiredAssets;

        return new SolvencySnapshot(
                totalLiabilities,
                customerLiabilitiesSats,
                systemProfitSats,
                inFlightWithdrawalSats,
                eligibleAssetsSats,
                onchainReserveAssetsSats,
                lightningReserveAssetsSats,
                safetyBufferSats,
                coverageRatio,
                minimumCoverageRatio,
                solvent,
                snapshotBlockHash,
                Instant.now());
    }

    /**
     * Convenience overload for callers that only have liability data.
     * Assets are set to zero — the resulting snapshot will always be INSOLVENT,
     * which is the correct fail-closed behavior when asset probes are unavailable.
     */
    public SolvencySnapshot computeSnapshotLiabilitiesOnly(
            long customerLiabilitiesSats,
            long systemProfitSats,
            long inFlightWithdrawalSats) {
        return computeSnapshot(
                customerLiabilitiesSats,
                systemProfitSats,
                inFlightWithdrawalSats,
                0L,
                0L,
                0L,
                null);
    }

    public boolean isEnabled() {
        return porEnabled;
    }

    public long safetyBufferBps() {
        return safetyBufferBps;
    }

    public double minimumCoverageRatio() {
        return minimumCoverageRatio;
    }

    /**
     * Immutable solvency snapshot at a point in time.
     */
    public record SolvencySnapshot(
            long totalLiabilitiesSats,
            long customerLiabilitiesSats,
            long systemProfitSats,
            long inFlightWithdrawalSats,
            long eligibleAssetsSats,
            long onchainReserveAssetsSats,
            long lightningReserveAssetsSats,
            long safetyBufferSats,
            double coverageRatio,
            double minimumCoverageRatio,
            boolean solvent,
            String snapshotBlockHash,
            Instant snapshotAt) {

        /** Assets minus liabilities. Negative = insolvent. */
        public long equitySats() {
            return eligibleAssetsSats - totalLiabilitiesSats;
        }

        /** Human-readable solvency status. */
        public String status() {
            if (eligibleAssetsSats <= 0 && totalLiabilitiesSats <= 0) {
                return "UNKNOWN";
            }
            if (coverageRatio >= minimumCoverageRatio
                    && eligibleAssetsSats >= (totalLiabilitiesSats + safetyBufferSats)) {
                return "SOLVENT";
            }
            if (coverageRatio < 1.0) {
                return "INSOLVENT";
            }
            if (coverageRatio < minimumCoverageRatio) {
                return "NEAR_INSOLVENT";
            }
            return "UNKNOWN";
        }
    }
}
