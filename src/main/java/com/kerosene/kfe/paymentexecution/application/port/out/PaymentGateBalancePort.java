package com.kerosene.kfe.paymentexecution.application.port.out;

import java.util.UUID;

/** Acquire and retain the BTC balance row lock in the owning submit transaction. */
public interface PaymentGateBalancePort {
    long lockAvailable(UUID walletId);
}
