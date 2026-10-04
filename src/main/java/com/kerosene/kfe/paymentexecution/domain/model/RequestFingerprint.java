package com.kerosene.kfe.paymentexecution.domain.model;

/** Stable fingerprint of the fields that determine payment execution semantics. */
public record RequestFingerprint(String value) {
    public RequestFingerprint {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("request fingerprint is required");
        }
    }
}
