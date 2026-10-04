package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.application.command.SubmitPaymentCommand;
import com.kerosene.kfe.paymentexecution.domain.model.RequestFingerprint;
import java.util.Objects;
import java.util.Optional;

/** Immediate orchestration output, not a durable/reusable authorization grant. */
public record PaymentPreflightResult(SubmitPaymentCommand command, RequestFingerprint fingerprint,
        Optional<PaymentExecutionResult> existingPayment) {
    public PaymentPreflightResult {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(existingPayment, "existingPayment");
    }

    @Override
    public String toString() { return "PaymentPreflightResult[replay=" + existingPayment.isPresent() + ", REDACTED]"; }
}
