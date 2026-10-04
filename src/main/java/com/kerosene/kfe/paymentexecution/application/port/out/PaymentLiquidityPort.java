package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Outbound liquidity effects joined to the owning payment transaction. */
public interface PaymentLiquidityPort {

    void reserve(PaymentExecutionId executionId, long amountSats);

    void release(PaymentExecutionId executionId);
}
