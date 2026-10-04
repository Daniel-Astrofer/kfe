package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.Map;

public interface PaymentExecutionLifecycleUseCase {

    void recordCurrentState(
            PaymentExecutionId executionId,
            ExecutionStatus currentStatus,
            String eventType,
            Map<String, ?> auditPayload);

    PaymentExecutionStatusChanged transition(
            PaymentExecutionId executionId,
            ExecutionStatus targetStatus,
            String eventType,
            Map<String, ?> auditPayload);
}
