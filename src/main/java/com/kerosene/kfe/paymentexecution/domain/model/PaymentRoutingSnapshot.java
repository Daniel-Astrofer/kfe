package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Authoritative route, amounts and identity read under the execution lock. */
public record PaymentRoutingSnapshot(PaymentExecutionId executionId, long userId, ExecutionStatus status,
        PaymentRail rail, PaymentDirection direction, IdempotencyKey idempotencyKey,
        UUID sourceWalletId, UUID destinationWalletId, long grossAmountSats, long receiverAmountSats,
        long networkFeeSats, long totalDebitSats, String externalReference, String memo, String proposalHash) {
    public PaymentRoutingSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(rail, "rail is required");
        Objects.requireNonNull(direction, "direction is required");
        Objects.requireNonNull(idempotencyKey, "idempotency key is required");
        if (userId <= 0L || grossAmountSats <= 0L || receiverAmountSats <= 0L || networkFeeSats < 0L || totalDebitSats < 0L) {
            throw new IllegalArgumentException("routing identity and financial amounts must be valid");
        }
    }

    public void requireReadyFor(long authenticatedUserId, PaymentExecutionId requestedId) {
        if (userId != authenticatedUserId || !executionId.equals(requestedId) || status != ExecutionStatus.LOCKED
                || (rail == PaymentRail.INTERNAL) != (direction == PaymentDirection.INTERNAL)
                || (direction != PaymentDirection.INBOUND && (sourceWalletId == null || totalDebitSats <= 0L))
                || (direction != PaymentDirection.OUTBOUND && destinationWalletId == null)
                || proposalHash == null || proposalHash.isBlank()) {
            throw new IllegalStateException("Payment is not eligible for routing.");
        }
    }

    public UUID statementWalletId() {
        return direction == PaymentDirection.INBOUND ? destinationWalletId : sourceWalletId;
    }

    @Override
    public String toString() {
        return "PaymentRoutingSnapshot[executionId=" + executionId + ", userId=" + userId + ", status=" + status
                + ", rail=" + rail + ", direction=" + direction + ", references=REDACTED]";
    }
}
