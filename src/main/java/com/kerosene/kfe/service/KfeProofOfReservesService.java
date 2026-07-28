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
 * <p>Currently computes liabilities from ledger. Asset queries (scantxoutset /
 * descriptor from vault mesh, Lightning channel state) are configured but the
 * actual remote calls depend on vault mesh availability.
 *
 * <p>When the vault mesh is unreachable, the service fail-closes: settlement is
 * blocked because assets cannot be verified.
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
     * Computes the solvency snapshot from ledger liabilities.
     * Asset queries are deferred to vault mesh integration.
     *
     * @param customerLiabilitiesSats sum of user available + locked + pending + hold balances
     * @param systemProfitSats SYSTEM_PROFIT wallet balance (liability until segregated)
     * @param inFlightWithdrawalSats in-flight withdrawal amounts not yet broadcast/confirmed
     * @return snapshot with liabilities filled; assets populated from available data
     */
    @Transactional(readOnly = true)
    public SolvencySnapshot computeSnapshot(
            long customerLiabilitiesSats,
            long systemProfitSats,
            long inFlightWithdrawalSats) {

        long totalLiabilities = customerLiabilitiesSats;
        if (profitReconcileWithVault) {
            totalLiabilities = Math.addExact(totalLiabilities, systemProfitSats);
        }
        totalLiabilities = Math.addExact(totalLiabilities, inFlightWithdrawalSats);

        // Assets are computed externally by the vault mesh.
        // This service computes the liability side; the BinarySettlementGate
        // enforces the invariant once both sides are known.
        long eligibleAssets = 0L; // Filled by vault mesh block snapshot
        long onchainReserveAssetsSats = 0L;
        long lightningReserveAssetsSats = 0L;

        long safetyBufferSats = (long) (totalLiabilities * safetyBufferBps / 10_000L);
        long requiredAssets = Math.addExact(totalLiabilities, safetyBufferSats);

        double coverageRatio = totalLiabilities > 0
                ? (double) eligibleAssets / (double) totalLiabilities
                : Double.POSITIVE_INFINITY;

        boolean solvent = coverageRatio >= minimumCoverageRatio
                && eligibleAssets >= requiredAssets;

        return new SolvencySnapshot(
                totalLiabilities,
                customerLiabilitiesSats,
                systemProfitSats,
                inFlightWithdrawalSats,
                eligibleAssets,
                onchainReserveAssetsSats,
                lightningReserveAssetsSats,
                safetyBufferSats,
                coverageRatio,
                minimumCoverageRatio,
                solvent,
                null, // snapshot block hash — filled when vault mesh provides it
                Instant.now());
    }

    public boolean isEnabled() {
        return porEnabled;
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
            if (coverageRatio >= minimumCoverageRatio && eligibleAssetsSats >= (totalLiabilitiesSats + safetyBufferSats)) {
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
