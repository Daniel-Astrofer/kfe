package com.kerosene.kfe.paymentexecution.application.command;

import java.util.UUID;

/** Authenticated intent; request metadata never supplies the acting user. */
public record CancelPaymentRequestCommand(long userId, UUID paymentRequestId) {
    public CancelPaymentRequestCommand {
        if (userId <= 0 || paymentRequestId == null) {
            throw new IllegalArgumentException("user id and payment request id are required");
        }
    }
}
