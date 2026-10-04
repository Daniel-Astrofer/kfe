package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentSubmissionSnapshot;
import com.kerosene.kfe.paymentexecution.application.result.PaymentSubmissionPricing;

/** All writes share the authorized submit transaction and its execution lock. */
public interface PaymentSubmissionStatePort {
    PaymentSubmissionSnapshot lockAndLoad(long userId, PaymentExecutionId id);
    void applyPricing(PaymentSubmissionSnapshot expectedIntent, PaymentSubmissionPricing pricing);
    void recordProposal(long userId, PaymentExecutionId id, String proposalHash);
    void recordQuorum(long userId, PaymentExecutionId id, int ackCount);
}
