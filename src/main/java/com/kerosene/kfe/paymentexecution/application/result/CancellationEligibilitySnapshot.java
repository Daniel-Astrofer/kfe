package com.kerosene.kfe.paymentexecution.application.result;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Authorized read projection; excludes raw idempotency keys, invoice payloads and credentials. */
public record CancellationEligibilitySnapshot(
        PaymentExecutionId executionId, long ownerUserId, ExecutionStatus status,
        String blockchainTransactionId, PaymentRequestCancellationReference paymentRequest) {}
