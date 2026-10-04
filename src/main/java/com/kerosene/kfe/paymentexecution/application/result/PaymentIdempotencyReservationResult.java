package com.kerosene.kfe.paymentexecution.application.result;

/** PENDING/conflict are failures; only a new reservation may start financial effects. */
public record PaymentIdempotencyReservationResult(boolean reserved, PaymentExecutionResult existingPayment) {
    public PaymentIdempotencyReservationResult {
        if (reserved == (existingPayment != null)) {
            throw new IllegalArgumentException("Expected either a new reservation or an existing payment.");
        }
    }
    public static PaymentIdempotencyReservationResult reservedNew() { return new PaymentIdempotencyReservationResult(true, null); }
    public static PaymentIdempotencyReservationResult replay(PaymentExecutionResult payment) {
        return new PaymentIdempotencyReservationResult(false, payment);
    }
    @Override public String toString() {
        return "PaymentIdempotencyReservationResult[reserved=" + reserved + ", existingPayment=REDACTED]";
    }
}
