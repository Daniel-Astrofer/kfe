package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.application.result.ExecutionPreparation;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import java.util.Optional;

public interface ExecutionPreparationPort {
    /** Returns after the short preparation transaction; empty means no external execution is needed. */
    Optional<ExecutionPreparation> prepare(ExecutionClaim claim);
}
