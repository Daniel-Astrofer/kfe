package source.kfe.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.model.KfeExecutionOutboxEntity;
import source.kfe.repository.KfeExecutionOutboxRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class KfeExecutionOutboxService {

    private static final List<String> DUE_STATUSES = List.of("PENDING", "FAILED_RETRYABLE");
    private static final Duration STALE_CLAIM_AFTER = Duration.ofMinutes(10);

    private final KfeExecutionOutboxRepository repository;

    public KfeExecutionOutboxService(KfeExecutionOutboxRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public List<KfeExecutionOutboxEntity> claimDue(String workerId) {
        String normalizedWorkerId = normalizeWorkerId(workerId);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleClaimBefore = now.minus(STALE_CLAIM_AFTER);
        return repository.findTop100ClaimCandidates(DUE_STATUSES, now, staleClaimBefore)
                .stream()
                .limit(100)
                .map(candidate -> claim(candidate.getId(), normalizedWorkerId, now, staleClaimBefore))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<KfeExecutionOutboxEntity> claim(
            UUID outboxId,
            String workerId,
            LocalDateTime now,
            LocalDateTime staleClaimBefore) {
        int updated = repository.claimDue(outboxId, DUE_STATUSES, now, staleClaimBefore, workerId);
        if (updated == 0) {
            return Optional.empty();
        }
        return repository.findById(outboxId);
    }

    private String normalizeWorkerId(String workerId) {
        String value = workerId == null ? "" : workerId.trim();
        if (value.isBlank()) {
            return "kfe-execution-worker";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.substring(0, Math.min(128, lower.length()));
    }
}
