package com.kerosene.kfe.paymentexecution.application.port.out;

import java.util.UUID;

/** Dispatches a durable command after the business transaction has committed. */
public interface ExecutionCommandDispatcher {

    DispatchResult dispatchImmediately(UUID outboxId, String workerId);

    enum DispatchResult {
        PROCESSED,
        ALREADY_CLAIMED
    }
}
