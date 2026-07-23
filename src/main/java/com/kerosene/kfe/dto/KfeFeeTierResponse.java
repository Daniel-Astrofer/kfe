package com.kerosene.kfe.dto;

public record KfeFeeTierResponse(
        String priority,
        long feeRateSatPerVbyte,
        long networkFeeSats,
        int targetBlocks,
        long estimatedSeconds,
        String source) {
}
