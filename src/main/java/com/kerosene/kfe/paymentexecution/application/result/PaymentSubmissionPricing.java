package com.kerosene.kfe.paymentexecution.application.result;

/**
 * Prepared pricing, without persistence or financial mutations.
 * The gate input remains distinct from the quote fee, which the pricing policy may normalize.
 */
public record PaymentSubmissionPricing(
        long reservedNetworkFeeSats,
        PaymentPricingQuote quote,
        PaymentDisplaySnapshot display) {
}
