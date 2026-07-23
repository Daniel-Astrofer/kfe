package com.kerosene.kfe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.common.audit.AuditEventPayloadSanitizer;
import com.kerosene.common.audit.AuditEventType;
import com.kerosene.common.audit.StructuredAuditLogger;
import com.kerosene.kfe.model.KfeAuditLogEntity;
import com.kerosene.kfe.model.KfeTransactionStatus;
import com.kerosene.kfe.repository.KfeAuditLogRepository;

import java.util.Map;
import java.util.UUID;

@Service
public class KfeAuditLogService {

    private static final String GENESIS_HASH = "0".repeat(64);

    private final KfeAuditLogRepository repository;
    private final KfeHashService hashService;
    private final ObjectMapper objectMapper;
    private final StructuredAuditLogger auditLogger;

    public KfeAuditLogService(
            KfeAuditLogRepository repository,
            KfeHashService hashService,
            ObjectMapper objectMapper,
            StructuredAuditLogger auditLogger) {
        this.repository = repository;
        this.hashService = hashService;
        this.objectMapper = objectMapper;
        this.auditLogger = auditLogger;
    }

    /**
     * Append-only audit event in the caller's transaction.
     *
     * <p>Must join the outer submit TX when {@code transactionId} points at a row that is not
     * committed yet — {@link Propagation#REQUIRES_NEW} would violate
     * {@code financial_audit_log_transaction_id_fkey} and surface as a generic client error
     * ("não conseguimos concluir essa solicitação").
     *
     * <p>The global audit appender lock is xact-scoped; callers must not call
     * {@link #recordInNewTransaction} while this lock is held (see settlement-gate path).
     */
    @Transactional
    public KfeAuditLogEntity record(
            String eventType,
            UUID transactionId,
            UUID walletId,
            KfeTransactionStatus fromStatus,
            KfeTransactionStatus toStatus,
            Map<String, ?> redactedPayload) {
        return persist(eventType, transactionId, walletId, fromStatus, toStatus, redactedPayload);
    }

    /**
     * Forensic audit in a <strong>new</strong> transaction (survives outer rollback).
     *
     * <p>Only safe when the outer transaction does <em>not</em> already hold
     * {@code GLOBAL_AUDIT_APPENDER}. Prefer {@link #record} from inside submit, or schedule this
     * after the outer TX has released its locks.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public KfeAuditLogEntity recordInNewTransaction(
            String eventType,
            UUID transactionId,
            UUID walletId,
            KfeTransactionStatus fromStatus,
            KfeTransactionStatus toStatus,
            Map<String, ?> redactedPayload) {
        return persist(eventType, transactionId, walletId, fromStatus, toStatus, redactedPayload);
    }

    private KfeAuditLogEntity persist(
            String eventType,
            UUID transactionId,
            UUID walletId,
            KfeTransactionStatus fromStatus,
            KfeTransactionStatus toStatus,
            Map<String, ?> redactedPayload) {
        AuditEventType auditEventType = AuditEventType.requireKnown(eventType);
        Map<String, Object> sanitizedPayload = AuditEventPayloadSanitizer.sanitize(redactedPayload);
        String payloadHash = hashService.sha256(toJson(sanitizedPayload));
        repository.lockAuditAppender();
        String previousHash = repository.findTopByOrderBySequenceNumberDesc()
                .map(KfeAuditLogEntity::getEventHash)
                .orElse(GENESIS_HASH);

        KfeAuditLogEntity event = new KfeAuditLogEntity();
        event.setEventType(auditEventType.name());
        event.setTransactionId(transactionId);
        event.setWalletId(walletId);
        event.setFromStatus(fromStatus != null ? fromStatus.name() : null);
        event.setToStatus(toStatus != null ? toStatus.name() : null);
        event.setPayloadHash(payloadHash);
        event.setPreviousHash(previousHash);
        event.setEventHash(hashService.sha256(previousHash + "|" + payloadHash + "|" + auditEventType.name()
                + "|" + transactionId + "|" + walletId + "|" + toStatus));
        KfeAuditLogEntity saved = repository.save(event);
        auditLogger.persisted(
                auditEventType,
                saved.getSequenceNumber(),
                saved.getId(),
                saved.getTransactionId(),
                saved.getWalletId(),
                saved.getFromStatus(),
                saved.getToStatus(),
                saved.getPayloadHash(),
                saved.getEventHash(),
                sanitizedPayload);
        return saved;
    }

    private String toJson(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
        } catch (Exception exception) {
            return "{}";
        }
    }

}
