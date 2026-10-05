package com.kerosene.kfe.ledger.domain;

/** Raised when a ledger command would break a monetary invariant. */
public class LedgerInvariantViolation extends IllegalArgumentException {
    public LedgerInvariantViolation(String message) {
        super(message);
    }
}
