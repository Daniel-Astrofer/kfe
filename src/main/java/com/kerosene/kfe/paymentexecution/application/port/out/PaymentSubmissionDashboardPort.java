package com.kerosene.kfe.paymentexecution.application.port.out;

public interface PaymentSubmissionDashboardPort {
    /** Register publication in the current transaction; rollback must suppress delivery. Not a durable outbox. */
    void publishAfterCommit(long userId);
}
