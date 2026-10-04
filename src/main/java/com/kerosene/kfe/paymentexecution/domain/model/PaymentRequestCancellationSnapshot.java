package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Immutable request state read while the caller holds the request cancellation lock. */
public record PaymentRequestCancellationSnapshot(
        UUID id,
        long userId,
        UUID walletId,
        String publicId,
        PaymentRequestCancellationStatus status,
        PaymentRail rail,
        String paymentHash,
        String providerReference,
        String paymentRequest,
        UUID paidTransactionId) {

    public PaymentRequestCancellationSnapshot {
        Objects.requireNonNull(id, "payment request id is required");
        Objects.requireNonNull(status, "payment request status is required");
        Objects.requireNonNull(rail, "payment rail is required");
        if (userId <= 0L) {
            throw new IllegalArgumentException("user id must be positive");
        }
    }

    public boolean cancellable() {
        return status.cancellable();
    }

    public boolean invoiceCancellationRequired() {
        return rail == PaymentRail.LIGHTNING
                && (hasText(paymentHash) || hasText(providerReference) || hasText(paymentRequest));
    }

    @Override
    public String toString() {
        return "PaymentRequestCancellationSnapshot[id=" + id
                + ", userId=" + userId + ", status=" + status + ", rail=" + rail + "]";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
