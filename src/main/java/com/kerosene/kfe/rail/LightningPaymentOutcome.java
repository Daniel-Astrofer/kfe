package com.kerosene.kfe.rail;

/**
 * Normalized LND / Lightning payment terminal classification for binary settlement.
 */
public enum LightningPaymentOutcome {
    SUCCEEDED,
    FAILED,
    IN_FLIGHT,
    UNKNOWN;

    public static LightningPaymentOutcome fromProviderStatus(String status) {
        if (status == null || status.isBlank()) {
            return UNKNOWN;
        }
        String normalized = status.trim().toUpperCase();
        return switch (normalized) {
            case "SUCCEEDED", "SUCCESS", "COMPLETE", "COMPLETED", "PAID", "SETTLED" -> SUCCEEDED;
            case "FAILED", "FAILURE", "ERROR", "CANCELLED", "CANCELED", "TIMEOUT" -> FAILED;
            case "IN_FLIGHT", "INFLIGHT", "PENDING", "IN_PROGRESS", "SENDING" -> IN_FLIGHT;
            default -> UNKNOWN;
        };
    }
}
