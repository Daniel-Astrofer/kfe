package com.kerosene.kfe.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.kerosene.common.infra.logging.StructuredLogEvent;
import com.kerosene.kfe.domain.KfeAuditEvent;

import java.util.UUID;

/**
 * Emits lightweight structured JSON audit events to the "kerosene.audit.financial" log stream.
 *
 * <p>Complements {@code KfeAuditLogService} (chain-hashed DB audit). This logger records
 * only sanitized metadata — never bearer tokens, macaroons, full invoices, preimages,
 * PSBT, raw transactions, or PII.
 *
 * <p>Usage:
 * <pre>{@code
 * auditEventLogger.logStateTransition(txId, walletId, "LOCKED", "EXECUTING", amountSats, "ONCHAIN");
 * auditEventLogger.logSettlement(txId, walletId, amountSats, feeSats, "ONCHAIN");
 * auditEventLogger.logConflict(txId, walletId, "replacementTxidHash", "CONFLICTED_DOUBLE_SPEND", "ONCHAIN");
 * }</pre>
 */
@Component
public class KfeAuditEventLogger {

    private static final Logger log = LoggerFactory.getLogger("kerosene.audit.financial");

    // --- State machine transitions ---

    public void logStateTransition(
            String eventType,
            UUID transactionId,
            UUID walletId,
            String previousStatus,
            String newStatus,
            long amountSats,
            String rail) {
        KfeAuditEvent event = KfeAuditEvent.builder()
                .eventType(eventType)
                .transactionId(transactionId)
                .walletId(walletId)
                .previousStatus(previousStatus)
                .newStatus(newStatus)
                .amountSats(amountSats)
                .rail(rail)
                .build();
        emit(event);
    }

    // --- Settlement operations ---

    public void logSettlement(
            String eventType,
            UUID transactionId,
            UUID walletId,
            long amountSats,
            long feeSats,
            String network,
            String rail,
            String referenceHash) {
        KfeAuditEvent event = KfeAuditEvent.builder()
                .eventType(eventType)
                .transactionId(transactionId)
                .walletId(walletId)
                .amountSats(amountSats)
                .feeSats(feeSats)
                .network(network)
                .rail(rail)
                .referenceHash(referenceHash)
                .build();
        emit(event);
    }

    // --- Conflict / reorg events ---

    public void logConflict(
            String eventType,
            UUID transactionId,
            UUID walletId,
            String referenceHash,
            String reason,
            String rail) {
        KfeAuditEvent event = KfeAuditEvent.builder()
                .eventType(eventType)
                .transactionId(transactionId)
                .walletId(walletId)
                .referenceHash(referenceHash)
                .rail(rail)
                .previousStatus(reason)
                .build();
        emit(event);
    }

    public void logReorg(
            UUID transactionId,
            UUID walletId,
            int previousConfirmations,
            int currentConfirmations,
            String rail) {
        KfeAuditEvent event = KfeAuditEvent.builder()
                .eventType("KFE_REORG_DETECTED")
                .transactionId(transactionId)
                .walletId(walletId)
                .previousStatus(String.valueOf(previousConfirmations))
                .newStatus(String.valueOf(currentConfirmations))
                .rail(rail)
                .build();
        emit(event);
    }

    // --- Reconciliation operations ---

    public void logReconciliation(
            String eventType,
            UUID transactionId,
            UUID walletId,
            String reason,
            String rail) {
        KfeAuditEvent event = KfeAuditEvent.builder()
                .eventType(eventType)
                .transactionId(transactionId)
                .walletId(walletId)
                .previousStatus(reason)
                .rail(rail)
                .build();
        emit(event);
    }

    // --- Generic ---

    public void log(KfeAuditEvent event) {
        emit(event);
    }

    private void emit(KfeAuditEvent event) {
        StructuredLogEvent structured = StructuredLogEvent.of(
                        event.eventType(),
                        "financial-audit",
                        "audit",
                        "Financial audit event")
                .field("audit.eventId", event.eventId())
                .field("audit.eventType", event.eventType())
                .field("audit.transactionId", event.transactionId())
                .field("audit.walletId", event.walletId());

        if (event.principalId() != null) {
            structured.field("audit.principalId", event.principalId());
        }
        if (event.previousStatus() != null) {
            structured.field("audit.previousStatus", event.previousStatus());
        }
        if (event.newStatus() != null) {
            structured.field("audit.newStatus", event.newStatus());
        }
        if (event.amountSats() > 0L) {
            structured.field("audit.amountSats", event.amountSats());
        }
        if (event.feeSats() > 0L) {
            structured.field("audit.feeSats", event.feeSats());
        }
        if (event.network() != null) {
            structured.field("audit.network", event.network());
        }
        if (event.rail() != null) {
            structured.field("audit.rail", event.rail());
        }
        if (event.referenceHash() != null) {
            structured.field("audit.referenceHash", event.referenceHash());
        }
        if (event.requestId() != null) {
            structured.field("audit.requestId", event.requestId());
        }
        if (event.correlationId() != null) {
            structured.field("audit.correlationId", event.correlationId());
        }
        structured.field("audit.occurredAt", event.occurredAt().toString());

        log.info("audit.event", structured.arguments());
    }
}
