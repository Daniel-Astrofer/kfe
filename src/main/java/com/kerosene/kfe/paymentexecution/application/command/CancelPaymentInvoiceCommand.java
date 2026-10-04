package com.kerosene.kfe.paymentexecution.application.command;

/** Invoice identity for a cancellation authorized by the owning payment request. */
public record CancelPaymentInvoiceCommand(
        long userId,
        String paymentHash,
        String providerReference,
        String paymentRequest) {

    public CancelPaymentInvoiceCommand {
        if (userId <= 0) {
            throw new IllegalArgumentException("user id is required");
        }
    }
}
