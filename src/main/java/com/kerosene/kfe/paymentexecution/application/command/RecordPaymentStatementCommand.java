package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.UUID;

/** Participant statement context, independent from transport credentials and persistence. */
public record RecordPaymentStatementCommand(
        long userId,
        PaymentExecutionId executionId,
        UUID walletId,
        String memo,
        boolean cancelled) {

    public RecordPaymentStatementCommand {
        if (userId <= 0 || executionId == null) {
            throw new IllegalArgumentException("user id and payment execution id are required");
        }
    }
}
