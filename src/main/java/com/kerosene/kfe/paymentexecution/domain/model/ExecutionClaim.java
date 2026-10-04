package com.kerosene.kfe.paymentexecution.domain.model;

import java.util.Objects;
import java.util.UUID;

/** Fenced ownership of one durable command; possession must be revalidated before dispatch. */
public record ExecutionClaim(UUID outboxId, UUID claimToken) {
    public ExecutionClaim {
        Objects.requireNonNull(outboxId, "outbox id is required");
        Objects.requireNonNull(claimToken, "claim token is required");
    }
    @Override public String toString() { return "ExecutionClaim[outboxId=" + outboxId + ", claimToken=REDACTED]"; }
}
