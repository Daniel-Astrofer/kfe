package source.kfe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.common.audit.AuditEventPayloadSanitizer;
import source.common.audit.AuditEventType;
import source.common.audit.StructuredAuditLogger;
import source.kfe.model.KfeAuditLogEntity;
import source.kfe.model.KfeTransactionStatus;
import source.kfe.repository.KfeAuditLogRepository;

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

    @Transactional
    public KfeAuditLogEntity record(
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
