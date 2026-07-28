package com.kerosene.kfe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.common.financial.FinancialNotificationPort;
import com.kerosene.kfe.model.KfeFinancialNotificationOutboxEntity;

import java.util.UUID;

@Service
public class KfeNotificationOutboxProcessor {

    private static final Logger log = LoggerFactory.getLogger(KfeNotificationOutboxProcessor.class);
    private static final int MAX_RETRIES = 5;
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private final KfeFinancialNotificationOutboxService outboxService;
    private final FinancialNotificationPort notificationPort;
    private final KfeFinancialMetrics financialMetrics;

    public KfeNotificationOutboxProcessor(
            KfeFinancialNotificationOutboxService outboxService,
            FinancialNotificationPort notificationPort,
            KfeFinancialMetrics financialMetrics) {
        this.outboxService = outboxService;
        this.notificationPort = notificationPort;
        this.financialMetrics = financialMetrics;
    }

    @Transactional
    public void processDeliverable(KfeFinancialNotificationOutboxEntity entity) {
        UUID outboxId = entity.getId();
        String eventType = entity.getEventType();
        int attempts = entity.getAttempts();

        try {
            Deliverable d = Deliverable.fromJson(entity);
            deliver(d);
            outboxService.markDelivered(outboxId);
            log.debug("[KFE Notif Outbox] delivered eventType={} eventId={}", eventType, entity.getEventId());
        } catch (RuntimeException exception) {
            log.warn("[KFE Notif Outbox] delivery failed eventType={} attempts={} msg={}",
                    eventType, attempts, exception.getMessage());
            if (attempts >= MAX_RETRIES) {
                outboxService.markDeadLetter(outboxId, safeMessage(exception));
                financialMetrics.recordNotificationDeadLetter(eventType);
            } else {
                outboxService.markRetryableFailure(outboxId, attempts + 1, safeMessage(exception));
            }
        }
    }

    /**
     * Primary entry point from worker.
     */
    public void process(KfeFinancialNotificationOutboxEntity entity) {
        processDeliverable(entity);
    }

    private void deliver(Deliverable d) {
        switch (d.eventType) {
            case "DEPOSIT_DETECTED":
                notificationPort.notifyDepositDetected(
                        d.userId, d.transactionId, d.walletId, d.rail, d.amountSats, d.confirmations);
                break;
            case "DEPOSIT_CONFIRMATION_PROGRESS":
                notificationPort.notifyDepositConfirmationProgress(
                        d.userId, d.transactionId, d.walletId, d.rail, d.amountSats, d.confirmations);
                break;
            case "DEPOSIT_CONFIRMED":
            case "DEPOSIT_FINALIZED":
                notificationPort.notifyDepositConfirmed(
                        d.userId, d.transactionId, d.walletId, d.rail, d.amountSats, d.confirmations);
                break;
            case "PAYMENT_PROCESSING":
                notificationPort.notifyPaymentInitiated(
                        d.userId, d.transactionId, d.walletId, d.rail, d.amountSats);
                break;
            case "PAYMENT_BROADCAST":
                notificationPort.notifyPaymentBroadcast(
                        d.userId, d.transactionId, d.walletId, d.rail, d.amountSats, d.txid);
                break;
            case "PAYMENT_CONFIRMED":
                notificationPort.notifyPaymentConfirmed(
                        d.userId, d.transactionId, d.walletId, d.rail, d.amountSats, d.confirmations);
                break;
            case "PAYMENT_FAILED":
                notificationPort.notifyPaymentFailed(
                        d.userId, d.transactionId, d.walletId, d.rail, d.amountSats,
                        d.failureCode, d.failureMessage);
                break;
            case "PAYMENT_RECONCILIATION_REQUIRED":
                notificationPort.notifyPaymentReconciliationRequired(
                        d.userId, d.transactionId, d.walletId, d.rail, d.amountSats, d.failureMessage);
                break;
            default:
                log.debug("[KFE Notif Outbox] unhandled eventType={} — no dispatch mapped", d.eventType);
        }
    }

    private String safeMessage(Throwable exception) {
        return exception.getMessage() != null && !exception.getMessage().isBlank()
                ? exception.getMessage()
                : "Notification delivery failed.";
    }

    private static final class Deliverable {
        final String eventType;
        final Long userId;
        final UUID transactionId;
        final UUID walletId;
        final String rail;
        final long amountSats;
        final int confirmations;
        final String txid;
        final String failureCode;
        final String failureMessage;

        Deliverable(String eventType, Long userId, UUID transactionId, UUID walletId,
                   String rail, long amountSats, int confirmations,
                   String txid, String failureCode, String failureMessage) {
            this.eventType = eventType;
            this.userId = userId;
            this.transactionId = transactionId;
            this.walletId = walletId;
            this.rail = rail;
            this.amountSats = amountSats;
            this.confirmations = confirmations;
            this.txid = txid;
            this.failureCode = failureCode;
            this.failureMessage = failureMessage;
        }

        static Deliverable fromJson(KfeFinancialNotificationOutboxEntity entity) {
            String eventType = entity.getEventType();
            Long userId = entity.getUserId();
            UUID transactionId = entity.getTransactionId();
            UUID walletId = null;
            String rail = "ONCHAIN";
            long amountSats = 0L;
            int confirmations = 0;
            String txid = null;
            String failureCode = null;
            String failureMessage = entity.getLastError();

            String json = entity.getPayloadJson();
            if (json != null && !json.isBlank()) {
                try {
                    JsonNode node = MAPPER.readTree(json);
                    walletId = uuidOrNull(node, "walletId");
                    if (node.has("rail") && !node.get("rail").isNull()) {
                        rail = node.get("rail").asText();
                    }
                    amountSats = node.has("amountSats") ? node.get("amountSats").asLong() : 0L;
                    confirmations = node.has("confirmations") ? node.get("confirmations").asInt() : 0;
                    txid = textOrNull(node, "txid");
                    failureCode = textOrNull(node, "failureCode");
                    if (node.has("failureMessage") && !node.get("failureMessage").isNull()) {
                        failureMessage = node.get("failureMessage").asText();
                    }
                } catch (Exception e) {
                    log.warn("[KFE Notif Outbox] failed to parse payloadJson eventId={}: {}",
                            entity.getEventId(), e.getMessage());
                }
            }

            return new Deliverable(eventType, userId, transactionId, walletId,
                    rail, amountSats, confirmations, txid, failureCode, failureMessage);
        }

        private static UUID uuidOrNull(JsonNode node, String field) {
            if (node.has(field) && !node.get(field).isNull()) {
                try {
                    return UUID.fromString(node.get(field).asText());
                } catch (Exception e) {
                    return null;
                }
            }
            return null;
        }

        private static String textOrNull(JsonNode node, String field) {
            if (node.has(field) && !node.get(field).isNull()) {
                return node.get(field).asText();
            }
            return null;
        }
    }
}
