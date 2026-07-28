package com.kerosene.kfe.model;

public enum KfeTransactionStatus {
    INTENT,
    VALIDATING,
    QUORUM_SYNC,
    LOCKED,
    EXECUTING,
    BROADCAST,
    CONFIRMING,
    SETTLED,
    FAILED,
    CANCELLED,
    REQUIRES_RECONCILIATION,
    CONFLICTED,
    CONFLICTED_RECONCILING,
    CONFLICTED_REFUNDED,
    REORG_RECONCILIATION,
    DROPPED,
    ABANDONED;

    /**
     * Coarse status for UI badges. Order/date stay fixed; only this label moves
     * PENDING → CONFIRMED / FAILED as the ledger advances.
     */
    public String displayStatus() {
        return switch (this) {
            case SETTLED -> "CONFIRMED";
            case FAILED, CANCELLED, REQUIRES_RECONCILIATION, CONFLICTED,
                 CONFLICTED_RECONCILING, CONFLICTED_REFUNDED,
                 REORG_RECONCILIATION,
                 DROPPED, ABANDONED -> "FAILED";
            default -> "PENDING";
        };
    }

    public static String displayStatusOf(KfeTransactionStatus status) {
        return status == null ? "PENDING" : status.displayStatus();
    }

    public static String displayStatusOf(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "PENDING";
        }
        try {
            return KfeTransactionStatus.valueOf(rawStatus.trim().toUpperCase()).displayStatus();
        } catch (IllegalArgumentException ignored) {
            return "PENDING";
        }
    }
}
