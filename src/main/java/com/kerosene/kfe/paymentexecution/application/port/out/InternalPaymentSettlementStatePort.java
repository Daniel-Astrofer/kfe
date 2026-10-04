package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.InternalPaymentSettlementSnapshot;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Requires the authorized submission transaction and any linked payment-request lock. */
public interface InternalPaymentSettlementStatePort {
    InternalPaymentSettlementSnapshot lockAndLoad(long userId, PaymentExecutionId executionId);
}
