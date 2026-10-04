package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.Map;

/** Forensic audit boundary for lifecycle decisions. Payloads must already exclude secrets. */
public interface PaymentExecutionAuditPort {

    void record(
            PaymentExecutionId executionId,
            String eventType,
            ExecutionStatus previousStatus,
            ExecutionStatus currentStatus,
            Map<String, ?> payload);
}
