package com.kerosene.kfe.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;

public record KfeTransactionQuoteRequest(
        @NotNull KfeRail rail,
        @NotNull KfeDirection direction,
        @Min(1) long amountSats,
        @Min(0) long networkFeeSats) {
}
