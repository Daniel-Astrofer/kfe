package source.kfe.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import source.kfe.model.KfeRail;

import java.time.LocalDateTime;
import java.util.UUID;

public record KfeCreatePaymentRequest(
        @NotNull UUID walletId,
        KfeRail rail,
        @Min(1) Long amountSats,
        @Size(max = 180) String description,
        @Size(max = 255) String memo,
        @Size(max = 120) String payerHint,
        LocalDateTime expiresAt,
        Boolean issueFreshAddress) {
}
