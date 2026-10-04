package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Idempotent settlement of a payment's recorded fee in the owning transaction. */
public interface PaymentFeeSettlementPort {

    void settleFee(PaymentExecutionId executionId);
}
