package com.kerosene.kfe.dto;

import jakarta.validation.constraints.NotBlank;

public record KfeRebalanceChannelRequest(
        @NotBlank String channelPoint,
        Long estimatedCostSats,
        Long expectedGainSats) {
}
