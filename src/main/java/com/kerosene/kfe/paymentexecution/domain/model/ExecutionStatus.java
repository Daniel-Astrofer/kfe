package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.EnumSet;
import java.util.Set;

/** Authoritative payment execution lifecycle independent from persistence and transport. */
public enum ExecutionStatus {
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

    public boolean canTransitionTo(ExecutionStatus target) {
        if (target == null) {
            return false;
        }
        if (this == target) {
            return true;
        }
        return allowedTargets().contains(target);
    }

    public String displayStatus() {
        return switch (this) {
            case SETTLED -> "CONFIRMED";
            case FAILED, CANCELLED, REQUIRES_RECONCILIATION, CONFLICTED,
                    CONFLICTED_RECONCILING, CONFLICTED_REFUNDED,
                    REORG_RECONCILIATION, DROPPED, ABANDONED -> "FAILED";
            default -> "PENDING";
        };
    }

    public String productStatus() {
        return productStatus(false);
    }

    public String productStatus(boolean hadConfirmations) {
        if (this == CONFLICTED && hadConfirmations) {
            return "NEEDS_REVIEW";
        }
        return switch (this) {
            case INTENT -> "PENDING";
            case VALIDATING, QUORUM_SYNC, LOCKED, EXECUTING -> "PROCESSING";
            case BROADCAST, CONFIRMING -> "CONFIRMING";
            case SETTLED, CONFLICTED_REFUNDED -> "COMPLETED";
            case FAILED, CANCELLED, ABANDONED, DROPPED, CONFLICTED -> "FAILED";
            case CONFLICTED_RECONCILING, REQUIRES_RECONCILIATION, REORG_RECONCILIATION ->
                    "NEEDS_REVIEW";
        };
    }

    private Set<ExecutionStatus> allowedTargets() {
        return switch (this) {
            case INTENT -> EnumSet.of(VALIDATING, FAILED);
            case VALIDATING -> EnumSet.of(QUORUM_SYNC, FAILED, CANCELLED);
            case QUORUM_SYNC -> EnumSet.of(LOCKED, FAILED, REQUIRES_RECONCILIATION);
            case LOCKED -> EnumSet.of(EXECUTING, SETTLED, FAILED, CANCELLED, REQUIRES_RECONCILIATION);
            case EXECUTING -> EnumSet.of(
                    BROADCAST, SETTLED, FAILED, CONFLICTED_RECONCILING, REQUIRES_RECONCILIATION);
            case BROADCAST, CONFIRMING -> EnumSet.of(
                    CONFIRMING, SETTLED, FAILED, CONFLICTED_RECONCILING, REQUIRES_RECONCILIATION);
            case SETTLED -> EnumSet.of(REORG_RECONCILIATION);
            case CONFLICTED_RECONCILING -> EnumSet.of(FAILED, BROADCAST, REQUIRES_RECONCILIATION);
            case CONFLICTED_REFUNDED -> EnumSet.of(REQUIRES_RECONCILIATION);
            case REORG_RECONCILIATION -> EnumSet.of(SETTLED, FAILED, REQUIRES_RECONCILIATION);
            case REQUIRES_RECONCILIATION -> EnumSet.of(EXECUTING, SETTLED, FAILED);
            case FAILED, CANCELLED, CONFLICTED, DROPPED, ABANDONED -> EnumSet.noneOf(ExecutionStatus.class);
        };
    }
}
