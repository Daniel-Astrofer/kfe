package com.kerosene.kfe.service;

import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "kfe.execution.enabled", havingValue = "true", matchIfMissing = true)
public class KfeExecutionOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(KfeExecutionOutboxWorker.class);
    private final String workerId = "kfe-execution-worker-" + UUID.randomUUID();

    private final KfeExecutionOutboxService outboxService;
    private final KfeExecutionOutboxProcessor processor;
    private KfeMaintenanceGuard maintenanceGuard = KfeMaintenanceGuard.unavailable();

    public KfeExecutionOutboxWorker(
            KfeExecutionOutboxService outboxService,
            KfeExecutionOutboxProcessor processor) {
        this.outboxService = outboxService;
        this.processor = processor;
    }

    @Autowired
    public void setMaintenanceGuard(KfeMaintenanceGuard maintenanceGuard) {
        this.maintenanceGuard = Objects.requireNonNull(maintenanceGuard);
    }

    @Scheduled(
            fixedDelayString = "${kfe.execution.outbox.fixed-delay-ms:5000}",
            initialDelayString = "${kfe.execution.outbox.initial-delay-ms:10000}")
    public void drain() {
        try {
            maintenanceGuard.executeMutation("outbox.worker-batch", this::drainAdmitted,
                    emptyBatch -> emptyBatch);
        } catch (KfeMaintenanceGuard.MaintenanceException rejection) {
            log.debug("[KFE Outbox] worker paused: {}", rejection.getMessage());
        }
    }

    private boolean drainAdmitted() {
        List<KfeExecutionOutboxService.ExecutionClaim> claimed = outboxService.claimDue(workerId);
        if (claimed.isEmpty()) {
            return true;
        }
        log.info("[KFE Outbox] claimed {} item(s) workerId={}", claimed.size(), workerId);
        for (KfeExecutionOutboxService.ExecutionClaim claim : claimed) {
            try {
                processor.process(claim);
            } catch (RuntimeException exception) {
                log.warn("[KFE Outbox] Processing failed for {}: {}", claim.outboxId(), exception.getMessage());
            }
        }
        // A lease or provider return cannot prove durable execution/callback completion.
        return false;
    }
}
