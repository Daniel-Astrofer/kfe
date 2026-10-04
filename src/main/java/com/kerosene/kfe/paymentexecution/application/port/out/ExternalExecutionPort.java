package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.ExecutionPreparation;
import com.kerosene.kfe.paymentexecution.application.result.ExternalExecutionResult;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;

public interface ExternalExecutionPort {
    boolean supports(String operation);
    /** Once dispatch starts, any uncertain send or local ACK failure must produce UNKNOWN, never FINAL_FAILURE. */
    ExternalExecutionResult execute(ExecutionClaim claim, ExecutionPreparation preparation);
}
