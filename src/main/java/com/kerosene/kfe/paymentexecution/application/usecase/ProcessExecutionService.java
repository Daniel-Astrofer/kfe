package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionOutcomePort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionPreparationPort;
import com.kerosene.kfe.paymentexecution.application.port.out.ExternalExecutionPort;
import com.kerosene.kfe.paymentexecution.domain.exception.ExecutionClaimLost;
import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;

import java.util.List;
import java.util.Objects;

/** Coordinates committed preparation and external execution without owning a transaction. */
public final class ProcessExecutionService {
    private final ExecutionClaimPort claims;
    private final ExecutionPreparationPort preparation;
    private final List<ExternalExecutionPort> executors;
    private final ExecutionOutcomePort outcomes;

    public ProcessExecutionService(
            ExecutionClaimPort claims,
            ExecutionPreparationPort preparation,
            List<ExternalExecutionPort> executors,
            ExecutionOutcomePort outcomes) {
        this.claims = Objects.requireNonNull(claims, "execution claims are required");
        this.preparation = Objects.requireNonNull(preparation, "execution preparation is required");
        this.executors = List.copyOf(executors);
        this.outcomes = Objects.requireNonNull(outcomes, "execution outcomes are required");
    }

    public void process(ExecutionClaim claim) {
        Objects.requireNonNull(claim, "execution claim is required");
        if (!claims.heartbeat(claim)) {
            throw new ExecutionClaimLost(claim.outboxId());
        }

        var prepared = Objects.requireNonNull(preparation.prepare(claim), "preparation result is required");
        if (prepared.isEmpty()) {
            return;
        }
        if (!claims.heartbeat(claim)) {
            return;
        }

        var context = prepared.get();
        var executor = executors.stream()
                .filter(candidate -> candidate.supports(context.operation()))
                .findFirst()
                .orElse(null);
        if (executor == null) {
            outcomes.markFinalFailure(claim, context.transactionId(),
                    "UNSUPPORTED_OPERATION", "Unsupported KFE outbox operation.");
            return;
        }

        // Infrastructure errors propagate: a failed outcome write must never trigger a fallback.
        var result = Objects.requireNonNull(executor.execute(claim, context), "external execution result is required");
        switch (result.outcome()) {
            case COMPLETED, CLAIM_LOST -> { }
            case UNKNOWN -> outcomes.markUnknown(claim, context.transactionId(),
                    result.providerReference(), result.rawPayload(), result.message());
            case RETRYABLE_FAILURE -> outcomes.markRetryableFailure(claim, context.transactionId(),
                    "PROVIDER_RETRYABLE_FAILURE", result.message());
            case FINAL_FAILURE -> outcomes.markFinalFailure(claim, context.transactionId(),
                    "PROVIDER_FINAL_FAILURE", result.message());
        }
    }
}
