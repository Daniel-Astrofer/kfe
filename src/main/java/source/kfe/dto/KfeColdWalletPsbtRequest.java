package source.kfe.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record KfeColdWalletPsbtRequest(
        @NotBlank @Size(max = 128) String destinationAddress,
        @Min(546) long amountSats,
        @Min(1) Integer confirmationTarget,
        @Min(1) Long feeRateSatsPerVbyte,
        @Valid List<Input> inputs,
        String totpCode) {

    public KfeColdWalletPsbtRequest(
            String destinationAddress,
            long amountSats,
            Integer confirmationTarget,
            Long feeRateSatsPerVbyte,
            List<Input> inputs) {
        this(destinationAddress, amountSats, confirmationTarget, feeRateSatsPerVbyte, inputs, null);
    }

    public record Input(
            @NotBlank @Size(max = 128) String txid,
            @Min(0) int vout) {
    }
}
