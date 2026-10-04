package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.application.result.PaymentCancellationHints;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

public interface PaymentCancellationHintsUseCase {
    PaymentCancellationHints hintsFor(long userId, PaymentExecutionId executionId);
}
