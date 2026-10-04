package com.kerosene.kfe.paymentexecution.application.port.out;

import java.util.UUID;

/** Serializes cancellation with payment-request observations and validates ownership. */
public interface PaymentRequestCancellationLockPort {
    void lock(long userId, UUID paymentRequestId);
}
