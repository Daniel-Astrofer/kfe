package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRoutingSnapshot;

public interface PaymentRoutingStatePort {
    PaymentRoutingSnapshot lockAndLoad(long userId, PaymentExecutionId executionId);
    void flush(long userId, PaymentExecutionId executionId);
}
