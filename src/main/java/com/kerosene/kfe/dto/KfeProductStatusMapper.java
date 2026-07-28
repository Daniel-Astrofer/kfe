package com.kerosene.kfe.dto;

import com.kerosene.kfe.model.KfeTransactionStatus;

/**
 * Maps internal {@link KfeTransactionStatus} enum values to the canonical 7-state
 * product status vocabulary exposed to clients.
 *
 * <p>Product states: PENDING, PROCESSING, CONFIRMING, COMPLETED, FAILED,
 * NEEDS_REVIEW, REVERSED.</p>
 *
 * <p>Unknown or null values map to PENDING (fail-safe).</p>
 *
 * @see docs/ops/PRODUCT_STATE_MAPPING.md
 */
public final class KfeProductStatusMapper {

    private KfeProductStatusMapper() {
        // utility class
    }

    /**
     * Maps an internal transaction status to its canonical product status.
     *
     * @param internalStatus the internal {@link KfeTransactionStatus} value
     * @return product status string (one of PENDING, PROCESSING, CONFIRMING, COMPLETED, FAILED, NEEDS_REVIEW, REVERSED)
     */
    public static String toProductStatus(KfeTransactionStatus internalStatus) {
        if (internalStatus == null) {
            return "PENDING";
        }
        return switch (internalStatus) {
            case INTENT -> "PENDING";
            case VALIDATING, QUORUM_SYNC, LOCKED, EXECUTING -> "PROCESSING";
            case BROADCAST, CONFIRMING -> "CONFIRMING";
            case SETTLED -> "COMPLETED";
            case FAILED, CANCELLED, ABANDONED, DROPPED -> "FAILED";
            case CONFLICTED -> "FAILED"; // override to NEEDS_REVIEW if confirmations were seen (see doc)
            case CONFLICTED_RECONCILING, REQUIRES_RECONCILIATION, REORG_RECONCILIATION -> "NEEDS_REVIEW";
            case CONFLICTED_REFUNDED -> "COMPLETED";
            // FUTURE: FINALIZED → COMPLETED, REVERSED → REVERSED
        };
    }

    /**
     * Maps an internal transaction status to its canonical product status,
     * with an optional override hint for CONFLICTED.
     *
     * @param internalStatus the internal {@link KfeTransactionStatus} value
     * @param hadConfirmations true if at least 1 confirmation was seen before conflict
     * @return product status string
     */
    public static String toProductStatus(KfeTransactionStatus internalStatus, boolean hadConfirmations) {
        if (internalStatus == KfeTransactionStatus.CONFLICTED && hadConfirmations) {
            return "NEEDS_REVIEW";
        }
        return toProductStatus(internalStatus);
    }

    /**
     * Maps from a raw string representation of the internal enum name.
     * Safely returns PENDING for null, blank, or unrecognized values.
     *
     * @param rawStatus the raw status string (enum name)
     * @return product status string
     */
    public static String toProductStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "PENDING";
        }
        try {
            return toProductStatus(KfeTransactionStatus.valueOf(rawStatus.trim().toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            return "PENDING";
        }
    }
}
