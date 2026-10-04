package com.kerosene.kfe.paymentexecution.domain.model;

import com.kerosene.kfe.paymentexecution.domain.event.PaymentExecutionStatusChanged;
import com.kerosene.kfe.paymentexecution.domain.exception.InvalidPaymentExecutionTransition;

/** Aggregate root that owns the payment execution lifecycle invariant. */
public final class PaymentExecution {

    private final PaymentExecutionId id;
    private ExecutionStatus status;

    private PaymentExecution(PaymentExecutionId id, ExecutionStatus status) {
        if (id == null || status == null) {
            throw new IllegalArgumentException("payment execution id and status are required");
        }
        this.id = id;
        this.status = status;
    }

    public static PaymentExecution reconstitute(PaymentExecutionId id, ExecutionStatus status) {
        return new PaymentExecution(id, status);
    }

    public PaymentExecutionStatusChanged transitionTo(ExecutionStatus target) {
        ExecutionStatus previous = status;
        if (!previous.canTransitionTo(target)) {
            throw new InvalidPaymentExecutionTransition(previous, target);
        }
        status = target;
        return new PaymentExecutionStatusChanged(id, previous, status);
    }

    /** Preliminary eligibility only; cancellation must also revalidate dispatch evidence under locks. */
    public boolean canBeCancelled(String blockchainTransactionId) {
        return switch (status) {
            case INTENT, VALIDATING, QUORUM_SYNC, LOCKED -> true;
            case EXECUTING -> blockchainTransactionId == null || blockchainTransactionId.isBlank();
            default -> false;
        };
    }

    /** Whether cancellation side effects may still close this execution. */
    public boolean isIncomplete() {
        return switch (status) {
            case SETTLED, FAILED, CANCELLED, CONFLICTED, CONFLICTED_REFUNDED, DROPPED, ABANDONED -> false;
            default -> true;
        };
    }

    public PaymentExecutionId id() {
        return id;
    }

    public ExecutionStatus status() {
        return status;
    }
}
