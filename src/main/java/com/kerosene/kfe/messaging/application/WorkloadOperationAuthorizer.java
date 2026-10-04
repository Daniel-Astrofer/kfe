package com.kerosene.kfe.messaging.application;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Explicit allow-list for workload-to-operation authorization.
 *
 * <p>An authenticated SPIFFE identity alone is not permission to publish every
 * message. Missing or unknown policy is deliberately denied.</p>
 */
public final class WorkloadOperationAuthorizer {

    private final Map<String, Set<String>> allowedOperationsByWorkload;

    public WorkloadOperationAuthorizer(Map<String, Set<String>> allowedOperationsByWorkload) {
        Objects.requireNonNull(allowedOperationsByWorkload, "allowedOperationsByWorkload is required");
        this.allowedOperationsByWorkload = allowedOperationsByWorkload.entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        entry -> normalize(entry.getKey(), "workload"),
                        entry -> entry.getValue() == null
                                ? Set.of()
                                : entry.getValue().stream()
                                        .map(value -> normalize(value, "operation"))
                                        .collect(java.util.stream.Collectors.toUnmodifiableSet())));
    }

    public void requireAllowed(WorkloadIdentity workload, String operation) {
        Objects.requireNonNull(workload, "workload is required");
        String normalizedOperation = normalize(operation, "operation");
        Set<String> allowed = allowedOperationsByWorkload.get(workload.spiffeId());
        if (allowed == null || !allowed.contains(normalizedOperation)) {
            throw new SecurityException("workload is not authorized for operation: " + normalizedOperation);
        }
    }

    private static String normalize(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
