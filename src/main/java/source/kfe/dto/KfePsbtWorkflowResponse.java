package source.kfe.dto;

import source.kfe.model.KfePsbtWorkflowStatus;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record KfePsbtWorkflowResponse(
        UUID id,
        Long userId,
        UUID walletId,
        KfePsbtWorkflowStatus status,
        String psbt,
        String psbtHash,
        String signedPsbtHash,
        String rawTxHash,
        String broadcastTxid,
        long amountSats,
        long feeSats,
        String destinationAddress,
        List<KfeColdWalletPsbtRequest.Input> inputs,
        String failureMessage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime signedAt,
        LocalDateTime broadcastAt) {
}
