package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import source.kfe.dto.KfeChannelDecisionResponse;
import source.kfe.dto.KfeCloseChannelRequest;
import source.kfe.dto.KfeOpenChannelRequest;
import source.kfe.model.KfeChannelCapacityIntent;
import source.kfe.model.KfeChannelCapacityJobEntity;
import source.kfe.rail.LightningChannelGateway;

import java.util.List;
import java.util.UUID;

/**
 * Executes durable capacity intents after re-checking binary AND gates.
 * Never runs on the user request thread.
 */
@Service
public class KfeChannelCapacityWorker {

    private static final Logger log = LoggerFactory.getLogger(KfeChannelCapacityWorker.class);

    private final KfeChannelCapacityQueueService queueService;
    private final KfeChannelLifecycleService lifecycleService;
    private final LightningChannelGateway channelGateway;
    private final ObjectProvider<KfeLightningOpsMetrics> opsMetrics;
    private final boolean enabled;
    private final int batchSize;
    private final long assumedFeeRateSatVb;

    public KfeChannelCapacityWorker(
            KfeChannelCapacityQueueService queueService,
            KfeChannelLifecycleService lifecycleService,
            LightningChannelGateway channelGateway,
            ObjectProvider<KfeLightningOpsMetrics> opsMetrics,
            @Value("${kfe.channel.capacity.worker.enabled:true}") boolean enabled,
            @Value("${kfe.channel.capacity.worker.batch-size:3}") int batchSize,
            @Value("${kfe.channel.capacity.assumed-fee-rate-sat-vb:10}") long assumedFeeRateSatVb) {
        this.queueService = queueService;
        this.lifecycleService = lifecycleService;
        this.channelGateway = channelGateway;
        this.opsMetrics = opsMetrics;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.assumedFeeRateSatVb = Math.max(1L, assumedFeeRateSatVb);
    }

    @Scheduled(
            fixedDelayString = "${kfe.channel.capacity.worker.fixed-delay-ms:120000}",
            initialDelayString = "${kfe.channel.capacity.worker.initial-delay-ms:120000}")
    public void processPending() {
        if (!enabled) {
            return;
        }
        processBatch(batchSize);
    }

    public int processBatch(int limit) {
        if (!channelGateway.isLive()) {
            log.debug("[KFE Capacity Worker] gateway not live — skip");
            return 0;
        }
        List<KfeChannelCapacityJobEntity> pending = queueService.pending(limit);
        int processed = 0;
        for (KfeChannelCapacityJobEntity job : pending) {
            try {
                executeJob(job.getId());
                processed++;
            } catch (RuntimeException ex) {
                log.warn("[KFE Capacity Worker] job {} failed: {}", job.getId(), ex.getMessage());
                queueService.fail(job.getId(), ex.getMessage());
                metric("error", job.getIntent() != null ? job.getIntent().name() : "unknown");
            }
        }
        return processed;
    }

    public void executeJob(UUID jobId) {
        KfeChannelCapacityJobEntity job = queueService.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Capacity job not found: " + jobId));
        queueService.markInProgress(jobId, "worker-start");
        if (job.getIntent() == KfeChannelCapacityIntent.OPEN) {
            executeOpen(job);
        } else if (job.getIntent() == KfeChannelCapacityIntent.CLOSE) {
            executeClose(job);
        } else {
            queueService.fail(jobId, "UNKNOWN_INTENT");
        }
    }

    private void executeOpen(KfeChannelCapacityJobEntity job) {
        KfeChannelDecisionResponse result = lifecycleService.openChannel(
                new KfeOpenChannelRequest(
                        job.getPeerPubkey(),
                        job.getLocalAmountSats(),
                        assumedFeeRateSatVb,
                        true,
                        true,
                        false));
        if (!result.passed()) {
            queueService.fail(job.getId(), "GATE_FAILED:" + result.decisionReason());
            metric("gate_fail", "OPEN");
            return;
        }
        if (!result.executed()) {
            queueService.fail(job.getId(), "NOT_EXECUTED:" + result.decisionReason());
            metric("not_executed", "OPEN");
            return;
        }
        queueService.complete(
                job.getId(),
                result.providerReference() != null ? result.providerReference() : result.channelPoint());
        metric("completed", "OPEN");
        log.info(
                "[KFE Capacity Worker] OPEN completed job={} channel={}",
                job.getId(),
                result.channelPoint());
    }

    private void executeClose(KfeChannelCapacityJobEntity job) {
        KfeChannelDecisionResponse result = lifecycleService.closeChannel(
                new KfeCloseChannelRequest(
                        job.getChannelPoint(),
                        false,
                        true,
                        assumedFeeRateSatVb));
        if (!result.passed()) {
            queueService.fail(job.getId(), "GATE_FAILED:" + result.decisionReason());
            metric("gate_fail", "CLOSE");
            return;
        }
        if (!result.executed()) {
            queueService.fail(job.getId(), "NOT_EXECUTED:" + result.decisionReason());
            metric("not_executed", "CLOSE");
            return;
        }
        queueService.complete(
                job.getId(),
                result.providerReference() != null ? result.providerReference() : "CLOSED");
        metric("completed", "CLOSE");
        log.info(
                "[KFE Capacity Worker] CLOSE completed job={} channel={}",
                job.getId(),
                job.getChannelPoint());
    }

    private void metric(String result, String intent) {
        KfeLightningOpsMetrics m = opsMetrics.getIfAvailable();
        if (m != null) {
            m.recordCapacity(result, intent);
        }
    }
}
