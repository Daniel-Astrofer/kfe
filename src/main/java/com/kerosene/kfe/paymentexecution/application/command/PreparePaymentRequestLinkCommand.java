package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import java.util.UUID;

public record PreparePaymentRequestLinkCommand(long userId, PaymentRail rail, PaymentDirection direction,
        UUID destinationWalletId, long amountSats, String publicId) {
    public PreparePaymentRequestLinkCommand {
        if (userId <= 0L || rail == null || direction == null || amountSats <= 0L) {
            throw new IllegalArgumentException("authenticated payment inputs are required");
        }
    }

    @Override
    public String toString() {
        return "PreparePaymentRequestLinkCommand[userId=" + userId + ", rail=" + rail
                + ", direction=" + direction + ", reference=REDACTED]";
    }
}
