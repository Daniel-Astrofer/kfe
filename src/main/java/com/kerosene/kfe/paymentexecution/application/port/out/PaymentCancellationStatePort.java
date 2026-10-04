package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentCancellationSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Reads and updates a payment in the same transaction that already holds its cancellation fence. */
public interface PaymentCancellationStatePort {
    PaymentCancellationSnapshot load(PaymentExecutionId executionId);

    /** Persist FAILED / USER_CANCELLED only if the locked payment still matches the supplied state. */
    void markCancelled(PaymentCancellationSnapshot previous, String message);
}
