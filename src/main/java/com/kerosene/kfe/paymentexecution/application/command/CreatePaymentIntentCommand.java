package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.UUID;

/** Creation-only inputs: authorization factors and quote/provider execution data are intentionally absent. */
public record CreatePaymentIntentCommand(
        long userId,
        IdempotencyKey idempotencyKey,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountSats,
        String externalReference,
        String memo,
        String paymentRequestPublicId) {

    @Override
    public String toString() {
        return "CreatePaymentIntentCommand[userId=" + userId + ", rail=" + rail + ", direction=" + direction
                + ", amountSats=" + amountSats + ", references=REDACTED]";
    }
}
