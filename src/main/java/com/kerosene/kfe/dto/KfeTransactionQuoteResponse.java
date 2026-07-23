package com.kerosene.kfe.dto;

import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;

import java.time.Instant;
import java.util.List;

public record KfeTransactionQuoteResponse(
        KfeRail rail,
        KfeDirection direction,
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
        List<KfeFeeTierResponse> feeTiers) {
}
