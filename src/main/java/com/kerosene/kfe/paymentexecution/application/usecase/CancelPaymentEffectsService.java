package com.kerosene.kfe.paymentexecution.application.usecase;

import com.kerosene.kfe.paymentexecution.application.command.RecordPaymentStatementCommand;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationAuditPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentCancellationStatePort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLedgerPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentLiquidityPort;
import com.kerosene.kfe.paymentexecution.application.port.out.PaymentStatementPort;
import com.kerosene.kfe.paymentexecution.domain.exception.PaymentCancellationRejected;
import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

/** Financial cancellation effects; the caller must already hold the batch fence in its transaction. */
public final class CancelPaymentEffectsService {
    private final PaymentCancellationStatePort state;
    private final PaymentLedgerPort ledger;
    private final PaymentLiquidityPort liquidity;
    private final PaymentStatementPort statement;
    private final PaymentCancellationAuditPort audit;

    public CancelPaymentEffectsService(
            PaymentCancellationStatePort state, PaymentLedgerPort ledger, PaymentLiquidityPort liquidity,
            PaymentStatementPort statement, PaymentCancellationAuditPort audit) {
        this.state = state;
        this.ledger = ledger;
        this.liquidity = liquidity;
        this.statement = statement;
        this.audit = audit;
    }

    public void cancel(PaymentExecutionId executionId, String message) {
        // Never decide from a snapshot obtained before the caller acquired its fence.
        var previous = state.load(executionId);
        if (!executionId.equals(previous.executionId())) {
            throw new IllegalStateException("Cancellation state does not match requested execution.");
        }
        if (!previous.incomplete()) {
            return;
        }
        if (!previous.cancellable()) {
            throw new PaymentCancellationRejected();
        }
        if (previous.requiresReserveRelease()) {
            ledger.releaseReserved(executionId, previous.sourceWalletId(), previous.totalDebitSats());
        }
        if (previous.requiresLiquidityRelease()) {
            liquidity.release(executionId);
        }
        state.markCancelled(previous, message);
        statement.record(new RecordPaymentStatementCommand(
                previous.userId(), executionId, previous.statementWalletId(), null, true));
        audit.recordCancelled(previous);
    }
}
