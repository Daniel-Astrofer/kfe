package com.kerosene.kfe.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.model.KfeFinancialNotificationOutboxEntity;
import com.kerosene.kfe.repository.KfeFinancialNotificationOutboxRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class KfeFinancialNotificationOutboxService {

    private static final List<String> DUE_STATUSES = List.of("PENDING", "FAILED_RETRYABLE");
    private static final Duration CLAIM_DURATION = Duration.ofMinutes(5);

    private final KfeFinancialNotificationOutboxRepository repository;

    public KfeFinancialNotificationOutboxService(KfeFinancialNotificationOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<KfeFinancialNotificationOutboxEntity> claimDue(String workerId) {
        String normalizedWorkerId = normalizeWorkerId(workerId);
        Instant now = Instant.now();
        Instant claimedUntil = now.plus(CLAIM_DURATION);
        return repository.findTop100ClaimCandidates(DUE_STATUSES, now)
                .stream()
                .limit(100)
                .map(candidate -> claim(candidate.getId(), normalizedWorkerId, now, claimedUntil))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<KfeFinancialNotificationOutboxEntity> claim(
            UUID outboxId,
            String workerId,
            Instant now,
            Instant claimedUntil) {
        int updated = repository.claimDue(outboxId, DUE_STATUSES, now, workerId, claimedUntil);
        if (updated == 0) {
            return Optional.empty();
        }
        return repository.findById(outboxId);
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
    public void markDeadLetter(UUID outboxId, String lastError) {
        repository.markFinalFailure(outboxId, "DEAD_LETTER", lastError);
    }

    @Transactional
    public void markDelivered(UUID outboxId) {
        repository.markDelivered(outboxId, Instant.now());
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
}
