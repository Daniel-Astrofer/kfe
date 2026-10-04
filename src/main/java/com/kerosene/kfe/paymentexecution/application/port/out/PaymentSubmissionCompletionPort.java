package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentSubmissionCompletionSnapshot;

/** Owner-scoped persistence and consumer projection within the submission transaction. */
public interface PaymentSubmissionCompletionPort {
    PaymentSubmissionCompletionSnapshot lockAndLoad(long userId, PaymentExecutionId executionId);
    PaymentExecutionResult saveAndProject(PaymentSubmissionCompletionSnapshot expected);
}
