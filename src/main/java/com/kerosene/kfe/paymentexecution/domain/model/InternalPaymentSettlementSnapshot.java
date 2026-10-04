package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Authoritative financial inputs read while the caller holds the execution lock. */
public record InternalPaymentSettlementSnapshot(
        PaymentExecutionId executionId, long userId, ExecutionStatus status,
        PaymentRail rail, PaymentDirection direction, UUID sourceWalletId, UUID destinationWalletId,
        long recipientUserId, long totalDebitSats, long receiverAmountSats) {

    public InternalPaymentSettlementSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(rail, "rail is required");
        Objects.requireNonNull(direction, "direction is required");
        Objects.requireNonNull(sourceWalletId, "source wallet is required");
        Objects.requireNonNull(destinationWalletId, "destination wallet is required");
        if (userId <= 0L || recipientUserId <= 0L) {
            throw new IllegalArgumentException("settlement participant ids must be positive");
        }
        if (receiverAmountSats <= 0L || totalDebitSats < receiverAmountSats) {
            throw new IllegalArgumentException("internal settlement amounts are inconsistent");
        }
    }

    public void requireReadyFor(long authenticatedUserId, PaymentExecutionId requestedId) {
        if (userId != authenticatedUserId || !executionId.equals(requestedId)
                || rail != PaymentRail.INTERNAL || direction != PaymentDirection.INTERNAL
                || status != ExecutionStatus.LOCKED || sourceWalletId.equals(destinationWalletId)) {
            throw new IllegalStateException("Internal payment is not eligible for settlement.");
        }
    }

    public boolean hasAnotherRecipient() {
        return userId != recipientUserId;
    }
}
