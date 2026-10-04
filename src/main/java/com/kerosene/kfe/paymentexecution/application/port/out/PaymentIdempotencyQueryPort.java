package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.PaymentExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import java.util.Optional;

public interface PaymentIdempotencyQueryPort {
    /** Current consumer projection, scoped to execution owner AND original idempotency key; no participant visibility or write lock. */
    Optional<PaymentExecutionResult> findOwnedByIdAndKey(long userId, PaymentExecutionId executionId, IdempotencyKey key);
}
