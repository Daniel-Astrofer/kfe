package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Financial inputs reloaded under the execution lock, not supplied by a transport caller. */
public record PaymentFundsReservationSnapshot(
        PaymentExecutionId executionId, long userId, ExecutionStatus status,
        PaymentRail rail, PaymentDirection direction, UUID sourceWalletId,
        long totalDebitSats, String proposalHash, int quorumAckCount) {

    public PaymentFundsReservationSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(rail, "rail is required");
        Objects.requireNonNull(direction, "direction is required");
        if (userId <= 0L || totalDebitSats < 0L || quorumAckCount < 0) {
            throw new IllegalArgumentException("reservation identity, amount and quorum count must be valid");
        }
    }

    public void requireReadyFor(long authenticatedUserId, PaymentExecutionId requestedId) {
        if (userId != authenticatedUserId || !executionId.equals(requestedId)
                || status != ExecutionStatus.QUORUM_SYNC
                || (rail == PaymentRail.INTERNAL) != (direction == PaymentDirection.INTERNAL)
                || proposalHash == null || proposalHash.isBlank()
                || (requiresSourceReserve() && (sourceWalletId == null || totalDebitSats <= 0L))) {
            throw new IllegalStateException("Payment is not eligible for funds reservation.");
        }
    }

    public boolean requiresSourceReserve() {
        return direction == PaymentDirection.OUTBOUND || direction == PaymentDirection.INTERNAL;
    }

    public boolean requiresLightningLiquidity() {
        return rail == PaymentRail.LIGHTNING && direction == PaymentDirection.OUTBOUND;
    }

    @Override
    public String toString() {
        return "PaymentFundsReservationSnapshot[executionId=" + executionId + ", userId=" + userId
                + ", status=" + status + ", rail=" + rail + ", direction=" + direction
                + ", totalDebitSats=" + totalDebitSats + ", quorumAckCount=" + quorumAckCount
                + ", proposal=REDACTED]";
    }
}
