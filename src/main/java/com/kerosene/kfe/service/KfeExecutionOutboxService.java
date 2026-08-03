package com.kerosene.kfe.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.model.KfeExecutionOutboxEntity;
import com.kerosene.kfe.repository.KfeExecutionOutboxRepository;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class KfeExecutionOutboxService {

    private static final List<String> DUE_STATUSES = List.of("PENDING", "FAILED_RETRYABLE");
    private static final List<String> RECOVERABLE_OPERATIONS =
            List.of("ONCHAIN_OUTBOUND", "LIGHTNING_OUTBOUND");
    private final KfeExecutionOutboxRepository repository;
    private final Duration leaseDuration;

    @Autowired
    public KfeExecutionOutboxService(
            KfeExecutionOutboxRepository repository,
            @Value("${kfe.execution.outbox.lease-seconds:600}") long leaseSeconds) {
        this.repository = repository;
        if (leaseSeconds < 30L || leaseSeconds > 3600L) {
            throw new IllegalArgumentException("kfe.execution.outbox.lease-seconds must be between 30 and 3600.");
        }
        this.leaseDuration = Duration.ofSeconds(leaseSeconds);
    }

    KfeExecutionOutboxService(KfeExecutionOutboxRepository repository) {
        this(repository, 600L);
    }

    public record ExecutionClaim(UUID outboxId, UUID claimToken) {
    }

    @Transactional
    public List<ExecutionClaim> claimDue(String workerId) {
        String normalizedWorkerId = normalizeWorkerId(workerId);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        return repository.findTop100ClaimCandidates(DUE_STATUSES, RECOVERABLE_OPERATIONS, now)
                .stream()
                .limit(100)
                .map(candidate -> claim(candidate.getId(), normalizedWorkerId, now))
                .flatMap(Optional::stream)
                .toList();
    }

    private Optional<ExecutionClaim> claim(
            UUID outboxId,
            String workerId,
            LocalDateTime now) {
        UUID claimToken = UUID.randomUUID();
        int updated = repository.claimDue(
                outboxId,
                DUE_STATUSES,
                RECOVERABLE_OPERATIONS,
                now,
                workerId,
                claimToken,
                now.plus(leaseDuration));
        if (updated == 0) {
            return Optional.empty();
        }
        return Optional.of(new ExecutionClaim(outboxId, claimToken));
    }

    /** Claim one outbox item right after submit and return its fenced ownership token. */
    @Transactional
    public Optional<ExecutionClaim> claimImmediate(UUID outboxId, String workerId) {
        if (outboxId == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        UUID claimToken = UUID.randomUUID();
        int updated = repository.claimImmediate(
                outboxId,
                now,
                normalizeWorkerId(workerId),
                claimToken,
                now.plus(leaseDuration));
        return updated > 0
                ? Optional.of(new ExecutionClaim(outboxId, claimToken))
                : Optional.empty();
    }

    @Transactional
    public boolean heartbeat(ExecutionClaim claim) {
        if (claim == null || claim.outboxId() == null || claim.claimToken() == null) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return repository.heartbeat(
                claim.outboxId(),
                claim.claimToken(),
                now,
                now.plus(leaseDuration)) == 1;
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
