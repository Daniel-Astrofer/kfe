package source.kfe.dto;

import java.time.LocalDateTime;

public record KfeAuditRootResponse(
        String merkleRoot,
        long eventCount,
        Long fromSequence,
        Long toSequence,
        LocalDateTime generatedAt) {
}
