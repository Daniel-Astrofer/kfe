package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentRequestCancellationSnapshot;

/** Appends the request cancellation audit in the same financial transaction as its state change. */
public interface PaymentRequestCancellationAuditPort {

    void recordCancelled(PaymentRequestCancellationSnapshot previous);
}
