package com.kerosene.kfe.application.transaction;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.model.KfeTransactionEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.repository.KfeTransactionRepository;
import com.kerosene.kfe.service.KfeAuditLogService;
import com.kerosene.kfe.service.KfeHashService;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class KfeTransactionStateMachine {

    private final KfeTransactionRepository transactionRepository;
    private final KfeAuditLogService auditLogService;
    private final KfeHashService hashService;

    public KfeTransactionStateMachine(
            KfeTransactionRepository transactionRepository,
            KfeAuditLogService auditLogService,
            KfeHashService hashService) {
        this.transactionRepository = transactionRepository;
        this.auditLogService = auditLogService;
        this.hashService = hashService;
    }

    /**
     * Atomically transitions a KFE transaction to a new state.
     *
     * <p>Runs within the caller's transaction. Callers operating outside a transaction
     * must wrap this in {@code @Transactional} to guarantee state-machine atomicity:
     * a crash between {@code save(tx)} and {@code audit(...)} must not leave the
     * transaction in the new state without forensic evidence.
     */
    @Transactional
    public void transition(
            KfeTransactionEntity tx,
            KfeTransactionStatus target,
            String eventType,
            Map<String, ?> auditPayload) {
        KfeTransactionStatus previous = tx.getStatus();
        if (!canTransition(previous, target)) {
            throw new IllegalStateException("Invalid KFE transaction transition from " + previous + " to " + target + ".");
        }
        tx.setStatus(target);
        transactionRepository.save(tx);
        audit(tx, eventType, previous, target, auditPayload);
    }

    public void audit(
            KfeTransactionEntity tx,
            String eventType,
            KfeTransactionStatus from,
            KfeTransactionStatus to,
            Map<String, ?> payload) {
        Map<String, Object> redacted = new LinkedHashMap<>();
        redacted.put("transactionId", tx.getId().toString());
        redacted.put("idempotencyHash", hashService.sha256(tx.getIdempotencyKey()));
        if (payload != null) {
            redacted.putAll(payload);
        }
        auditLogService.record(eventType, tx.getId(), tx.getSourceWalletId(), from, to, redacted);
    }

    private boolean canTransition(KfeTransactionStatus current, KfeTransactionStatus target) {
        if (current == target) {
            return true;
        }
        if (current == KfeTransactionStatus.FAILED
                || current == KfeTransactionStatus.CANCELLED
                || current == KfeTransactionStatus.DROPPED
                || current == KfeTransactionStatus.ABANDONED) {
            return false;
        }
        return switch (current) {
            case INTENT ->
                target == KfeTransactionStatus.VALIDATING
                || target == KfeTransactionStatus.FAILED;
            case VALIDATING ->
                target == KfeTransactionStatus.QUORUM_SYNC
                || target == KfeTransactionStatus.FAILED
                || target == KfeTransactionStatus.CANCELLED;
            case QUORUM_SYNC ->
                target == KfeTransactionStatus.LOCKED
                || target == KfeTransactionStatus.FAILED
                || target == KfeTransactionStatus.REQUIRES_RECONCILIATION;
            case LOCKED ->
                target == KfeTransactionStatus.EXECUTING
                || target == KfeTransactionStatus.SETTLED
                || target == KfeTransactionStatus.FAILED
                || target == KfeTransactionStatus.CANCELLED
                || target == KfeTransactionStatus.REQUIRES_RECONCILIATION;
            case EXECUTING ->
                target == KfeTransactionStatus.BROADCAST
                || target == KfeTransactionStatus.SETTLED
                || target == KfeTransactionStatus.FAILED
                || target == KfeTransactionStatus.CONFLICTED_RECONCILING
                || target == KfeTransactionStatus.REQUIRES_RECONCILIATION;
            case BROADCAST ->
                target == KfeTransactionStatus.CONFIRMING
                || target == KfeTransactionStatus.SETTLED
                || target == KfeTransactionStatus.FAILED
                || target == KfeTransactionStatus.CONFLICTED_RECONCILING
                || target == KfeTransactionStatus.REQUIRES_RECONCILIATION;
            case CONFIRMING ->
                target == KfeTransactionStatus.SETTLED
                || target == KfeTransactionStatus.FAILED
                || target == KfeTransactionStatus.CONFLICTED_RECONCILING
                || target == KfeTransactionStatus.REQUIRES_RECONCILIATION;
            case SETTLED ->
                target == KfeTransactionStatus.REORG_RECONCILIATION;
            case CONFLICTED_RECONCILING ->
                target == KfeTransactionStatus.FAILED
                || target == KfeTransactionStatus.BROADCAST
                || target == KfeTransactionStatus.REQUIRES_RECONCILIATION;
            case CONFLICTED_REFUNDED ->
                target == KfeTransactionStatus.REQUIRES_RECONCILIATION;
            case REORG_RECONCILIATION ->
                target == KfeTransactionStatus.SETTLED
                || target == KfeTransactionStatus.FAILED
                || target == KfeTransactionStatus.REQUIRES_RECONCILIATION;
            case REQUIRES_RECONCILIATION ->
                target == KfeTransactionStatus.EXECUTING
                || target == KfeTransactionStatus.SETTLED
                || target == KfeTransactionStatus.FAILED;
            default -> false;
        };
    }
}
