package source.kfe.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record KfeOpenChannelRequest(
        @NotBlank String peerPubkey,
        @NotNull @Min(1) Long localAmountSats,
        Long estimatedFeeRateSatVb,
        Boolean anchorsEnabled,
        Boolean privateChannel,
        Boolean spendUnconfirmed) {
}
