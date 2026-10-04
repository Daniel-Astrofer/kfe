package com.kerosene.kfe.pricing.domain.model;

/** Result of applying a pricing policy to an amount and network fee. */
public record PricingQuote(
        SatoshiAmount grossAmount,
        SatoshiAmount receiverAmount,
        SatoshiAmount networkFee,
        SatoshiAmount totalDebit,
        SatoshiAmount keroseneFee,
        int pricingPolicyVersion) {
}
