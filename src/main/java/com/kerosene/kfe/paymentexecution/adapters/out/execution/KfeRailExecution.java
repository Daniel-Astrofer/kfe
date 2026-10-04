package com.kerosene.kfe.paymentexecution.adapters.out.execution;

import java.util.UUID;

public interface KfeRailExecution {

    boolean supports(String operation);

    void execute(UUID outboxId, KfeExecutionTransactionHelper.PreparationResult prep);
}
