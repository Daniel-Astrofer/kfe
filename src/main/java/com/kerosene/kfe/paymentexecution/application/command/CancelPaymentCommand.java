package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Authenticated intent to cancel a participant-visible payment. */
public record CancelPaymentCommand(long userId, PaymentExecutionId paymentExecutionId) {

    public CancelPaymentCommand {
        if (userId <= 0 || paymentExecutionId == null) {
            throw new IllegalArgumentException("user id and payment execution id are required");
        }
    }
}
