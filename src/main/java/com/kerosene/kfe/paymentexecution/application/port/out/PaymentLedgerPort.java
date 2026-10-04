package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.PaymentExecutionId;

import java.util.UUID;

/**
 * Balance effects for payment execution, joined to the caller's transaction.
 * A reservation, debit or credit succeeds only together with its ledger movement.
 */
public interface PaymentLedgerPort {

    void reserve(PaymentExecutionId executionId, UUID walletId, long amountSats);

    void settleReservedDebit(PaymentExecutionId executionId, UUID walletId, long amountSats);

    void creditAvailable(PaymentExecutionId executionId, UUID walletId, long amountSats);

    void releaseReserved(PaymentExecutionId executionId, UUID walletId, long amountSats);
}
