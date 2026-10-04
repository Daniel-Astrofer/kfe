package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

/** Validated initial intent, before pricing, authorization gate, reservations, or execution. */
public record PaymentIntent(
        long userId,
        IdempotencyKey idempotencyKey,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountSats,
        String externalReference,
        String memo) {

    private static final long MAX_SATOSHIS = 2_100_000_000_000_000L;

    public PaymentIntent {
        if (userId <= 0L) {
            throw new IllegalArgumentException("authenticated user id must be positive");
        }
        if (idempotencyKey == null || rail == null || direction == null) {
            throw new IllegalArgumentException("idempotency key, rail and direction are required");
        }
        if (amountSats <= 0L) {
            throw new IllegalArgumentException("amountSats must be positive.");
        }
        if (amountSats > MAX_SATOSHIS) {
            throw new IllegalArgumentException("amountSats exceeds maximum allowed limit (21M BTC).");
        }
        if (rail == PaymentRail.INTERNAL && direction != PaymentDirection.INTERNAL) {
            throw new IllegalArgumentException("INTERNAL rail requires INTERNAL direction.");
        }
        if (direction == PaymentDirection.INTERNAL && rail != PaymentRail.INTERNAL) {
            throw new IllegalArgumentException("INTERNAL direction requires INTERNAL rail.");
        }
    }

    @Override
    public String toString() {
        return "PaymentIntent[userId=" + userId + ", rail=" + rail + ", direction=" + direction
                + ", amountSats=" + amountSats + ", references=REDACTED]";
    }
}
