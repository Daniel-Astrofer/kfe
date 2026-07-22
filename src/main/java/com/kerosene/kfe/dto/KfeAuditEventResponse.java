package source.kfe.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record KfeAuditEventResponse(
        Long sequenceNumber,
        UUID id,
        UUID transactionId,
        UUID walletId,
        String eventType,
        String fromStatus,
        String toStatus,
        String payloadHash,
        String previousHash,
        String eventHash,
        LocalDateTime createdAt) {
}
