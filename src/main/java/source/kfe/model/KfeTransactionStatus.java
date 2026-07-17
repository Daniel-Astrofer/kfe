package source.kfe.model;

public enum KfeTransactionStatus {
    INTENT,
    VALIDATING,
    QUORUM_SYNC,
    LOCKED,
    EXECUTING,
    SETTLED,
    FAILED,
    REQUIRES_RECONCILIATION;

    /**
     * Coarse status for UI badges. Order/date stay fixed; only this label moves
     * PENDING → CONFIRMED / FAILED as the ledger advances.
     */
    public String displayStatus() {
        return switch (this) {
            case SETTLED -> "CONFIRMED";
            case FAILED, REQUIRES_RECONCILIATION -> "FAILED";
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
