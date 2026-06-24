package source.kfe.dto;

import source.kfe.model.KfeDirection;
import source.kfe.model.KfeRail;

public record KfeTransactionQuoteResponse(
        KfeRail rail,
        KfeDirection direction,
        long grossAmountSats,
        long receiverAmountSats,
        long networkFeeSats,
        long totalDebitSats,
        long keroseneFeeSats) {
}
