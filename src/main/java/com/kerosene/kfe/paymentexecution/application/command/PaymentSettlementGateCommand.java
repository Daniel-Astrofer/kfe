package com.kerosene.kfe.paymentexecution.application.command;

import com.kerosene.kfe.paymentexecution.domain.model.IdempotencyKey;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentDirection;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentRail;

import java.util.UUID;

/** Typed gate inputs. Monetary and risk validation remains the settlement gate's responsibility. */
public record PaymentSettlementGateCommand(
        long userId,
        PaymentExecutionId executionId,
        UUID sourceWalletId,
        IdempotencyKey idempotencyKey,
        boolean idempotencyReserved,
        PaymentRail rail,
        PaymentDirection direction,
        long amountSats,
        long networkFeeSats,
        long totalDebitSats,
        boolean requiresSourceReserve,
        String proposalHash) {

    @Override
    public String toString() {
        return "PaymentSettlementGateCommand[userId=" + userId + ", executionId=" + executionId
                + ", rail=" + rail + ", direction=" + direction + ", amountSats=" + amountSats
                + ", networkFeeSats=" + networkFeeSats + ", totalDebitSats=" + totalDebitSats
                + ", idempotencyReserved=" + idempotencyReserved
                + ", requiresSourceReserve=" + requiresSourceReserve + ", references=REDACTED]";
    }
}
