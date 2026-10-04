package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.UUID;

/** Stable application message persisted before an external rail is invoked. */
public record ScheduleExternalExecutionCommand(
        PaymentExecutionId executionId,
        IdempotencyKey idempotencyKey,
        long userId,
        PaymentRail rail,
        PaymentDirection direction,
        UUID sourceWalletId,
        UUID destinationWalletId,
        long amountSats,
        long networkFeeSats,
        long totalDebitSats,
        String externalReference,
        String memo,
        String quorumProposalHash,
        Long feeRateSatsPerVbyte,
        Integer feeTargetBlocks) {

    public ScheduleExternalExecutionCommand {
        if (executionId == null || idempotencyKey == null || rail == null || direction == null) {
            throw new IllegalArgumentException("execution identity, rail and direction are required");
        }
        if (amountSats < 0L || networkFeeSats < 0L || totalDebitSats < 0L) {
            throw new IllegalArgumentException("execution amounts must be non-negative");
        }
    }

    @Override
    public String toString() {
        return "ScheduleExternalExecutionCommand[executionId=" + executionId + ", userId=" + userId
                + ", rail=" + rail + ", direction=" + direction + ", references=REDACTED]";
    }
}
