package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Persisted submission identity after routing, read under the execution lock. */
public record PaymentSubmissionCompletionSnapshot(PaymentExecutionId executionId, long userId,
        IdempotencyKey idempotencyKey, ExecutionStatus status, PaymentRail rail, PaymentDirection direction,
        UUID destinationWalletId) {
    public PaymentSubmissionCompletionSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(idempotencyKey, "idempotency key is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(rail, "rail is required");
        Objects.requireNonNull(direction, "direction is required");
        if (userId <= 0L) { throw new IllegalArgumentException("execution owner must be positive"); }
    }
    public void requireReadyFor(long authenticatedUserId, PaymentExecutionId requestedId, IdempotencyKey requestedKey) {
        boolean internal = rail == PaymentRail.INTERNAL && direction == PaymentDirection.INTERNAL;
        boolean external = rail != PaymentRail.INTERNAL && direction != PaymentDirection.INTERNAL;
        if (userId != authenticatedUserId || !executionId.equals(requestedId) || !idempotencyKey.equals(requestedKey)
                || !(internal && status == ExecutionStatus.SETTLED || external && status == ExecutionStatus.EXECUTING)
                || direction != PaymentDirection.OUTBOUND && destinationWalletId == null) {
            throw new IllegalStateException("Payment is not eligible for submission completion.");
        }
    }
    @Override public String toString() {
        return "PaymentSubmissionCompletionSnapshot[executionId=" + executionId + ", userId=" + userId
                + ", status=" + status + ", rail=" + rail + ", direction=" + direction + ", idempotency=REDACTED]";
    }
}
