package com.kerosene.kfe.dto;

public record KfePpmAdjustRequest(
        String channelPoint,
        Long currentPpm,
        Boolean acceleratedDrain,
        Long baseFeeMsat) {
}
