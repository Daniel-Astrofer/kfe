package com.kerosene.kfe.paymentexecution.domain.model;

/** Request-state projection used by Payment Execution, not the full payment-request aggregate. */
public enum PaymentRequestCancellationStatus {
    OPEN, PAID, EXPIRED, HIDDEN, CANCELLED, FAILED;

    public boolean cancellable() {
        return this == OPEN || this == EXPIRED;
    }
}
