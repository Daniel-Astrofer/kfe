package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.UUID;

/** Existing initiation notification boundary; durable delivery is not guaranteed by this port. */
public interface PaymentInitiatedNotificationPort {
    void initiated(long userId, PaymentExecutionId executionId, UUID walletId, PaymentRail rail, long grossAmountSats);
}
