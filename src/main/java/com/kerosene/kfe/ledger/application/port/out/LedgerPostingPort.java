package com.kerosene.kfe.ledger.application.port.out;

import com.kerosene.kfe.ledger.domain.LedgerPosting;
import com.kerosene.kfe.ledger.domain.LedgerMovementType;
import java.util.UUID;

/** Persists immutable postings idempotently by operation and movement type. */
public interface LedgerPostingPort {
    default boolean exists(UUID operationId, LedgerMovementType movementType) {
        return false;
    }

    boolean appendIfAbsent(LedgerPosting posting);
}
