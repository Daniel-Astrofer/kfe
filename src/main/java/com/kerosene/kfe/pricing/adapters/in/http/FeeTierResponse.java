package com.kerosene.kfe.pricing.adapters.in.http;

public record FeeTierResponse(
        String name,
        long feeRateSatPerVbyte,
        long networkFeeSats,
        int targetBlocks,
        long estimatedSeconds,
        String source) {
}
