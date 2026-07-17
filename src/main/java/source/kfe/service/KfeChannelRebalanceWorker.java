package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import source.kfe.model.KfeChannelRebalanceJobEntity;
import source.kfe.model.KfeChannelRebalanceJobStatus;
import source.kfe.rail.LightningChannelGateway;
import source.kfe.rail.LightningLoopClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes PENDING rebalance jobs:
 * <ol>
 *   <li>LND circular self-payment (prefer)</li>
 *   <li>Lightning Loop In/Out when loopd is configured and circular fails</li>
 * </ol>
 * Fee budget is reserved from SYSTEM_PROFIT.
 */
@Service
public class KfeChannelRebalanceWorker {

    private static final Logger log = LoggerFactory.getLogger(KfeChannelRebalanceWorker.class);

    private final KfeChannelRebalanceQueueService queueService;
    private final LightningChannelGateway channelGateway;
    private final ObjectProvider<LightningLoopClient> loopClientProvider;
    private final KfeSystemWalletService systemWalletService;
    private final KfeBalanceService balanceService;
    private final KfeAuditLogService auditLogService;
    private final ObjectProvider<KfeLightningOpsMetrics> opsMetrics;
    private final boolean enabled;
    private final boolean loopFallbackEnabled;
    private final int batchSize;
    private final long maxFeeSats;
    private final long maxAmountSats;

    public KfeChannelRebalanceWorker(
            KfeChannelRebalanceQueueService queueService,
            LightningChannelGateway channelGateway,
            ObjectProvider<LightningLoopClient> loopClientProvider,
            KfeSystemWalletService systemWalletService,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService,
            ObjectProvider<KfeLightningOpsMetrics> opsMetrics,
            @Value("${kfe.channel.rebalance-worker.enabled:true}") boolean enabled,
            @Value("${kfe.channel.rebalance-worker.loop-fallback-enabled:true}") boolean loopFallbackEnabled,
            @Value("${kfe.channel.rebalance-worker.batch-size:5}") int batchSize,
            @Value("${kfe.channel.rebalance-worker.max-fee-sats:5000}") long maxFeeSats,
            @Value("${kfe.channel.rebalance-worker.max-amount-sats:500000}") long maxAmountSats) {
        this.queueService = queueService;
        this.channelGateway = channelGateway;
        this.loopClientProvider = loopClientProvider;
        this.systemWalletService = systemWalletService;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
        this.opsMetrics = opsMetrics;
        this.enabled = enabled;
        this.loopFallbackEnabled = loopFallbackEnabled;
        this.batchSize = Math.max(1, batchSize);
        this.maxFeeSats = Math.max(0L, maxFeeSats);
        this.maxAmountSats = Math.max(1L, maxAmountSats);
    }

    @Scheduled(
            fixedDelayString = "${kfe.channel.rebalance-worker.fixed-delay-ms:90000}",
            initialDelayString = "${kfe.channel.rebalance-worker.initial-delay-ms:60000}")
    public void processPending() {
        if (!enabled) {
            return;
        }
        processBatch(batchSize);
    }

    public int processBatch(int limit) {
        if (!channelGateway.isLive()) {
            log.debug("[KFE Rebal Worker] gateway not live — skip");
            return 0;
        }
        List<KfeChannelRebalanceJobEntity> pending = queueService.pending(limit);
        int processed = 0;
        for (KfeChannelRebalanceJobEntity job : pending) {
            try {
                executeJob(job.getId());
                processed++;
            } catch (RuntimeException ex) {
                log.warn("[KFE Rebal Worker] job {} failed: {}", job.getId(), ex.getMessage());
                queueService.fail(job.getId(), ex.getMessage());
                metric("error", "worker");
            }
        }
        return processed;
    }

    @Transactional
    public void executeJob(UUID jobId) {
        KfeChannelRebalanceJobEntity job = queueService.findById(jobId).orElse(null);
        if (job == null) {
            return;
        }
        if (job.getStatus() != KfeChannelRebalanceJobStatus.PENDING
                && job.getStatus() != KfeChannelRebalanceJobStatus.IN_PROGRESS) {
            return;
        }
        queueService.markInProgress(jobId, "WORKER_START");

        long amount = resolveAmount(job);
        long feeBudget = Math.min(maxFeeSats, Math.max(job.getEstimatedCostSats(), 1L));
        UUID profitId = systemWalletService.requireProfitWalletId();
        boolean reservedFee = reserveFee(profitId, feeBudget, jobId);
        if (feeBudget > 0L && !reservedFee) {
            return;
        }

        // 1) Circular self-payment
        LightningChannelGateway.CircularRebalanceResult circular = channelGateway.attemptCircularRebalance(
                new LightningChannelGateway.CircularRebalanceCommand(
                        job.getChannelPoint(),
                        amount,
                        feeBudget,
                        "kfe-rebalance-job-" + jobId));
        if (circular.succeeded()) {
            settleFee(profitId, feeBudget, circular.feeSats(), reservedFee);
            queueService.complete(jobId, firstNonBlank(circular.paymentHash(), "CIRCULAR_OK"));
            audit(jobId, true, "CIRCULAR", circular.status(), circular.paymentHash(), circular.feeSats(), "OK");
            metric("success", "circular");
            log.info("[KFE Rebal Worker] circular OK job={} channel={}", jobId, job.getChannelPoint());
            return;
        }

        // 2) Loop fallback (submarine)
        if (loopFallbackEnabled) {
            LightningLoopClient loop = loopClientProvider.getIfAvailable();
            if (loop != null && loop.isLive()) {
                LightningLoopClient.LoopResult loopResult = attemptLoop(job, amount);
                if (loopResult.succeeded()) {
                    settleFee(profitId, feeBudget, feeBudget, reservedFee);
                    queueService.complete(
                            jobId, firstNonBlank(loopResult.swapId(), loopResult.kind()));
                    audit(
                            jobId,
                            true,
                            loopResult.kind(),
                            "SUBMITTED",
                            loopResult.swapId(),
                            feeBudget,
                            "OK");
                    metric("success", "loop");
                    log.info(
                            "[KFE Rebal Worker] loop OK job={} kind={} swap={}",
                            jobId,
                            loopResult.kind(),
                            loopResult.swapId());
                    return;
                }
                log.warn(
                        "[KFE Rebal Worker] loop fallback failed job={}: {}",
                        jobId,
                        loopResult.message());
            }
        }

        if (reservedFee) {
            balanceService.releaseReserved(profitId, KfeSystemWalletService.ASSET_BTC, feeBudget);
        }
        String failMsg = circular.message() != null ? circular.message() : circular.status();
        queueService.fail(jobId, failMsg);
        audit(jobId, false, "CIRCULAR", circular.status(), null, 0L, failMsg);
        metric("failed", "circular");
    }

    private LightningLoopClient.LoopResult attemptLoop(KfeChannelRebalanceJobEntity job, long amount) {
        LightningLoopClient loop = loopClientProvider.getIfAvailable();
        if (loop == null) {
            return LightningLoopClient.LoopResult.failed("NONE", "loop client missing", null);
        }
        LightningChannelGateway.ChannelSnapshot target = channelGateway.listChannels().stream()
                .filter(c -> job.getChannelPoint().equals(c.channelPoint()))
                .findFirst()
                .orElse(null);
        // Drained (low local) → Loop In. Excess local → Loop Out forced on that channel.
        if (target != null && target.localRatio() >= 0.5d && target.chanId() != null) {
            return loop.loopOut(amount, List.of(target.chanId()));
        }
        return loop.loopIn(amount);
    }

    private boolean reserveFee(UUID profitId, long feeBudget, UUID jobId) {
        if (feeBudget <= 0L) {
            return false;
        }
        try {
            balanceService.reserve(profitId, KfeSystemWalletService.ASSET_BTC, feeBudget);
            return true;
        } catch (RuntimeException ex) {
            queueService.fail(jobId, "PROFIT_RESERVE_FAILED:" + safe(ex));
            metric("failed", "profit_reserve");
            return false;
        }
    }

    private long resolveAmount(KfeChannelRebalanceJobEntity job) {
        long gain = job.getExpectedGainSats();
        if (gain <= 0L) {
            gain = Math.max(1_000L, job.getEstimatedCostSats() * 5);
        }
        return Math.min(maxAmountSats, gain);
    }

    private void settleFee(UUID profitId, long feeBudget, long actualFee, boolean reservedFee) {
        if (!reservedFee || feeBudget <= 0L) {
            return;
        }
        if (actualFee > 0L && actualFee <= feeBudget) {
            balanceService.settleReservedDebit(profitId, KfeSystemWalletService.ASSET_BTC, actualFee);
            long release = feeBudget - actualFee;
            if (release > 0L) {
                balanceService.releaseReserved(profitId, KfeSystemWalletService.ASSET_BTC, release);
            }
            return;
        }
        balanceService.settleReservedDebit(profitId, KfeSystemWalletService.ASSET_BTC, feeBudget);
    }

    private void audit(
            UUID jobId,
            boolean success,
            String provider,
            String status,
            String reference,
            long feeSats,
            String message) {
        auditLogService.record(
                "KFE_CHANNEL_DECISION",
                null,
                null,
                null,
                null,
                Map.of(
                        "rebalanceJobId", jobId.toString(),
                        "success", success ? 1 : 0,
                        "provider", provider != null ? provider : "",
                        "status", status != null ? status : "",
                        "reference", reference != null ? reference : "",
                        "feeSats", feeSats,
                        "message", message != null ? message : ""));
    }

    private void metric(String result, String provider) {
        KfeLightningOpsMetrics metrics = opsMetrics.getIfAvailable();
        if (metrics != null) {
            metrics.recordRebalance(result, provider);
        }
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b;
    }

    private static String safe(Throwable ex) {
        String message = ex.getMessage();
        return message != null && !message.isBlank() ? message : ex.getClass().getSimpleName();
    }
}
