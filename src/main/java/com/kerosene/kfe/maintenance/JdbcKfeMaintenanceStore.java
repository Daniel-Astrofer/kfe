package com.kerosene.kfe.maintenance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

import static com.kerosene.kfe.maintenance.KfeMaintenanceGuard.*;

/** PostgreSQL store. Every call owns a short, independent durable transaction. */
@Repository
public class JdbcKfeMaintenanceStore implements KfeMaintenanceStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public JdbcKfeMaintenanceStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setTimeout(15);
    }

    public Control transition(Action action, Command command, long operatorId) {
        Objects.requireNonNull(action);
        Objects.requireNonNull(command);
        if (operatorId <= 0) {
            throw new MaintenanceException(401, "An authenticated operator is required.");
        }
        return inTransaction(() -> {
            Control current = lockControl();
            var previous = jdbc.query("""
                    SELECT operator_id, reason, expected_revision
                    FROM financial.kfe_maintenance_audit WHERE change_id = ? AND action = ?
                    """, (row, index) -> new Replay(row.getLong("operator_id"),
                    row.getString("reason"), row.getLong("expected_revision")),
                    command.changeId(), action.name());
            if (!previous.isEmpty()) {
                Replay replay = previous.getFirst();
                if (replay.operatorId != operatorId || !replay.reason.equals(command.reason())
                        || replay.expectedRevision != command.expectedRevision()) {
                    throw new MaintenanceException(409, "Maintenance changeId conflicts with an audited request.");
                }
                return current;
            }
            if (current.revision() != command.expectedRevision()) {
                throw new MaintenanceException(409, "Maintenance revision changed; read status before retrying.");
            }
            Mode target = action == Action.DRAIN ? Mode.DRAINING : Mode.ACTIVE;
            if (action == Action.DRAIN && current.mode() != Mode.ACTIVE) {
                throw new MaintenanceException(409, "Another maintenance drain owns this Cell.");
            }
            if (action == Action.RESUME && (current.mode() != Mode.DRAINING
                    || !command.changeId().equals(current.changeId()))) {
                throw new MaintenanceException(409, "Resume requires the active drain changeId.");
            }
            long revision = Math.addExact(current.revision(), 1L);
            Instant now = databaseTime();
            jdbc.update("""
                    INSERT INTO financial.kfe_maintenance_audit
                    (id, action, change_id, operator_id, reason, expected_revision, revision,
                     from_mode, to_mode, occurred_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), action.name(), command.changeId(), operatorId,
                    command.reason(), command.expectedRevision(), revision, current.mode().name(),
                    target.name(), Timestamp.from(now));
            if (jdbc.update("""
                    UPDATE financial.kfe_maintenance_control SET mode = ?, change_id = ?, revision = ?,
                    changed_at = ?, operator_id = ?, reason = ? WHERE singleton_id = 1 AND revision = ?
                    """, target.name(), command.changeId(), revision, Timestamp.from(now), operatorId,
                    command.reason(), current.revision()) != 1) {
                throw new IllegalStateException("Maintenance control update did not affect its singleton.");
            }
            return new Control(target, command.changeId(), revision);
        });
    }

    public Admission admit(String operation) {
        if (operation == null || operation.isBlank() || operation.length() > 128) {
            throw new IllegalArgumentException("A bounded operation name is required.");
        }
        return inTransaction(() -> {
            Control control = lockControl();
            if (control.mode() != Mode.ACTIVE) {
                throw new MaintenanceException(503, "KFE is draining for changeId=" + control.changeId());
            }
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO financial.kfe_maintenance_admissions
                    (id, operation, admitted_revision, state, admitted_at)
                    VALUES (?, ?, ?, 'IN_FLIGHT', ?)
                    """, id, operation, control.revision(), Timestamp.from(databaseTime()));
            return new Admission(id, control.revision());
        });
    }

    public void resolve(UUID admissionId, boolean certainCompletion) {
        Objects.requireNonNull(admissionId);
        inTransaction(() -> {
            // UNCERTAIN is sticky. A late callback cannot clear an already uncertain workflow.
            jdbc.update("""
                    UPDATE financial.kfe_maintenance_admissions SET state = ?, completed_at = ?
                    WHERE id = ? AND state = 'IN_FLIGHT'
                    """, certainCompletion ? "COMPLETED" : "UNCERTAIN",
                    certainCompletion ? Timestamp.from(databaseTime()) : null, admissionId);
            return Boolean.TRUE;
        });
    }

    public Admission captureContinuation(UUID parentId, String operation, boolean waitingForCommit) {
        Objects.requireNonNull(parentId);
        if (operation == null || operation.isBlank() || operation.length() > 128) {
            throw new IllegalArgumentException("A bounded operation name is required.");
        }
        return inTransaction(() -> {
            lockControl();
            var parents = jdbc.query("""
                    SELECT id, admitted_revision FROM financial.kfe_maintenance_admissions
                    WHERE id = ? AND state = 'IN_FLIGHT' FOR UPDATE
                    """, (row, index) -> new Admission(row.getObject("id", UUID.class),
                    row.getLong("admitted_revision")), parentId);
            if (parents.size() != 1) {
                throw new MaintenanceException(409, "Continuation requires an unresolved admitted parent.");
            }
            Admission child = new Admission(UUID.randomUUID(), parents.getFirst().revision());
            jdbc.update("""
                    INSERT INTO financial.kfe_maintenance_admissions
                    (id, operation, admitted_revision, state, admitted_at, parent_admission_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, child.id(), operation, child.revision(), waitingForCommit ? "WAITING" : "READY",
                    Timestamp.from(databaseTime()), parentId);
            return child;
        });
    }

    public void releaseContinuation(UUID admissionId, boolean committed) {
        Objects.requireNonNull(admissionId);
        inTransaction(() -> {
            lockControl();
            if (jdbc.update("""
                    UPDATE financial.kfe_maintenance_admissions SET state = ?, completed_at = ?
                    WHERE id = ? AND state = 'WAITING' AND parent_admission_id IS NOT NULL
                    """, committed ? "READY" : "CANCELLED",
                    committed ? null : Timestamp.from(databaseTime()), admissionId) != 1) {
                throw new MaintenanceException(409, "Continuation was not waiting for transaction completion.");
            }
            return Boolean.TRUE;
        });
    }

    public Admission claimContinuation(UUID admissionId) {
        Objects.requireNonNull(admissionId);
        return inTransaction(() -> {
            lockControl();
            var claims = jdbc.query("""
                    UPDATE financial.kfe_maintenance_admissions SET state = 'IN_FLIGHT'
                    WHERE id = ? AND state = 'READY' AND parent_admission_id IS NOT NULL
                    RETURNING id, admitted_revision
                    """, (row, index) -> new Admission(row.getObject("id", UUID.class),
                    row.getLong("admitted_revision")), admissionId);
            if (claims.size() != 1) {
                throw new MaintenanceException(409, "Continuation is absent, not ready or already claimed.");
            }
            return claims.getFirst();
        });
    }

    public Observation observe() {
        return inTransaction(() -> {
            // Blocks new admissions/transitions while collecting financial observations.
            Control control = lockControl();
            Map<String, Long> blockers = new LinkedHashMap<>();
            count(blockers, "admissionsInFlight", "financial.kfe_maintenance_admissions", "state = 'IN_FLIGHT'");
            count(blockers, "admissionsUncertain", "financial.kfe_maintenance_admissions", "state = 'UNCERTAIN'");
            count(blockers, "continuationsWaiting", "financial.kfe_maintenance_admissions", "state = 'WAITING'");
            count(blockers, "continuationsReady", "financial.kfe_maintenance_admissions", "state = 'READY'");
            count(blockers, "admissionStatusUnknown", "financial.kfe_maintenance_admissions",
                    "state IS NULL OR state NOT IN ('WAITING','READY','IN_FLIGHT','UNCERTAIN','COMPLETED','CANCELLED')");
            count(blockers, "outboxQueued", "financial.financial_execution_outbox",
                    "status IN ('PENDING', 'FAILED_RETRYABLE')");
            count(blockers, "outboxInFlight", "financial.financial_execution_outbox", "status = 'PROCESSING'");
            count(blockers, "outboxUncertain", "financial.financial_execution_outbox", "status = 'UNKNOWN'");
            count(blockers, "outboxStatusUnknown", "financial.financial_execution_outbox",
                    "status IS NULL OR status NOT IN ('PENDING','FAILED_RETRYABLE','PROCESSING',"
                            + "'UNKNOWN','DISPATCHED','FAILED_FINAL','CANCELLED')");
            count(blockers, "transactionsPending", "financial.transactions_master",
                    "status IN ('INTENT','VALIDATING','QUORUM_SYNC','LOCKED','EXECUTING','BROADCAST','CONFIRMING')");
            count(blockers, "transactionsReconciliation", "financial.transactions_master",
                    "status IN ('REQUIRES_RECONCILIATION','CONFLICTED','CONFLICTED_RECONCILING',"
                            + "'REORG_RECONCILIATION','DROPPED','ABANDONED')");
            count(blockers, "transactionStatusUnknown", "financial.transactions_master",
                    "status IS NULL OR status NOT IN ('INTENT','VALIDATING','QUORUM_SYNC','LOCKED',"
                            + "'EXECUTING','BROADCAST','CONFIRMING','SETTLED','FAILED','CANCELLED',"
                            + "'REQUIRES_RECONCILIATION','CONFLICTED','CONFLICTED_RECONCILING',"
                            + "'CONFLICTED_REFUNDED','REORG_RECONCILIATION','DROPPED','ABANDONED')");
            for (String kind : new String[]{"capacity", "rebalance"}) {
                String table = "financial.channel_" + kind + "_jobs";
                count(blockers, kind + "Queued", table, "status = 'PENDING'");
                count(blockers, kind + "InFlight", table, "status = 'IN_PROGRESS'");
                count(blockers, kind + "StatusUnknown", table,
                        "status IS NULL OR status NOT IN ('PENDING','IN_PROGRESS','COMPLETED','FAILED','CANCELLED')");
            }
            count(blockers, "meshUnresolved", "financial.channel_operation_decisions",
                    "mesh_inject_phase IS NOT NULL AND mesh_inject_phase NOT IN ('COMMITTED','RELEASED')");
            count(blockers, "psbtOutstanding", "financial.kfe_psbt_workflows",
                    "status IN ('CREATED','SIGNED','FINALIZED','BROADCAST')");
            count(blockers, "psbtStatusUnknown", "financial.kfe_psbt_workflows",
                    "status IS NULL OR status NOT IN ('CREATED','SIGNED','FINALIZED','BROADCAST','FAILED')");
            return new Observation(control, databaseTime(), Map.copyOf(blockers));
        });
    }

    private Control lockControl() {
        var rows = jdbc.query("""
                SELECT mode, change_id, revision FROM financial.kfe_maintenance_control
                WHERE singleton_id = 1 FOR UPDATE
                """, (row, index) -> new Control(Mode.valueOf(row.getString("mode")),
                row.getString("change_id"), row.getLong("revision")));
        if (rows.size() != 1) {
            throw new IllegalStateException("Maintenance control singleton is missing.");
        }
        return rows.getFirst();
    }

    private void count(Map<String, Long> blockers, String name, String table, String predicate) {
        // Table and predicate are compile-time inventory entries, never caller input.
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + predicate, Long.class);
        blockers.put(name, Objects.requireNonNull(count));
    }

    private Instant databaseTime() {
        return Objects.requireNonNull(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class)).toInstant();
    }

    private <T> T inTransaction(Supplier<T> work) {
        return Objects.requireNonNull(transaction.execute(ignored -> work.get()));
    }

    private record Replay(long operatorId, String reason, long expectedRevision) { }
}
