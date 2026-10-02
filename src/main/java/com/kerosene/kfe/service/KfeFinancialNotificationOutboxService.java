package com.kerosene.kfe.service;

import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.model.KfeFinancialNotificationOutboxEntity;
import com.kerosene.kfe.repository.KfeFinancialNotificationOutboxRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class KfeFinancialNotificationOutboxService {

    private KfeMaintenanceGuard maintenanceGuard = KfeMaintenanceGuard.unavailable();

    @Autowired
    public void setMaintenanceGuard(KfeMaintenanceGuard guard) {
        this.maintenanceGuard = Objects.requireNonNull(guard);
    }

    private static final List<String> DUE_STATUSES = List.of("PENDING", "FAILED_RETRYABLE");
    private static final Duration CLAIM_DURATION = Duration.ofMinutes(5);

    private final KfeFinancialNotificationOutboxRepository repository;

    public KfeFinancialNotificationOutboxService(KfeFinancialNotificationOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<KfeFinancialNotificationOutboxEntity> claimDue(String workerId) {
        // A nonempty standalone claim leaves delivery outstanding. The worker holds
        // the same admitted workflow across this claim and synchronous processing.
        return maintenanceGuard.executeMutation("notification.claim-due",
                () -> claimDueAdmitted(workerId), ClaimBatch::noClaims).items();
    }

    private ClaimBatch claimDueAdmitted(String workerId) {
        String normalizedWorkerId = normalizeWorkerId(workerId);
        Instant now = Instant.now();
        Instant claimedUntil = now.plus(CLAIM_DURATION);
        AtomicBoolean claimed = new AtomicBoolean();
        List<KfeFinancialNotificationOutboxEntity> items = repository.findTop100ClaimCandidates(DUE_STATUSES, now)
                .stream()
                .limit(100)
                .map(candidate -> claim(candidate.getId(), normalizedWorkerId, now, claimedUntil, claimed))
                .flatMap(Optional::stream)
                .toList();
        return new ClaimBatch(items, !claimed.get());
    }

    private record ClaimBatch(List<KfeFinancialNotificationOutboxEntity> items, boolean noClaims) { }

    private Optional<KfeFinancialNotificationOutboxEntity> claim(
            UUID outboxId,
            String workerId,
            Instant now,
            Instant claimedUntil,
            AtomicBoolean claimed) {
        int updated = repository.claimDue(outboxId, DUE_STATUSES, now, workerId, claimedUntil);
        if (updated == 0) {
            return Optional.empty();
        }
        // Even a missing reload after a successful claim leaves unresolved work.
        claimed.set(true);
        return repository.findById(outboxId);
    }

    @Transactional
    public void markRetryableFailure(
            UUID outboxId,
            int attempts,
            String lastError) {
        maintenanceGuard.executeMutation("notification.retryable-failure", () -> {
            Instant next = nextBackoff(attempts);
            return repository.markRetryableFailure(outboxId, "FAILED_RETRYABLE", next, lastError);
        }, ignored -> false);
    }

    @Transactional
    public void markDeadLetter(UUID outboxId, String lastError) {
        maintenanceGuard.executeMutation("notification.dead-letter",
                () -> repository.markFinalFailure(outboxId, "DEAD_LETTER", lastError), ignored -> false);
    }

    @Transactional
    public void markDelivered(UUID outboxId) {
        // The existing DELIVERED label is not evidence that the best-effort port
        // reached the recipient; preserve it without certifying maintenance safety.
        maintenanceGuard.executeMutation("notification.mark-delivered",
                () -> repository.markDelivered(outboxId, Instant.now()), ignored -> false);
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
