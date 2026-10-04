package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

/** Existing proposal wire representation. Do not normalize the original references or log the preimage. */
public record PaymentProposal(PaymentExecutionId executionId, long userId, PaymentRail rail, PaymentDirection direction,
        UUID sourceWalletId, UUID destinationWalletId, long grossAmountSats, long receiverAmountSats,
        long networkFeeSats, long keroseneFeeSats, long totalDebitSats,
        String externalReference, String paymentRequestPublicId) {
    public String canonicalContent() {
        return String.join("|", "KFE_TX_PROPOSAL", executionId.value().toString(), String.valueOf(userId),
                rail.name(), direction.name(), String.valueOf(sourceWalletId), String.valueOf(destinationWalletId),
                String.valueOf(grossAmountSats), String.valueOf(receiverAmountSats), String.valueOf(networkFeeSats),
                String.valueOf(keroseneFeeSats), String.valueOf(totalDebitSats),
                externalReference != null ? externalReference : "", paymentRequestPublicId != null ? paymentRequestPublicId : "");
    }
    @Override public String toString() {
        return "PaymentProposal[executionId=" + executionId + ", userId=" + userId
                + ", rail=" + rail + ", direction=" + direction + ", references=REDACTED]";
    }
}
