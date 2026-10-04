package com.kerosene.kfe.paymentexecution.domain.model;

/** Client-scoped key that identifies one semantic payment submission. */
public record IdempotencyKey(String value) {

    public static final int MAX_LENGTH = 180;

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("idempotency key must not exceed " + MAX_LENGTH + " characters");
        }
    }
}
