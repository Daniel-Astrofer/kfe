package com.kerosene.kfe.dto;

import jakarta.validation.constraints.NotBlank;

public record KfeCloseChannelRequest(
        @NotBlank String channelPoint,
        Boolean force,
        Boolean peerOfflineBeyondThreshold,
        Long estimatedFeeRateSatVb) {
}
