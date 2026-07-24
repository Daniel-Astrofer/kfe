package com.kerosene.kfe.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import com.kerosene.kfe.model.KfeRail;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record KfeCreatePaymentRequest(
        @NotNull UUID walletId,
        /** Legacy: single rail (backward compat). Prefer rails (list) for multi-rail. */
        KfeRail rail,
        /** Rails to activate for this payment request. Defaults to [ONCHAIN] when both are null/empty. */
        List<KfeRail> rails,
        @Min(1) Long amountSats,
        @Size(max = 180) String description,
        @Size(max = 255) String memo,
        @Size(max = 120) String payerHint,
        LocalDateTime expiresAt,
        Boolean issueFreshAddress) {
}
