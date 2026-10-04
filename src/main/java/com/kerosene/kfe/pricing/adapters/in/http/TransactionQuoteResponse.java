package com.kerosene.kfe.pricing.adapters.in.http;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;

import java.time.Instant;
import java.util.List;

public record TransactionQuoteResponse(
        PaymentRail rail,
        PaymentDirection direction,
        long grossAmountSats,
        long receiverAmountSats,
        long networkFeeSats,
        long totalDebitSats,
        long keroseneFeeSats,
        long totalFeeSats,
        long feeRateSatPerVbyte,
        int estimatedVbytes,
        int estimatedConfirmationBlocks,
        long estimatedSettlementSeconds,
        String feeSource,
        Instant quoteExpiresAt,
        List<FeeTierResponse> feeTiers) {
}
