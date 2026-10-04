package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.UUID;

/** Payment Execution's minimal view of a wallet; not the Wallet aggregate. */
public record PaymentWalletSnapshot(UUID id, long userId, boolean active, boolean watchOnly, boolean spendable) {
    public PaymentWalletSnapshot {
        if (id == null || userId <= 0L) {
            throw new IllegalArgumentException("wallet id and owner are required");
        }
    }

    public boolean usable() { return active && !watchOnly && spendable; }

    public void requireSpendable(String role) {
        if (!active) {
            throw new IllegalStateException(role + " wallet is not active.");
        }
        if (watchOnly || !spendable) {
            throw new IllegalStateException(role + " wallet is watch-only and cannot move funds.");
        }
    }
}
