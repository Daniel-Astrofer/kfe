package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import java.util.UUID;

/** Each write owns a short transaction and fences by the current claim before changing financial state. */
public interface ExecutionOutcomePort {
    void markUnknown(ExecutionClaim claim, UUID transactionId, String reference, String payload, String message);
    void markRetryableFailure(ExecutionClaim claim, UUID transactionId, String code, String message);
    void markFinalFailure(ExecutionClaim claim, UUID transactionId, String code, String message);
}
