package source.kfe.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record KfeTaxEventResponse(
        String id,
        String eventType,
        String asset,
        long quantitySats,
        String classification,
        String sourceRef,
        LocalDateTime createdAt,
        UUID accountId,
        UUID cardId,
        UUID walletId,
        LocalDateTime purgeAfter) {
}
