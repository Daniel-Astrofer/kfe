package com.kerosene.kfe.messaging.envelope;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Stable message metadata. Transport identity is intentionally absent from the
 * payload: it is supplied by the authenticated channel adapter.
 */
public record MessageEnvelope(
        UUID messageId,
        String messageKind,
        String type,
        int schemaVersion,
        Instant occurredAt,
        UUID correlationId,
        UUID causationId,
        String aggregateId,
        long aggregateVersion,
        String idempotencyKey,
        String payload) {

    public MessageEnvelope {
        Objects.requireNonNull(messageId, "messageId is required");
        requireText(messageKind, "messageKind");
        requireText(type, "type");
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be positive");
        }
        Objects.requireNonNull(occurredAt, "occurredAt is required");
        if (aggregateVersion < 0) {
            throw new IllegalArgumentException("aggregateVersion cannot be negative");
        }
        Objects.requireNonNull(payload, "payload is required");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
