package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import source.kfe.model.KfeExecutionOutboxEntity;

import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "kfe.execution.enabled", havingValue = "true", matchIfMissing = true)
public class KfeExecutionOutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(KfeExecutionOutboxWorker.class);
    private final String workerId = "kfe-execution-worker-" + UUID.randomUUID();

    private final KfeExecutionOutboxService outboxService;
    private final KfeExecutionOutboxProcessor processor;

    public KfeExecutionOutboxWorker(
            KfeExecutionOutboxService outboxService,
            KfeExecutionOutboxProcessor processor) {
        this.outboxService = outboxService;
        this.processor = processor;
    }

    @Scheduled(
            fixedDelayString = "${kfe.execution.outbox.fixed-delay-ms:5000}",
            initialDelayString = "${kfe.execution.outbox.initial-delay-ms:10000}")
    public void drain() {
        List<KfeExecutionOutboxEntity> claimed = outboxService.claimDue(workerId);
        for (KfeExecutionOutboxEntity item : claimed) {
            try {
                processor.process(item.getId());
            } catch (RuntimeException exception) {
                log.warn("[KFE Outbox] Processing failed for {}: {}", item.getId(), exception.getMessage());
            }
        }
    }
}
