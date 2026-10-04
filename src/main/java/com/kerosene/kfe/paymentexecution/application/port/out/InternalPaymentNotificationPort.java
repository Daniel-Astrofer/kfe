package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import java.util.UUID;

/** Notification intent; the compatibility adapter still uses the existing best-effort transport. */
public interface InternalPaymentNotificationPort {
    void sent(long userId, PaymentExecutionId executionId, UUID walletId, long amountSats);
    void received(long userId, PaymentExecutionId executionId, UUID walletId, long amountSats);
}
