package com.kerosene.kfe.pricing.application.result;

import com.kerosene.kfe.pricing.domain.model.PaymentDirection;
import com.kerosene.kfe.pricing.domain.model.PaymentRail;

import java.time.Instant;
import java.util.List;

/** Application result independent from HTTP serialization. */
public record TransactionQuoteResult(
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
        List<FeeTierResult> feeTiers,
        int pricingPolicyVersion) {

    public TransactionQuoteResult {
        feeTiers = feeTiers == null ? List.of() : List.copyOf(feeTiers);
    }
}
