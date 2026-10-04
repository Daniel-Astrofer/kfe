package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

/** Values bound to the authorized execution and its outbound message. */
public record ExecutionIntentBinding(
        UUID transactionId,
        long userId,
        String idempotencyKey,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountSats,
        long networkFeeSats,
        long totalDebitSats,
        String externalReference,
        String memo,
        String quorumProposalHash
) {
    @Override
    public String toString() {
        return "ExecutionIntentBinding[transactionId=" + transactionId
                + ", rail=" + rail + ", direction=" + direction + ", references=REDACTED]";
    }
}
