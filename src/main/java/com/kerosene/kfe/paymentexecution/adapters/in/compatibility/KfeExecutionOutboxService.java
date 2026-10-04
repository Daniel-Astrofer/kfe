package com.kerosene.kfe.paymentexecution.adapters.in.compatibility;

import com.kerosene.kfe.paymentexecution.application.port.out.ExecutionClaimPort;
import com.kerosene.kfe.paymentexecution.adapters.out.persistence.JpaExecutionClaimAdapter;
import com.kerosene.kfe.adapters.out.persistence.repository.paymentexecution.KfeExecutionOutboxRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Compatibility facade for the legacy scheduler/dispatcher; claim persistence belongs to the port. */
@Service
public class KfeExecutionOutboxService {
    private final ExecutionClaimPort claims;

    @Autowired
    public KfeExecutionOutboxService(ExecutionClaimPort claims) {
        this.claims = Objects.requireNonNull(claims);
    }

    KfeExecutionOutboxService(KfeExecutionOutboxRepository repository) {
        this(new JpaExecutionClaimAdapter(repository, 600L));
    }

    public record ExecutionClaim(UUID outboxId, UUID claimToken) { }

    public List<ExecutionClaim> claimDue(String workerId) {
        return claims.claimDue(workerId).stream().map(KfeExecutionOutboxService::legacy).toList();
    }

    public Optional<ExecutionClaim> claimImmediate(UUID outboxId, String workerId) {
        return claims.claimImmediate(outboxId, workerId).map(KfeExecutionOutboxService::legacy);
    }

    public boolean heartbeat(ExecutionClaim claim) {
        if (claim == null || claim.outboxId() == null || claim.claimToken() == null) { return false; }
        return claims.heartbeat(new com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim(claim.outboxId(), claim.claimToken()));
    }

    private static ExecutionClaim legacy(com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim claim) {
        return new ExecutionClaim(claim.outboxId(), claim.claimToken());
    }
}
