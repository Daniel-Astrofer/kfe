package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/** Raw scalar values; validation belongs to the validation service. */
public record ValidatePaymentRequestCommand(String idempotencyKey, PaymentRail rail,
        PaymentDirection direction, long amountSats, long networkFeeSats, String externalReference) {
    @Override
    public String toString() { return "ValidatePaymentRequestCommand[REDACTED]"; }
}
