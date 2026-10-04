package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Internal submit context; monetary inputs and routing come from the locked execution. */
public record RouteLockedPaymentCommand(long userId, PaymentExecutionId executionId,
        String externalReference, String memo, Long feeRateSatPerVbyte, Integer feeTargetBlocks) {
    public RouteLockedPaymentCommand {
        if (userId <= 0L || executionId == null) {
            throw new IllegalArgumentException("authenticated user and execution id are required");
        }
    }

    @Override
    public String toString() {
        return "RouteLockedPaymentCommand[userId=" + userId + ", executionId=" + executionId + ", references=REDACTED]";
    }
}
