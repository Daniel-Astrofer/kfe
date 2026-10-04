package com.kerosene.kfe.pricing.domain.model;

/** Non-negative amount represented in satoshis. */
public record SatoshiAmount(long value) {

    public SatoshiAmount {
        if (value < 0L) {
            throw new IllegalArgumentException("satoshi amount must be non-negative");
        }
    }

    public static SatoshiAmount positive(long value) {
        if (value <= 0L) {
            throw new IllegalArgumentException("satoshi amount must be positive");
        }
        return new SatoshiAmount(value);
    }

    public SatoshiAmount plus(SatoshiAmount other) {
        return new SatoshiAmount(Math.addExact(value, other.value));
    }
}
