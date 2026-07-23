package com.kerosene.kfe.dto;

import jakarta.validation.constraints.NotBlank;

public record KfeSignedPsbtRequest(
        @NotBlank String signedPsbt) {
}
