package com.kerosene.kfe.paymentexecution.application.result;

/** Payment Execution's immutable view of the pricing context's calculation. */
public record PaymentPricingQuote(
        long grossAmountSats,
        long receiverAmountSats,
        long networkFeeSats,
        long keroseneFeeSats,
        long totalDebitSats,
        int pricingPolicyVersion) {
}
