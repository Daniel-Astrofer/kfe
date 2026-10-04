package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentFundsReservationSnapshot;

public interface PaymentFundsReservationStatePort {
    PaymentFundsReservationSnapshot lockAndLoad(long userId, PaymentExecutionId executionId);
}
