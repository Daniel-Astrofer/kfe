package source.kfe.dto;

import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;

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
