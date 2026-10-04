package com.kerosene.kfe.paymentexecution.application.port.in;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;

/** Worker entry point; must be invoked without an encompassing database transaction. */
public interface ProcessExecutionUseCase {
    void process(ExecutionClaim claim);
}
