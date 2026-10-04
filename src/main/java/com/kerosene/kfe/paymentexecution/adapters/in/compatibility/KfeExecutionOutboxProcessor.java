package com.kerosene.kfe.paymentexecution.adapters.in.compatibility;

import com.kerosene.kfe.paymentexecution.application.port.in.ProcessExecutionUseCase;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import org.springframework.stereotype.Service;

import java.util.Objects;

/** Compatibility facade only. Worker decisions belong to the payment execution application core. */
@Service
public class KfeExecutionOutboxProcessor {
    private final ProcessExecutionUseCase execution;

    public KfeExecutionOutboxProcessor(ProcessExecutionUseCase execution) {
        this.execution = Objects.requireNonNull(execution);
    }

    public void process(KfeExecutionOutboxService.ExecutionClaim claim) {
        Objects.requireNonNull(claim, "execution claim is required");
        execution.process(new ExecutionClaim(claim.outboxId(), claim.claimToken()));
    }
}
