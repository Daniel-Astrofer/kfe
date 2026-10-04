package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.application.result.PreparedPaymentRequestLink;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

public record CompletePaymentRequestLinkCommand(long userId, PreparedPaymentRequestLink link, PaymentExecutionId executionId) {
    public CompletePaymentRequestLinkCommand {
        if (userId <= 0L || link == null || executionId == null || userId != link.payerUserId()) {
            throw new IllegalArgumentException("authenticated payer, accepted request and execution are required");
        }
    }
}
