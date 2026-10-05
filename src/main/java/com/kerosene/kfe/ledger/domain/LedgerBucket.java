package com.kerosene.kfe.ledger.domain;

/** Buckets that make up a wallet's custodial ledger balance. */
public enum LedgerBucket {
    AVAILABLE,
    PENDING,
    LOCKED,
    AUTO_HOLD,
    OBSERVED
}
