package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Immutable financial state reread after the cancellation fence has acquired its locks. */
public record PaymentCancellationSnapshot(
        PaymentExecutionId executionId,
        long userId,
        ExecutionStatus status,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long totalDebitSats,
        String blockchainTransactionId) {

    public PaymentCancellationSnapshot {
        Objects.requireNonNull(executionId, "execution id is required");
        Objects.requireNonNull(status, "execution status is required");
        Objects.requireNonNull(rail, "payment rail is required");
        Objects.requireNonNull(direction, "payment direction is required");
        if (userId <= 0L) {
            throw new IllegalArgumentException("user id must be positive");
        }
        if (totalDebitSats < 0L) {
            throw new IllegalArgumentException("total debit cannot be negative");
        }
    }

    public boolean incomplete() {
        return PaymentExecution.reconstitute(executionId, status).isIncomplete();
    }

    public boolean cancellable() {
        return PaymentExecution.reconstitute(executionId, status).canBeCancelled(blockchainTransactionId);
    }

    public boolean requiresReserveRelease() {
        return (status == ExecutionStatus.LOCKED || status == ExecutionStatus.EXECUTING)
                && sourceWalletId != null && totalDebitSats > 0L;
    }

    public boolean requiresLiquidityRelease() {
        return rail == PaymentRail.LIGHTNING && direction == PaymentDirection.OUTBOUND;
    }

    public UUID statementWalletId() {
        return sourceWalletId != null ? sourceWalletId : destinationWalletId;
    }
}
