package com.kerosene.kfe.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Granular Bitcoin finality policy for the deposit lifecycle.
 *
 * <p>Deposit stages: DETECTED → CONFIRMING → CREDITED → FINALIZED
 *
 * <p>Rules:
 * <ol>
 *   <li>Credit at {@code creditConfirmations} makes funds available, monitoring continues.</li>
 *   <li>Monitor until {@code finalityConfirmations} window.</li>
 *   <li>Reorg before credit: remove expectation, notify "deposit not confirmed".</li>
 *   <li>Reorg after credit: lock equivalent amount, create compensating entry.</li>
 *   <li>Risk-based policy by amount (not enforced here — callers evaluate cutoff).</li>
 * </ol>
 */
@ConfigurationProperties(prefix = "bitcoin")
public class KfeBitcoinFinalityPolicy {

    private static final Logger log = LoggerFactory.getLogger(KfeBitcoinFinalityPolicy.class);

    /** Mempool visibility (0-conf). Default 0. */
    private int detectedConfirmations = 0;

    /** Confirmations required to credit available_sats. Production must be >= 1. */
    private int creditConfirmations = 3;

    /** Confirmations after which monitoring stops (funds considered irreversible). */
    private int finalityConfirmations = 6;

    /** Extended watch window for reorg detection beyond finality. */
    private int reorgMonitorConfirmations = 12;

    @PostConstruct
    void validateProductionGate() {
        if (creditConfirmations < 1) {
            String msg = "bitcoin.credit-confirmations must be >= 1 in production. Current: "
                    + creditConfirmations;
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        log.info(
                "Bitcoin finality policy: detected={} credit={} finality={} reorg-monitor={}",
                detectedConfirmations,
                creditConfirmations,
                finalityConfirmations,
                reorgMonitorConfirmations);
    }

    /** Whether the tx is merely detected (mempool). */
    public boolean isDetected(int confirmations) {
        return confirmations >= detectedConfirmations;
    }

    /** Whether confirmations satisfy the credit threshold. */
    public boolean isCreditReady(int confirmations) {
        return confirmations >= creditConfirmations;
    }

    /** Whether confirmations satisfy the finality (irreversible) threshold. */
    public boolean isFinalized(int confirmations) {
        return confirmations >= finalityConfirmations;
    }

    /** Whether the extended reorg monitoring window still applies. */
    public boolean isWithinReorgWindow(int confirmations) {
        return confirmations < reorgMonitorConfirmations;
    }

    /**
     * Risk-based required confirmations by amount.
     * <p>Up to {@code lowSats} → 1 conf, between → 3 confs, above → 6 confs.
     */
    public int requiredConfirmationsForAmount(long amountSats, long lowSats, long highSats) {
        if (amountSats <= lowSats) {
            return 1;
        }
        if (amountSats <= highSats) {
            return 3;
        }
        return 6;
    }

    public int getDetectedConfirmations() {
        return detectedConfirmations;
    }

    public void setDetectedConfirmations(int detectedConfirmations) {
        this.detectedConfirmations = detectedConfirmations;
    }

    public int getCreditConfirmations() {
        return creditConfirmations;
    }

    public void setCreditConfirmations(int creditConfirmations) {
        this.creditConfirmations = creditConfirmations;
    }

    public int getFinalityConfirmations() {
        return finalityConfirmations;
    }

    public void setFinalityConfirmations(int finalityConfirmations) {
        this.finalityConfirmations = finalityConfirmations;
    }

    public int getReorgMonitorConfirmations() {
        return reorgMonitorConfirmations;
    }

    public void setReorgMonitorConfirmations(int reorgMonitorConfirmations) {
        this.reorgMonitorConfirmations = reorgMonitorConfirmations;
    }
}
