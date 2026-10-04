package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentExecutionRepository;
import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionStatus;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecution;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.Map;

/** Framework-free lifecycle coordination. Transactionality is supplied by an inbound adapter. */
public final class PaymentExecutionLifecycleService {

    private final PaymentExecutionRepository repository;
    private final PaymentExecutionAuditPort auditPort;

    public PaymentExecutionLifecycleService(
            PaymentExecutionRepository repository,
            PaymentExecutionAuditPort auditPort) {
        this.repository = repository;
        this.auditPort = auditPort;
    }

    public void recordCurrentState(
            PaymentExecutionId executionId,
            ExecutionStatus currentStatus,
            String eventType,
            Map<String, ?> auditPayload) {
        PaymentExecution execution = load(executionId);
        if (execution.status() != currentStatus) {
            throw new IllegalStateException(
                    "Payment execution status changed before audit: expected "
                            + currentStatus + " but was " + execution.status());
        }
        auditPort.record(executionId, eventType, null, currentStatus, auditPayload);
    }

    public PaymentExecutionStatusChanged transition(
            PaymentExecutionId executionId,
            ExecutionStatus targetStatus,
            String eventType,
            Map<String, ?> auditPayload) {
        PaymentExecution execution = load(executionId);
        PaymentExecutionStatusChanged event = execution.transitionTo(targetStatus);
        repository.save(execution);
        auditPort.record(
                executionId,
                eventType,
                event.previousStatus(),
                event.currentStatus(),
                auditPayload);
        return event;
    }

    private PaymentExecution load(PaymentExecutionId executionId) {
        return repository.findById(executionId)
                .orElseThrow(() -> new IllegalArgumentException("KFE transaction not found."));
    }
}
