package com.kerosene.kfe.paymentexecution.application.port.out;

import com.kerosene.kfe.paymentexecution.domain.model.ExecutionClaim;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Short, atomic claim operations. The caller must not hold a transaction across provider I/O. */
public interface ExecutionClaimPort {
    List<ExecutionClaim> claimDue(String workerId);
    Optional<ExecutionClaim> claimImmediate(UUID outboxId, String workerId);
    /** Renews only current unexpired ownership, never resurrecting a stale lease. */
    boolean heartbeat(ExecutionClaim claim);
}
