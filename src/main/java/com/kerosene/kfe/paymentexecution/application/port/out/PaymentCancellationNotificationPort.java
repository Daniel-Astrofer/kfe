package com.kerosene.kfe.paymentexecution.application.port.out;

/** Registers publication in the current cancellation transaction, never before its commit. */
public interface PaymentCancellationNotificationPort {
    void publishAfterCommit(long userId);
}
