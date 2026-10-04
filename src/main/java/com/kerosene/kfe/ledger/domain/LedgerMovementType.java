package com.kerosene.kfe.ledger.domain;

/** Canonical movement vocabulary. A posting is immutable once accepted. */
public enum LedgerMovementType {
    RESERVE,
    RELEASE_RESERVE,
    SETTLE_DEBIT,
    CREDIT,
    CREDIT_FEE,
    COMPENSATING_CREDIT,
    COMPENSATING_DEBIT
}
