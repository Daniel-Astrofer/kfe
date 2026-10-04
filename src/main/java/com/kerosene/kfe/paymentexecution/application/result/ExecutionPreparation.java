package com.kerosene.kfe.paymentexecution.application.result;

import java.util.Objects;
import java.util.UUID;

/** Immutable projection of the short, committed preparation step; not an authorization ticket. */
public record ExecutionPreparation(
        String operation, UUID transactionId, Long userId, String sourceWalletLabel,
        UUID sourceWalletId, String externalReference, long amountSats, long networkFeeSats,
        String memo, String idempotencyKey, String quorumProposalHash,
        Long feeRateSatsPerVbyte, Integer feeTargetBlocks) {
    public ExecutionPreparation {
        Objects.requireNonNull(transactionId, "transactionId is required");
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("Execution operation is required.");
        }
    }

    @Override
    public String toString() {
        return "ExecutionPreparation[transactionId=" + transactionId + ", details=REDACTED]";
    }
}
