package com.kerosene.kfe.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KfeClassifyTaxEventRequest(
        @NotBlank @Size(max = 64) String classification) {
}
