package com.kerosene.kfe.application.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeHashService;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KfeTransactionStateMachineTest {

    private final KfeTransactionRepository transactionRepository = mock(KfeTransactionRepository.class);
    private final KfeAuditLogService auditLogService = mock(KfeAuditLogService.class);
    private final KfeHashService hashService = mock(KfeHashService.class);
    private final KfeTransactionStateMachine stateMachine =
            new KfeTransactionStateMachine(transactionRepository, auditLogService, hashService);

    // -------------------------------------------------------
    // Permitted transitions — every allowed edge in the graph
    // -------------------------------------------------------

    @ParameterizedTest
    @MethodSource("permittedTransitions")
    void permitsDefinedTransition(KfeTransactionStatus from, KfeTransactionStatus to) {
        KfeTransactionEntity tx = entityWithStatus(from);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        assertDoesNotThrow(() -> stateMachine.transition(tx, to, "TEST", Map.of()));
        assertThat(tx.getStatus()).isEqualTo(to);
        verify(transactionRepository).save(tx);
    }

    static Stream<Arguments> permittedTransitions() {
        return Stream.of(
                // INTENT
                Arguments.of(KfeTransactionStatus.INTENT, KfeTransactionStatus.VALIDATING),
                Arguments.of(KfeTransactionStatus.INTENT, KfeTransactionStatus.FAILED),
                // VALIDATING
                Arguments.of(KfeTransactionStatus.VALIDATING, KfeTransactionStatus.QUORUM_SYNC),
                Arguments.of(KfeTransactionStatus.VALIDATING, KfeTransactionStatus.FAILED),
                Arguments.of(KfeTransactionStatus.VALIDATING, KfeTransactionStatus.CANCELLED),
                // QUORUM_SYNC
                Arguments.of(KfeTransactionStatus.QUORUM_SYNC, KfeTransactionStatus.LOCKED),
                Arguments.of(KfeTransactionStatus.QUORUM_SYNC, KfeTransactionStatus.FAILED),
                Arguments.of(KfeTransactionStatus.QUORUM_SYNC,
                        KfeTransactionStatus.REQUIRES_RECONCILIATION),
                // LOCKED
                Arguments.of(KfeTransactionStatus.LOCKED, KfeTransactionStatus.EXECUTING),
                Arguments.of(KfeTransactionStatus.LOCKED, KfeTransactionStatus.SETTLED),
                Arguments.of(KfeTransactionStatus.LOCKED, KfeTransactionStatus.FAILED),
                Arguments.of(KfeTransactionStatus.LOCKED, KfeTransactionStatus.CANCELLED),
                Arguments.of(KfeTransactionStatus.LOCKED, KfeTransactionStatus.REQUIRES_RECONCILIATION),
                // EXECUTING
                Arguments.of(KfeTransactionStatus.EXECUTING, KfeTransactionStatus.BROADCAST),
                Arguments.of(KfeTransactionStatus.EXECUTING, KfeTransactionStatus.SETTLED),
                Arguments.of(KfeTransactionStatus.EXECUTING, KfeTransactionStatus.FAILED),
                Arguments.of(KfeTransactionStatus.EXECUTING,
                        KfeTransactionStatus.CONFLICTED_RECONCILING),
                Arguments.of(KfeTransactionStatus.EXECUTING,
                        KfeTransactionStatus.REQUIRES_RECONCILIATION),
                // BROADCAST
                Arguments.of(KfeTransactionStatus.BROADCAST, KfeTransactionStatus.CONFIRMING),
                Arguments.of(KfeTransactionStatus.BROADCAST, KfeTransactionStatus.SETTLED),
                Arguments.of(KfeTransactionStatus.BROADCAST, KfeTransactionStatus.FAILED),
                Arguments.of(KfeTransactionStatus.BROADCAST,
                        KfeTransactionStatus.CONFLICTED_RECONCILING),
                Arguments.of(KfeTransactionStatus.BROADCAST,
                        KfeTransactionStatus.REQUIRES_RECONCILIATION),
                // CONFIRMING
                Arguments.of(KfeTransactionStatus.CONFIRMING, KfeTransactionStatus.SETTLED),
                Arguments.of(KfeTransactionStatus.CONFIRMING, KfeTransactionStatus.FAILED),
                Arguments.of(KfeTransactionStatus.CONFIRMING,
                        KfeTransactionStatus.CONFLICTED_RECONCILING),
                Arguments.of(KfeTransactionStatus.CONFIRMING,
                        KfeTransactionStatus.REQUIRES_RECONCILIATION),
                // SETTLED
                Arguments.of(KfeTransactionStatus.SETTLED, KfeTransactionStatus.REORG_RECONCILIATION),
                // CONFLICTED_RECONCILING
                Arguments.of(KfeTransactionStatus.CONFLICTED_RECONCILING,
                        KfeTransactionStatus.FAILED),
                Arguments.of(KfeTransactionStatus.CONFLICTED_RECONCILING,
                        KfeTransactionStatus.BROADCAST),
                Arguments.of(KfeTransactionStatus.CONFLICTED_RECONCILING,
                        KfeTransactionStatus.REQUIRES_RECONCILIATION),
                // CONFLICTED_REFUNDED
                Arguments.of(KfeTransactionStatus.CONFLICTED_REFUNDED,
                        KfeTransactionStatus.REQUIRES_RECONCILIATION),
                // REORG_RECONCILIATION
                Arguments.of(KfeTransactionStatus.REORG_RECONCILIATION,
                        KfeTransactionStatus.SETTLED),
                Arguments.of(KfeTransactionStatus.REORG_RECONCILIATION,
                        KfeTransactionStatus.FAILED),
                Arguments.of(KfeTransactionStatus.REORG_RECONCILIATION,
                        KfeTransactionStatus.REQUIRES_RECONCILIATION),
                // REQUIRES_RECONCILIATION
                Arguments.of(KfeTransactionStatus.REQUIRES_RECONCILIATION,
                        KfeTransactionStatus.EXECUTING),
                Arguments.of(KfeTransactionStatus.REQUIRES_RECONCILIATION,
                        KfeTransactionStatus.SETTLED),
                Arguments.of(KfeTransactionStatus.REQUIRES_RECONCILIATION,
                        KfeTransactionStatus.FAILED));
    }

    // -------------------------------------------------------
    // Forbidden transitions — key edges that MUST be rejected
    // -------------------------------------------------------

    @ParameterizedTest
    @MethodSource("forbiddenTransitions")
    void rejectsInvalidTransition(KfeTransactionStatus from, KfeTransactionStatus to) {
        KfeTransactionEntity tx = entityWithStatus(from);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> stateMachine.transition(tx, to, "TEST", Map.of()));
        assertThat(ex.getMessage()).contains(from.name()).contains(to.name());
    }

    static Stream<Arguments> forbiddenTransitions() {
        return Stream.of(
                // SETTLED has only REORG_RECONCILIATION — everything else forbidden
                Arguments.of(KfeTransactionStatus.SETTLED, KfeTransactionStatus.EXECUTING),
                Arguments.of(KfeTransactionStatus.SETTLED, KfeTransactionStatus.FAILED),
                Arguments.of(KfeTransactionStatus.SETTLED, KfeTransactionStatus.BROADCAST),
                Arguments.of(KfeTransactionStatus.SETTLED, KfeTransactionStatus.CONFIRMING),
                // Terminal states — no outgoing except self
                Arguments.of(KfeTransactionStatus.FAILED, KfeTransactionStatus.EXECUTING),
                Arguments.of(KfeTransactionStatus.FAILED, KfeTransactionStatus.SETTLED),
                Arguments.of(KfeTransactionStatus.CANCELLED, KfeTransactionStatus.EXECUTING),
                Arguments.of(KfeTransactionStatus.CANCELLED, KfeTransactionStatus.SETTLED),
                Arguments.of(KfeTransactionStatus.DROPPED, KfeTransactionStatus.EXECUTING),
                Arguments.of(KfeTransactionStatus.ABANDONED, KfeTransactionStatus.EXECUTING),
                // CONFLICTED — no outgoing except self (falls to default→false)
                Arguments.of(KfeTransactionStatus.CONFLICTED, KfeTransactionStatus.SETTLED),
                Arguments.of(KfeTransactionStatus.CONFLICTED, KfeTransactionStatus.EXECUTING),
                // Backwards transitions
                Arguments.of(KfeTransactionStatus.EXECUTING, KfeTransactionStatus.INTENT),
                Arguments.of(KfeTransactionStatus.BROADCAST, KfeTransactionStatus.EXECUTING),
                Arguments.of(KfeTransactionStatus.CONFIRMING, KfeTransactionStatus.BROADCAST));
    }

    // -------------------------------------------------------
    // Idempotent transitions — same→same always allowed
    // -------------------------------------------------------

    @Test
    void sameStateTransitionIsIdempotent() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.EXECUTING);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        assertDoesNotThrow(() -> stateMachine.transition(
                tx, KfeTransactionStatus.EXECUTING, "IDEMPOTENT_CHECK", Map.of()));
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
    }

    @Test
    void terminalStateSelfTransitionIsIdempotent() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.FAILED);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        assertDoesNotThrow(() -> stateMachine.transition(
                tx, KfeTransactionStatus.FAILED, "IDEMPOTENT_CHECK", Map.of()));
        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
    }

    // -------------------------------------------------------
    // Business-critical transitions — explicit named coverage
    // -------------------------------------------------------

    @Test
    void executingToConflictedReconciling() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.EXECUTING);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        stateMachine.transition(tx, KfeTransactionStatus.CONFLICTED_RECONCILING,
                "CONFLICT_DETECTED", Map.of());

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.CONFLICTED_RECONCILING);
    }

    @Test
    void broadcastToSettled() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.BROADCAST);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        stateMachine.transition(tx, KfeTransactionStatus.SETTLED, "CONFIRMED", Map.of());

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
    }

    @Test
    void confirmingToSettled() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.CONFIRMING);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        stateMachine.transition(tx, KfeTransactionStatus.SETTLED, "FULLY_CONFIRMED", Map.of());

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.SETTLED);
    }

    @Test
    void settledToReorgReconciliation() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.SETTLED);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        stateMachine.transition(tx, KfeTransactionStatus.REORG_RECONCILIATION,
                "REORG_DETECTED", Map.of());

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.REORG_RECONCILIATION);
    }

    @Test
    void requiresReconciliationToExecuting() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.REQUIRES_RECONCILIATION);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        stateMachine.transition(tx, KfeTransactionStatus.EXECUTING, "RECOVERY_RETRY", Map.of());

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.EXECUTING);
    }

    @Test
    void conflictedReconcilingToFailed() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.CONFLICTED_RECONCILING);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        stateMachine.transition(tx, KfeTransactionStatus.FAILED, "IRRECONCILABLE", Map.of());

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.FAILED);
    }

    @Test
    void conflictedReconcilingToBroadcast() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.CONFLICTED_RECONCILING);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        stateMachine.transition(tx, KfeTransactionStatus.BROADCAST, "REPLACEMENT_FOUND", Map.of());

        assertThat(tx.getStatus()).isEqualTo(KfeTransactionStatus.BROADCAST);
    }

    @Test
    void settledToExecutingIsForbidden() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.SETTLED);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> stateMachine.transition(tx, KfeTransactionStatus.EXECUTING, "TEST", Map.of()));
        assertThat(ex.getMessage()).contains("SETTLED").contains("EXECUTING");
    }

    @Test
    void settledToFailedIsForbiddenWithoutReorg() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.SETTLED);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> stateMachine.transition(tx, KfeTransactionStatus.FAILED, "TEST", Map.of()));
        assertThat(ex.getMessage()).contains("SETTLED").contains("FAILED");
    }

    @Test
    void conflictedToSettledIsForbiddenWithoutRecovery() {
        KfeTransactionEntity tx = entityWithStatus(KfeTransactionStatus.CONFLICTED);
        when(hashService.sha256(tx.getIdempotencyKey())).thenReturn("sha256-hash");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> stateMachine.transition(tx, KfeTransactionStatus.SETTLED, "TEST", Map.of()));
        assertThat(ex.getMessage()).contains("CONFLICTED").contains("SETTLED");
    }

    // -------------------------------------------------------
    // Helpers
    // -------------------------------------------------------

    private KfeTransactionEntity entityWithStatus(KfeTransactionStatus status) {
        KfeTransactionEntity tx = new KfeTransactionEntity();
        tx.setIdempotencyKey("idempotency-" + UUID.randomUUID());
        tx.setStatus(status);
        return tx;
    }
}
