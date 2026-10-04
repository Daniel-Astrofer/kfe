package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

/** Pricing inputs from a validated submission, before applying the authoritative network fee floor. */
public record PreparePaymentPricingCommand(
        PaymentRail rail,
        PaymentDirection direction,
        long amountSats,
        long requestedNetworkFeeSats,
        Long feeRateSatPerVbyte,
        Integer feeTargetBlocks) {
}
