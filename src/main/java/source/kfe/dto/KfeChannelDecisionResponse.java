package source.kfe.dto;

import source.kfe.model.KfeChannelOperationType;

import java.time.LocalDateTime;
import java.util.UUID;

public record KfeChannelDecisionResponse(
        UUID id,
        KfeChannelOperationType operation,
        boolean passed,
        boolean executed,
        String peerPubkey,
        String channelPoint,
        Long amountSats,
        String decisionReason,
        String providerReference,
        String flagsJson,
        LocalDateTime createdAt) {
}
