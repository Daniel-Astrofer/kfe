package com.kerosene.kfe.maintenance;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.concurrent.Executor;

/** Durable admission boundary. A draining workflow may finish only if already admitted. */
public interface KfeMaintenanceGuard {
    String SCHEMA = "kerosene.kfe-maintenance/v1";

    enum Mode { ACTIVE, DRAINING }
    enum Action { DRAIN, RESUME }

    record Command(String changeId, String reason, long expectedRevision) {
        public Command {
            if (changeId == null || !changeId.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")) {
                throw new IllegalArgumentException("changeId must be 1–128 identifier characters.");
            }
            if (reason == null || reason.isBlank() || reason.length() > 512
                    || reason.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("reason must be 1–512 printable characters.");
            }
            reason = reason.trim();
            if (expectedRevision < 0) {
                throw new IllegalArgumentException("expectedRevision must be nonnegative.");
            }
        }
    }

    record Status(String schema, Mode mode, String changeId, long revision,
                  Instant observedAt, boolean safeToUpdate, Map<String, Long> blockers) {
        public Status {
            Objects.requireNonNull(observedAt);
            blockers = Map.copyOf(blockers);
        }
    }

    final class MaintenanceException extends RuntimeException {
        private final int httpStatus;

        public MaintenanceException(int httpStatus, String message) {
            super(message);
            this.httpStatus = httpStatus;
        }

        public int httpStatus() { return httpStatus; }
    }

    Status requestDrain(Command command, long operatorId);
    Status resume(Command command, long operatorId);
    Status status();

    <T> T executeMutation(String operation, Supplier<T> work, Predicate<T> certainCompletion);

    /** Persist before enqueue; only descendants of an admitted workflow may finish during drain. */
    default void scheduleContinuation(String operation, Executor executor, Runnable work) {
        throw new MaintenanceException(503, "KFE continuation admission is unavailable.");
    }

    default <T> T executeMutation(String operation, Supplier<T> work) {
        return executeMutation(operation, work, ignored -> true);
    }

    /** Compatibility construction stays fail-closed until Spring supplies the real guard. */
    static KfeMaintenanceGuard unavailable() {
        return new KfeMaintenanceGuard() {
            private MaintenanceException unavailableException() {
                return new MaintenanceException(503, "KFE maintenance admission is unavailable.");
            }
            public Status requestDrain(Command command, long operatorId) { throw unavailableException(); }
            public Status resume(Command command, long operatorId) { throw unavailableException(); }
            public Status status() { throw unavailableException(); }
            public <T> T executeMutation(String operation, Supplier<T> work, Predicate<T> completion) {
                throw unavailableException();
            }
        };
    }
}
