package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import java.util.List;

/** Reserves cancellation against dispatch, joining the caller's financial transaction. */
public interface PaymentCancellationFencePort {
    void fence(List<PaymentExecutionId> executionIds);
}
