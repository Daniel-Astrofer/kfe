package source.kfe.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;

public record KfeTransactionQuoteRequest(
        @NotNull KfeRail rail,
        @NotNull KfeDirection direction,
        @Min(1) long amountSats,
        @Min(0) long networkFeeSats) {
}
