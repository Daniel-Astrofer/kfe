package com.kerosene.kfe.paymentexecution.application.result;

public record PaymentRecipient(long userId, boolean active) {
    public PaymentRecipient {
        if (userId <= 0L) { throw new IllegalArgumentException("recipient id must be positive"); }
    }
}
