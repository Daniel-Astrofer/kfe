package com.kerosene.kfe.messaging.adapters.out.notification;

import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;
import com.kerosene.kfe.messaging.adapters.out.persistence.KfeFinancialNotificationOutboxService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.kerosene.common.financial.notification.FinancialNotificationPort;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.KfeFinancialNotificationOutboxEntity;

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

    public void processDeliverable(KfeFinancialNotificationOutboxEntity entity) {
        UUID outboxId = entity.getId();
        String eventType = entity.getEventType();
        int attempts = entity.getAttempts();
        KfeFinancialNotificationOutboxService.NotificationClaim claim = entity.getClaimToken() == null
                ? null
                : new KfeFinancialNotificationOutboxService.NotificationClaim(outboxId, entity.getClaimToken());

        try {
            Deliverable d = Deliverable.fromJson(entity);
            deliver(d);
            if (claim == null) {
                outboxService.markDelivered(outboxId);
            } else {
                outboxService.markDelivered(claim);
            }
            financialMetrics.recordNotificationDelivered(eventType);
            log.debug("[KFE Notif Outbox] delivered eventType={} eventId={}", eventType, entity.getEventId());
        } catch (RuntimeException exception) {
            log.warn("[KFE Notif Outbox] delivery failed eventType={} attempts={}", eventType, attempts);
            if (attempts >= MAX_RETRIES) {
                if (claim == null) {
                    outboxService.markDeadLetter(outboxId, safeMessage(exception));
                } else {
                    outboxService.markDeadLetter(claim, safeMessage(exception));
                }
                financialMetrics.recordNotificationDeadLetter(eventType);
            } else {
                if (claim == null) {
                    outboxService.markRetryableFailure(outboxId, attempts + 1, safeMessage(exception));
                } else {
                    outboxService.markRetryableFailure(claim, attempts + 1, safeMessage(exception));
                }
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
            case "PAYMENT_REQUEST_DEPOSIT_CONFIRMED":
                notificationPort.notifyPaymentRequestDepositConfirmed(
                        d.userId, d.transactionId, d.paymentRequestId, d.publicId,
                        d.walletId, d.rail, d.amountSats);
                break;
            case "PAYMENT_PROCESSING":
                notificationPort.notifyPaymentInitiated(
                        d.userId, d.transactionId, d.walletId, d.rail, d.amountSats);
                break;
            case "INTERNAL_TRANSFER_SENT":
                notificationPort.notifyInternalTransferSent(
                        d.userId, d.transactionId, d.walletId, d.amountSats);
                break;
            case "INTERNAL_TRANSFER_RECEIVED":
                notificationPort.notifyInternalTransferReceived(
                        d.userId, d.transactionId, d.walletId, d.amountSats);
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
                throw new IllegalArgumentException("Unsupported notification event type.");
        }
    }

    private String safeMessage(Throwable exception) {
        return "Notification delivery failed; retry or quarantine required.";
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
        final UUID paymentRequestId;
        final String publicId;

        Deliverable(String eventType, Long userId, UUID transactionId, UUID walletId,
                   String rail, long amountSats, int confirmations,
                   String txid, String failureCode, String failureMessage,
                   UUID paymentRequestId, String publicId) {
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
            this.paymentRequestId = paymentRequestId;
            this.publicId = publicId;
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
            UUID paymentRequestId = null;
            String publicId = null;

            String json = entity.getPayloadJson();
            if (json == null || json.isBlank()) {
                throw new IllegalArgumentException("Notification payload is missing.");
            }
            if (json != null && !json.isBlank()) {
                try {
                    JsonNode node = MAPPER.readTree(json);
                    if (node == null || !node.isObject()) {
                        throw new IllegalArgumentException("Notification payload is invalid.");
                    }
                    walletId = uuidOrNull(node, "walletId");
                    if (node.has("rail") && !node.get("rail").isNull()) {
                        rail = node.get("rail").asText();
                    }
                    amountSats = node.has("amountSats") ? node.get("amountSats").asLong() : 0L;
                    confirmations = node.has("confirmations") ? node.get("confirmations").asInt() : 0;
                    txid = textOrNull(node, "txid");
                    failureCode = textOrNull(node, "failureCode");
                    paymentRequestId = uuidOrNull(node, "paymentRequestId");
                    publicId = textOrNull(node, "publicId");
                    if (node.has("failureMessage") && !node.get("failureMessage").isNull()) {
                        failureMessage = node.get("failureMessage").asText();
                    }
                } catch (Exception e) {
                    throw new IllegalArgumentException("Notification payload is invalid.", e);
                }
            }

            return new Deliverable(eventType, userId, transactionId, walletId,
                    rail, amountSats, confirmations, txid, failureCode, failureMessage,
                    paymentRequestId, publicId);
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
