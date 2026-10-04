package com.kerosene.kfe.pricing.domain.service;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import com.kerosene.kfe.pricing.domain.model.PricingPolicySnapshot;
import com.kerosene.kfe.pricing.domain.model.PricingQuote;
import com.kerosene.kfe.pricing.domain.model.SatoshiAmount;

/** Pure domain service for fee and settlement amount calculation. */
public final class PricingCalculator {

    private static final long BPS_DENOMINATOR = 10_000L;

    public PricingQuote quote(
            PaymentRail rail,
            PaymentDirection direction,
            SatoshiAmount amount,
            SatoshiAmount networkFee,
            PricingPolicySnapshot policy) {
        if (rail == null || direction == null || amount == null || networkFee == null || policy == null) {
            throw new IllegalArgumentException("pricing inputs are required");
        }
        if (amount.value() == 0L) {
            throw new IllegalArgumentException("amount must be positive");
        }

        if (rail == PaymentRail.INTERNAL || direction == PaymentDirection.INTERNAL) {
            return new PricingQuote(
                    amount,
                    amount,
                    new SatoshiAmount(0L),
                    amount,
                    new SatoshiAmount(0L),
                    policy.version());
        }

        SatoshiAmount keroseneFee = calculateFee(amount, policy.railPricing());
        if (direction == PaymentDirection.INBOUND) {
            long receiverSats = Math.subtractExact(amount.value(), keroseneFee.value());
            if (receiverSats <= 0L) {
                throw new IllegalArgumentException("inbound amount is too small after Kerosene fee");
            }
            return new PricingQuote(
                    amount,
                    new SatoshiAmount(receiverSats),
                    networkFee,
                    new SatoshiAmount(0L),
                    keroseneFee,
                    policy.version());
        }

        return new PricingQuote(
                amount,
                amount,
                networkFee,
                amount.plus(networkFee).plus(keroseneFee),
                keroseneFee,
                policy.version());
    }

    private SatoshiAmount calculateFee(
            SatoshiAmount amount,
            PricingPolicySnapshot.RailPricing pricing) {
        if (pricing == null || pricing.basisPoints() == 0) {
            return new SatoshiAmount(0L);
        }
        long numerator = Math.addExact(
                Math.multiplyExact(amount.value(), (long) pricing.basisPoints()),
                BPS_DENOMINATOR - 1L);
        long fee = Math.floorDiv(numerator, BPS_DENOMINATOR);
        if (pricing.minSats() != null) {
            fee = Math.max(fee, pricing.minSats());
        }
        if (pricing.maxSats() != null) {
            fee = Math.min(fee, pricing.maxSats());
        }
        return new SatoshiAmount(fee);
    }
}
