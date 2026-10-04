package com.kerosene.kfe.messaging.application;

import java.util.Objects;

/** Authenticated workload identity supplied by the transport, never by message payload. */
public record WorkloadIdentity(String spiffeId) {
    public WorkloadIdentity {
        Objects.requireNonNull(spiffeId, "spiffeId is required");
        if (!spiffeId.startsWith("spiffe://") || spiffeId.length() > 255) {
            throw new IllegalArgumentException("workload identity must be a valid SPIFFE id");
        }
    }
}
