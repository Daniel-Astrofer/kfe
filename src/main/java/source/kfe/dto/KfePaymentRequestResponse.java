package source.kfe.dto;

import source.kfe.model.KfePaymentRequestStatus;
import source.kfe.model.KfeRail;
import source.kfe.model.KfeTransactionStatus;

import java.time.LocalDateTime;
import java.util.UUID;

public record KfePaymentRequestResponse(
        UUID id,
        String publicId,
        Long userId,
        UUID walletId,
        UUID addressId,
        String address,
        KfeRail rail,
        KfePaymentRequestStatus status,
        Long amountSats,
        String description,
        String memo,
        String payerHint,
        UUID paidTransactionId,
        UUID settlementTransactionId,
        KfeTransactionStatus settlementStatus,
        String blockchainTxid,
        Integer confirmations,
        Long grossAmountSats,
        Long receiverAmountSats,
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
