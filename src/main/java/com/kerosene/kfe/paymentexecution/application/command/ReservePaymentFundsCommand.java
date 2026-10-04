package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Internal submit step after authorization and gate approval, never an independent payment endpoint. */
public record ReservePaymentFundsCommand(long userId, PaymentExecutionId executionId) {
    public ReservePaymentFundsCommand {
        if (userId <= 0L || executionId == null) {
            throw new IllegalArgumentException("user id and execution id are required");
        }
    }
}
