package com.kerosene.kfe.maintenance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.concurrent.Executor;

@Service
public class KfeMaintenanceService implements KfeMaintenanceGuard {
    private static final Logger log = LoggerFactory.getLogger(KfeMaintenanceService.class);
    private final KfeMaintenanceStore store;
    private final ThreadLocal<Workflow> workflow = new ThreadLocal<>();

    public KfeMaintenanceService(KfeMaintenanceStore store) {
        this.store = Objects.requireNonNull(store);
    }

    public Status requestDrain(Command command, long operatorId) {
        return transition(Action.DRAIN, command, operatorId);
    }

    public Status resume(Command command, long operatorId) {
        return transition(Action.RESUME, command, operatorId);
    }

    private Status transition(Action action, Command command, long operatorId) {
        if (operatorId <= 0) {
            throw new MaintenanceException(401, "An authenticated operator is required.");
        }
        Objects.requireNonNull(command);
        KfeMaintenanceStore.Control control = store.transition(action, command, operatorId);
        Status observed = status();
        if (observed.mode() == null) {
            return new Status(SCHEMA, control.mode(), control.changeId(), control.revision(),
                    observed.observedAt(), false, observed.blockers());
        }
        return observed;
    }

    public Status status() {
        Map<String, Long> blockers = new LinkedHashMap<>();
        // Unowned entrypoints/callbacks have not been inventoried and guarded. No override flag.
        blockers.put("mutationCoverageUnknown", 1L);
        blockers.put("callbackCoverageUnknown", 1L);
        blockers.put("readSideEffectsUnknown", 1L);
        try {
            KfeMaintenanceStore.Observation observation = store.observe();
            blockers.putAll(observation.blockers());
            boolean safe = observation.control().mode() == Mode.DRAINING
                    && blockers.values().stream().allMatch(count -> count != null && count == 0L);
            return new Status(SCHEMA, observation.control().mode(), observation.control().changeId(),
                    observation.control().revision(), observation.observedAt(), safe, blockers);
        } catch (RuntimeException failure) {
            blockers.put("observationUnavailable", 1L);
            // Null mode/revision -1 are explicitly unknown; never fabricate ACTIVE or DRAINING.
            return new Status(SCHEMA, null, null, -1L, Instant.now(), false, blockers);
        }
    }

    public <T> T executeMutation(String operation, Supplier<T> work, Predicate<T> certainCompletion) {
        Objects.requireNonNull(work);
        Objects.requireNonNull(certainCompletion);
        Workflow existing = workflow.get();
        if (existing != null) {
            return perform(existing, work, certainCompletion);
        }
        if (operation == null || operation.isBlank() || operation.length() > 128) {
            throw new IllegalArgumentException("A bounded operation name is required.");
        }
        KfeMaintenanceStore.Admission admission;
        try {
            admission = store.admit(operation);
        } catch (MaintenanceException rejection) {
            throw rejection;
        } catch (RuntimeException failure) {
            throw new MaintenanceException(503, "KFE maintenance admission is unavailable.");
        }
        Workflow root = new Workflow(admission);
        workflow.set(root);
        boolean transactionBound = TransactionSynchronizationManager.isActualTransactionActive();
        try {
            if (transactionBound) {
                if (!TransactionSynchronizationManager.isSynchronizationActive()) {
                    root.certain = false;
                    throw new MaintenanceException(503, "KFE transaction completion cannot be observed.");
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    public void afterCompletion(int status) {
                        resolve(root, root.certain && status == STATUS_COMMITTED);
                    }
                });
            }
            return perform(root, work, certainCompletion);
        } finally {
            workflow.remove();
            if (!transactionBound) {
                resolve(root, root.certain);
            }
        }
    }

    private <T> T perform(Workflow root, Supplier<T> work, Predicate<T> completion) {
        try {
            T result = work.get();
            root.certain &= completion.test(result);
            return result;
        } catch (RuntimeException | Error failure) {
            root.certain = false;
            throw failure;
        }
    }

    public void scheduleContinuation(String operation, Executor executor, Runnable work) {
        Objects.requireNonNull(executor);
        Objects.requireNonNull(work);
        Workflow parent = workflow.get();
        if (parent == null) {
            throw new MaintenanceException(409, "Continuation requires a currently admitted workflow.");
        }
        boolean transactionBound = TransactionSynchronizationManager.isActualTransactionActive();
        if (transactionBound && !TransactionSynchronizationManager.isSynchronizationActive()) {
            parent.certain = false;
            throw new MaintenanceException(503, "KFE transaction completion cannot be observed.");
        }
        KfeMaintenanceStore.Admission child;
        try {
            child = store.captureContinuation(parent.admission.id(), operation, transactionBound);
        } catch (RuntimeException failure) {
            parent.certain = false;
            throw new MaintenanceException(503, "KFE continuation could not be persisted.");
        }
        Runnable enqueue = () -> enqueueContinuation(child, executor, work);
        if (!transactionBound) {
            enqueue.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void afterCompletion(int status) {
                // Unknown completion is not rollback proof and leaves WAITING unresolved.
                if (status != STATUS_COMMITTED && status != STATUS_ROLLED_BACK) {
                    return;
                }
                try {
                    store.releaseContinuation(child.id(), status == STATUS_COMMITTED);
                    if (status == STATUS_COMMITTED) {
                        enqueue.run();
                    }
                } catch (RuntimeException failure) {
                    log.warn("KFE continuation remains unresolved admissionId={}", child.id());
                }
            }
        });
    }

    private void enqueueContinuation(KfeMaintenanceStore.Admission child, Executor executor, Runnable work) {
        try {
            executor.execute(() -> runContinuation(child.id(), work));
        } catch (RuntimeException failure) {
            // Executor rejection is not reliable proof that a custom executor did not start work.
            // The durable READY/IN_FLIGHT child remains a blocker, without changing finance results.
            log.warn("KFE continuation scheduling remains unresolved admissionId={}", child.id());
        }
    }

    private void runContinuation(java.util.UUID id, Runnable work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // A direct executor during afterCompletion must not reuse a completed transaction.
            log.warn("KFE continuation requires a transaction-free executor thread admissionId={}", id);
            return;
        }
        // Direct executors must not share the parent's workflow or transaction-bound continuation.
        Workflow previous = workflow.get();
        KfeMaintenanceStore.Admission admission = store.claimContinuation(id);
        Workflow child = new Workflow(admission);
        workflow.set(child);
        try {
            perform(child, () -> { work.run(); return Boolean.TRUE; }, ignored -> true);
        } finally {
            if (previous == null) {
                workflow.remove();
            } else {
                workflow.set(previous);
            }
            resolve(child, child.certain);
        }
    }

    private void resolve(Workflow root, boolean certain) {
        try {
            store.resolve(root.admission.id(), certain);
        } catch (RuntimeException failure) {
            // Preserve the durable IN_FLIGHT blocker. Do not change the financial result.
            log.warn("KFE maintenance admission completion could not be persisted admissionId={}",
                    root.admission.id());
        }
    }

    private static final class Workflow {
        private final KfeMaintenanceStore.Admission admission;
        private boolean certain = true;
        private Workflow(KfeMaintenanceStore.Admission admission) { this.admission = admission; }
    }
}
