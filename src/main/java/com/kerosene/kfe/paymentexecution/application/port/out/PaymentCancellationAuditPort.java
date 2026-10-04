package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentCancellationSnapshot;

/** Appends a cancellation event, including the inbound destination wallet, in the financial commit. */
public interface PaymentCancellationAuditPort {
    void recordCancelled(PaymentCancellationSnapshot previous);
}
