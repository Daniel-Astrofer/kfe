package source.kfe.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record KfeStatementItem(
        UUID id,
        UUID transactionId,
        UUID walletId,
        String displayPayloadJson,
        LocalDateTime createdAt,
        LocalDateTime expiresAt) {
}
