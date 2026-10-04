package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.port.in.PaymentExecutionLifecycleUseCase;
import com.kerosene.kfe.paymentexecution.application.usecase.PaymentExecutionLifecycleService;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** Ensures aggregate persistence and forensic audit commit or roll back together. */
@Component
public class TransactionalPaymentExecutionLifecycleAdapter implements PaymentExecutionLifecycleUseCase {

    private final PaymentExecutionLifecycleService lifecycleService;

    public TransactionalPaymentExecutionLifecycleAdapter(
            PaymentExecutionLifecycleService lifecycleService) {
        this.lifecycleService = lifecycleService;
    }

    @Override
    @Transactional
    public void recordCurrentState(
            PaymentExecutionId executionId,
            ExecutionStatus currentStatus,
            String eventType,
            Map<String, ?> auditPayload) {
        lifecycleService.recordCurrentState(executionId, currentStatus, eventType, auditPayload);
    }

    @Override
    @Transactional
    public PaymentExecutionStatusChanged transition(
            PaymentExecutionId executionId,
            ExecutionStatus targetStatus,
            String eventType,
            Map<String, ?> auditPayload) {
        return lifecycleService.transition(executionId, targetStatus, eventType, auditPayload);
    }
}
