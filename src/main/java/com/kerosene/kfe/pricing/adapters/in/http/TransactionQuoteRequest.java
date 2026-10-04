package com.kerosene.kfe.pricing.adapters.in.http;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record TransactionQuoteRequest(
        @NotNull PaymentRail rail,
        @NotNull PaymentDirection direction,
        @Min(1) long amountSats,
        @Min(0) long networkFeeSats) {
}
