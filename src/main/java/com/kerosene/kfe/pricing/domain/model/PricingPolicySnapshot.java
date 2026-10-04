package com.kerosene.kfe.pricing.domain.model;

/** Immutable policy input used for one pricing decision. */
public record PricingPolicySnapshot(int version, RailPricing railPricing) {

    public PricingPolicySnapshot {
        if (version < 1) {
            throw new IllegalArgumentException("pricing policy version must be positive");
        }
    }

    public record RailPricing(int basisPoints, Long minSats, Long maxSats) {
        public RailPricing {
            if (basisPoints < 0 || basisPoints > 10_000) {
                throw new IllegalArgumentException("basisPoints must be between 0 and 10000");
            }
            if (minSats != null && minSats < 0L) {
                throw new IllegalArgumentException("minSats must be non-negative");
            }
            if (maxSats != null && maxSats < 0L) {
                throw new IllegalArgumentException("maxSats must be non-negative");
            }
            if (minSats != null && maxSats != null && minSats > maxSats) {
                throw new IllegalArgumentException("minSats must not exceed maxSats");
            }
        }
    }
}
