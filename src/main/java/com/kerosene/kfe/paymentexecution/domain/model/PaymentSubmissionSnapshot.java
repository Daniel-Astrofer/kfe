package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Intent identity read under the execution lock, before its first financial preparation. */
public record PaymentSubmissionSnapshot(PaymentExecutionId executionId, long userId, ExecutionStatus status,
        IdempotencyKey idempotencyKey, PaymentRail rail, PaymentDirection direction, UUID sourceWalletId,
        UUID destinationWalletId, long amountSats, String externalReference) {
    public PaymentSubmissionSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(idempotencyKey, "idempotency key is required");
        Objects.requireNonNull(rail, "rail is required");
        Objects.requireNonNull(direction, "direction is required");
        if (userId <= 0L || amountSats <= 0L) { throw new IllegalArgumentException("submission identity and amount must be valid"); }
    }
    public void requireReadyFor(long authenticatedUserId, PaymentExecutionId requestedId,
            String rawExternalReference, String rawPublicId) {
        if (userId != authenticatedUserId || !executionId.equals(requestedId) || status != ExecutionStatus.INTENT
                || (rail == PaymentRail.INTERNAL) != (direction == PaymentDirection.INTERNAL)) {
            throw new IllegalStateException("Payment is not eligible for submission preparation.");
        }
        String publicId = clean(rawPublicId);
        String expectedReference = publicId != null ? publicId : clean(rawExternalReference);
        if (!Objects.equals(expectedReference, externalReference)
                || (publicId != null && rail != PaymentRail.INTERNAL)) {
            throw new IllegalArgumentException("Submission references do not match the persisted payment intent.");
        }
    }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    @Override public String toString() {
        return "PaymentSubmissionSnapshot[executionId=" + executionId + ", userId=" + userId
                + ", status=" + status + ", rail=" + rail + ", direction=" + direction + ", references=REDACTED]";
    }
}
