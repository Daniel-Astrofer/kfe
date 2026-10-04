package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Issued by the authorized submit flow after gate approval and reservation, in its transaction. */
public record SettleInternalPaymentCommand(long userId, PaymentExecutionId executionId) {
    public SettleInternalPaymentCommand {
        if (userId <= 0L || executionId == null) {
            throw new IllegalArgumentException("user id and execution id are required");
        }
    }
}
