package com.kerosene.kfe.ledger.domain;

import java.time.Instant;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;

/** Immutable participant-facing statement projection input. */
public record StatementRecord(Long userId, UUID transactionId, UUID walletId,
        Instant ledgerCreatedAt, Instant expiresAt, Map<String, ?> payload) {
    public StatementRecord {
        Objects.requireNonNull(userId, "userId is required");
        Objects.requireNonNull(transactionId, "transactionId is required");
        Objects.requireNonNull(ledgerCreatedAt, "ledgerCreatedAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        payload = payload == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        if (userId <= 0L || !expiresAt.isAfter(ledgerCreatedAt)) {
            throw new LedgerInvariantViolation("invalid statement identity or expiry");
        }
    }
}
