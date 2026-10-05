package com.kerosene.kfe.messaging.adapters.out.persistence;

import com.kerosene.kfe.bootstrap.adapters.out.observability.KfeFinancialMetrics;

import com.kerosene.kfe.messaging.envelope.MessageEnvelope;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import com.kerosene.kfe.adapters.out.persistence.model.messaging.KfeFinancialNotificationOutboxEntity;
import com.kerosene.kfe.adapters.out.persistence.repository.messaging.KfeFinancialNotificationOutboxRepository;

import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class KfeFinancialNotificationOutboxService {

    private static final List<String> DUE_STATUSES = List.of("PENDING", "FAILED_RETRYABLE");
    private static final Duration CLAIM_DURATION = Duration.ofMinutes(5);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record NotificationClaim(UUID outboxId, UUID claimToken) {
        public NotificationClaim {
            java.util.Objects.requireNonNull(outboxId, "outboxId is required");
            java.util.Objects.requireNonNull(claimToken, "claimToken is required");
        }
    }

    private final KfeFinancialNotificationOutboxRepository repository;

    @Autowired(required = false)
    private KfeFinancialMetrics metrics;

    public KfeFinancialNotificationOutboxService(KfeFinancialNotificationOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public KfeFinancialNotificationOutboxEntity enqueue(
            MessageEnvelope message, long userId, UUID transactionId) {
        java.util.Objects.requireNonNull(message, "message is required");
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        return repository.findByEventId(message.messageId()).orElseGet(() -> {
            KfeFinancialNotificationOutboxEntity entity = new KfeFinancialNotificationOutboxEntity();
            entity.setEventId(message.messageId());
            entity.setUserId(userId);
            entity.setTransactionId(transactionId);
            entity.setEventType(message.type());
            entity.setSchemaVersion(message.schemaVersion());
            entity.setCorrelationId(message.correlationId());
            entity.setCausationId(message.causationId());
            entity.setPayloadJson(message.payload());
            try {
                return repository.saveAndFlush(entity);
            } catch (org.springframework.dao.DataIntegrityViolationException duplicate) {
                return repository.findByEventId(message.messageId()).orElseThrow(() -> duplicate);
            }
        });
    }

    @Transactional
    public List<KfeFinancialNotificationOutboxEntity> claimDue(String workerId) {
        String normalizedWorkerId = normalizeWorkerId(workerId);
        Instant now = Instant.now();
        Instant claimedUntil = now.plus(CLAIM_DURATION);
        return repository.findClaimCandidatesFenced(DUE_STATUSES, now, org.springframework.data.domain.PageRequest.of(0, 100))
                .stream()
                .limit(100)
                .map(candidate -> claim(candidate.getId(), normalizedWorkerId, now, claimedUntil))
                .flatMap(Optional::stream)
                .toList();
    }

    /** Persist a notification exactly once by its event id. */
    @Transactional
    public KfeFinancialNotificationOutboxEntity enqueue(
            UUID eventId,
            Long userId,
            UUID transactionId,
            String eventType,
            Map<String, ?> payload) {
        if (eventId == null || userId == null || eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("Notification event id, user and type are required.");
        }
        Optional<KfeFinancialNotificationOutboxEntity> existing = repository.findByEventId(eventId);
        if (existing.isPresent()) {
            return existing.get();
        }
        KfeFinancialNotificationOutboxEntity entity = new KfeFinancialNotificationOutboxEntity();
        entity.setEventId(eventId);
        entity.setUserId(userId);
        entity.setTransactionId(transactionId);
        entity.setEventType(eventType.trim().toUpperCase(Locale.ROOT));
        entity.setPayloadJson(toJson(payload));
        entity.setStatus("PENDING");
        entity.setNextAttemptAt(Instant.now());
        try {
            KfeFinancialNotificationOutboxEntity saved = repository.saveAndFlush(entity);
            if (metrics != null) {
                metrics.recordNotificationEnqueued(entity.getEventType());
            }
            return saved;
        } catch (org.springframework.dao.DataIntegrityViolationException race) {
            return repository.findByEventId(eventId).orElseThrow(() -> race);
        }
    }

    public UUID stableEventId(String eventType, UUID transactionId, String discriminator) {
        String key = String.valueOf(eventType) + ":" + transactionId + ":" + discriminator;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private String toJson(Map<String, ?> payload) {
        try {
            return MAPPER.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Notification payload is not serializable.", exception);
        }
    }

    private Optional<KfeFinancialNotificationOutboxEntity> claim(
            UUID outboxId,
            String workerId,
            Instant now,
            Instant claimedUntil) {
        UUID token = UUID.randomUUID();
        int updated = repository.claimFenced(outboxId, DUE_STATUSES, now, workerId, token, claimedUntil);
        if (updated == 0) {
            return Optional.empty();
        }
        return repository.findById(outboxId).filter(entity -> token.equals(entity.getClaimToken()));
    }

    @Transactional
    public void markRetryableFailure(
            UUID outboxId,
            int attempts,
            String lastError) {
        Instant next = nextBackoff(attempts);
        repository.markRetryableFailure(outboxId, "FAILED_RETRYABLE", next, lastError);
    }

    @Transactional
    public boolean markRetryableFailure(NotificationClaim claim, int attempts, String ignoredError) {
        return repository.markRetryableFailureFenced(
                claim.outboxId(), claim.claimToken(), nextBackoff(attempts), safeFailure()) == 1;
    }

    @Transactional
    public void markDeadLetter(UUID outboxId, String lastError) {
        repository.markFinalFailure(outboxId, "DEAD_LETTER", lastError);
    }

    @Transactional
    public boolean markDeadLetter(NotificationClaim claim, String ignoredError) {
        return repository.markFinalFailureFenced(claim.outboxId(), claim.claimToken(), safeFailure()) == 1;
    }

    @Transactional
    public void markDelivered(UUID outboxId) {
        repository.markDelivered(outboxId, Instant.now());
    }

    @Transactional
    public boolean markDelivered(NotificationClaim claim) {
        return repository.markDeliveredFenced(claim.outboxId(), claim.claimToken(), Instant.now()) == 1;
    }

    private Instant nextBackoff(int attempts) {
        long delayMillis = (long) Math.pow(2, Math.min(attempts, 5)) * 1_000L;
        return Instant.now().plus(Duration.ofMillis(delayMillis));
    }

    private String normalizeWorkerId(String workerId) {
        String value = workerId == null ? "" : workerId.trim();
        if (value.isBlank()) {
            return "kfe-notification-worker";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.substring(0, Math.min(128, lower.length()));
    }

    private static String safeFailure() {
        return "Notification delivery failed; retry or quarantine required.";
    }
}
