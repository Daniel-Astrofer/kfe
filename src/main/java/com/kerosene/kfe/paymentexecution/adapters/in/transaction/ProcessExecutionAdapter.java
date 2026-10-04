package com.kerosene.kfe.paymentexecution.adapters.in.transaction;

import com.kerosene.kfe.paymentexecution.application.port.in.ProcessExecutionUseCase;
import com.kerosene.kfe.paymentexecution.application.usecase.ProcessExecutionService;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/** The ports own short state transactions; this orchestration must never enclose external RPC in one. */
@Component
public class ProcessExecutionAdapter implements ProcessExecutionUseCase {
    private final ProcessExecutionService service;

    public ProcessExecutionAdapter(ProcessExecutionService service) {
        this.service = Objects.requireNonNull(service);
    }

    @Override
    public void process(ExecutionClaim claim) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("External execution must start outside an existing transaction.");
        }
        service.process(claim);
    }
}
