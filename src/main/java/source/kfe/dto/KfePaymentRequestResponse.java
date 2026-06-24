package source.kfe.dto;

import source.kfe.model.KfePaymentRequestStatus;
import source.kfe.model.KfeRail;

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
        LocalDateTime expiresAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
