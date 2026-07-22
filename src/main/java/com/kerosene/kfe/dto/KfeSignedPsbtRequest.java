package source.kfe.dto;

import jakarta.validation.constraints.NotBlank;

public record KfeSignedPsbtRequest(
        @NotBlank String signedPsbt) {
}
