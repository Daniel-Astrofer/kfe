package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.SettlementGateEvaluation;
import java.util.UUID;

/** Audit participates in the caller transaction; a rejection row is lost on rollback. */
public interface PaymentGateAuditPort {
    void record(PaymentExecutionId executionId, UUID walletId, SettlementGateEvaluation evaluation);
}
