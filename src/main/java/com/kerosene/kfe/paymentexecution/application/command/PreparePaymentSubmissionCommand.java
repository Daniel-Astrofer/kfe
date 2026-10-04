package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import java.util.Objects;

/** Inputs retained in memory by the authorized submit; not a portable authorization ticket. */
public record PreparePaymentSubmissionCommand(long userId, PaymentExecutionId executionId,
        RequestFingerprint requestFingerprint, long requestedNetworkFeeSats, Long feeRateSatPerVbyte,
        Integer feeTargetBlocks, String externalReference, String paymentRequestPublicId) {
    public PreparePaymentSubmissionCommand {
        if (userId <= 0L) { throw new IllegalArgumentException("authenticated user id must be positive"); }
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(requestFingerprint, "request fingerprint is required");
    }
    @Override public String toString() {
        return "PreparePaymentSubmissionCommand[userId=" + userId + ", executionId=" + executionId + ", references=REDACTED]";
    }
}
