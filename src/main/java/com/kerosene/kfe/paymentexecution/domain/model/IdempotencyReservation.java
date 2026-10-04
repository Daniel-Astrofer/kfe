package com.kerosene.kfe.paymentexecution.domain.model;

import com.kerosene.kfe.paymentexecution.domain.exception.IdempotencyKeyConflict;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentExecutionStillProcessing;

/** Domain representation of a client idempotency reservation. */
public final class IdempotencyReservation {

    private final IdempotencyKey key;
    private final RequestFingerprint fingerprint;
    private PaymentExecutionId executionId;

    private IdempotencyReservation(
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            PaymentExecutionId executionId) {
        if (key == null || fingerprint == null) {
            throw new IllegalArgumentException("idempotency key and request fingerprint are required");
        }
        this.key = key;
        this.fingerprint = fingerprint;
        this.executionId = executionId;
    }

    public static IdempotencyReservation pending(IdempotencyKey key, RequestFingerprint fingerprint) {
        return new IdempotencyReservation(key, fingerprint, null);
    }

    public static IdempotencyReservation reconstitute(
            IdempotencyKey key,
            RequestFingerprint fingerprint,
            PaymentExecutionId executionId) {
        return new IdempotencyReservation(key, fingerprint, executionId);
    }

    public void assertSameRequest(RequestFingerprint candidate) {
        if (!fingerprint.equals(candidate)) {
            throw new IdempotencyKeyConflict();
        }
    }

    public PaymentExecutionId completedExecutionId() {
        if (executionId == null) {
            throw new PaymentExecutionStillProcessing();
        }
        return executionId;
    }

    public boolean isPending() {
        return executionId == null;
    }

    public void complete(PaymentExecutionId completedExecutionId) {
        if (completedExecutionId == null) {
            throw new IllegalArgumentException("completed execution id is required");
        }
        if (executionId != null && !executionId.equals(completedExecutionId)) {
            throw new IdempotencyKeyConflict();
        }
        executionId = completedExecutionId;
    }

    public IdempotencyKey key() {
        return key;
    }

    public RequestFingerprint fingerprint() {
        return fingerprint;
    }
}
