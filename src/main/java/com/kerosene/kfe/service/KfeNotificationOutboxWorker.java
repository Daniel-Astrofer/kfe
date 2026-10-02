package com.kerosene.kfe.service;

import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.kerosene.kfe.model.KfeFinancialNotificationOutboxEntity;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "kfe.notification.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class KfeNotificationOutboxWorker {

    private KfeMaintenanceGuard maintenanceGuard = KfeMaintenanceGuard.unavailable();

    @Autowired
    public void setMaintenanceGuard(KfeMaintenanceGuard guard) {
        this.maintenanceGuard = Objects.requireNonNull(guard);
    }

    private static final Logger log = LoggerFactory.getLogger(KfeNotificationOutboxWorker.class);
    private final String workerId = "kfe-notification-worker-" + UUID.randomUUID();

    private final KfeFinancialNotificationOutboxService outboxService;
    private final KfeNotificationOutboxProcessor processor;

    public KfeNotificationOutboxWorker(
            KfeFinancialNotificationOutboxService outboxService,
            KfeNotificationOutboxProcessor processor) {
        this.outboxService = outboxService;
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString = "${kfe.notification.outbox.fixed-delay-ms:5000}",
            initialDelayString = "${kfe.notification.outbox.initial-delay-ms:10000}")
    public void drain() {
        maintenanceGuard.executeMutation("notification.worker", this::drainAdmitted, Boolean.TRUE::equals);
    }

    private boolean drainAdmitted() {
        List<KfeFinancialNotificationOutboxEntity> claimed = outboxService.claimDue(workerId);
        if (claimed.isEmpty()) {
            return true;
        }
        log.debug("[KFE Notif Outbox] claimed {} item(s) workerId={}", claimed.size(), workerId);
        for (KfeFinancialNotificationOutboxEntity item : claimed) {
            try {
                processor.process(item);
            } catch (RuntimeException exception) {
                log.warn("[KFE Notif Outbox] Processing failed for {}: {}", item.getId(), exception.getMessage());
            }
        }
        // Processing catches failures, and the remote port also swallows failures.
        // Completing the local batch cannot prove recipient delivery.
        return false;
    }
}
