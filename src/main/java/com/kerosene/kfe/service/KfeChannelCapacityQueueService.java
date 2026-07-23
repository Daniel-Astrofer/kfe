package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.model.KfeChannelCapacityIntent;
import com.kerosene.kfe.model.KfeChannelCapacityJobEntity;
import com.kerosene.kfe.model.KfeChannelCapacityJobStatus;
import com.kerosene.kfe.repository.KfeChannelCapacityJobRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable queue for dead-man capacity intents (OPEN / CLOSE).
 * Binary AND gates are re-evaluated at execution time by the worker.
 */
@Service
public class KfeChannelCapacityQueueService {

    private static final Logger log = LoggerFactory.getLogger(KfeChannelCapacityQueueService.class);

    private final KfeChannelCapacityJobRepository jobRepository;
    private final KfeSystemWalletService systemWalletService;
    private final KfeBalanceService balanceService;
    private final KfeAuditLogService auditLogService;

    public KfeChannelCapacityQueueService(
            KfeChannelCapacityJobRepository jobRepository,
            KfeSystemWalletService systemWalletService,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.systemWalletService = systemWalletService;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public Optional<KfeChannelCapacityJobEntity> enqueueOpenIfAbsent(
            String peerPubkey,
            long localAmountSats,
            long estimatedCostSats,
            long expectedGainSats,
            String triggerReason,
            UUID decisionId) {
        if (peerPubkey == null || peerPubkey.isBlank()) {
            throw new IllegalArgumentException("peerPubkey is required for OPEN capacity intent.");
        }
        String peer = peerPubkey.trim();
        Optional<KfeChannelCapacityJobEntity> existing =
                jobRepository.findFirstByIntentAndPeerPubkeyAndStatusIn(
                        KfeChannelCapacityIntent.OPEN,
                        peer,
                        EnumSet.of(
                                KfeChannelCapacityJobStatus.PENDING,
                                KfeChannelCapacityJobStatus.IN_PROGRESS));
        if (existing.isPresent()) {
            return existing;
        }
        UUID profitWalletId = systemWalletService.requireProfitWalletId();
        if (estimatedCostSats > 0L) {
            var profit = balanceService.requireForUpdate(profitWalletId, KfeSystemWalletService.ASSET_BTC);
            if (profit.getAvailableSats() < estimatedCostSats) {
                log.warn(
                        "[KFE Capacity] profit insufficient for OPEN peer={} cost={} available={}",
                        peer,
                        estimatedCostSats,
                        profit.getAvailableSats());
                return Optional.empty();
            }
        }
        KfeChannelCapacityJobEntity job = new KfeChannelCapacityJobEntity();
        job.setIntent(KfeChannelCapacityIntent.OPEN);
        job.setPeerPubkey(peer);
        job.setLocalAmountSats(Math.max(0L, localAmountSats));
        job.setEstimatedCostSats(Math.max(0L, estimatedCostSats));
        job.setExpectedGainSats(Math.max(0L, expectedGainSats));
        job.setTriggerReason(truncate(triggerReason, 255));
        job.setDecisionId(decisionId);
        job.setStatus(KfeChannelCapacityJobStatus.PENDING);
        try {
            job = jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException ex) {
            return jobRepository.findFirstByIntentAndPeerPubkeyAndStatusIn(
                    KfeChannelCapacityIntent.OPEN,
                    peer,
                    EnumSet.of(
                            KfeChannelCapacityJobStatus.PENDING,
                            KfeChannelCapacityJobStatus.IN_PROGRESS));
        }
        audit(job, profitWalletId);
        return Optional.of(job);
    }

    @Transactional
    public Optional<KfeChannelCapacityJobEntity> enqueueCloseIfAbsent(
            String channelPoint,
            String peerPubkey,
            long estimatedCostSats,
            String triggerReason,
            UUID decisionId) {
        if (channelPoint == null || channelPoint.isBlank()) {
            throw new IllegalArgumentException("channelPoint is required for CLOSE capacity intent.");
        }
        String point = channelPoint.trim();
        Optional<KfeChannelCapacityJobEntity> existing =
                jobRepository.findFirstByIntentAndChannelPointAndStatusIn(
                        KfeChannelCapacityIntent.CLOSE,
                        point,
                        EnumSet.of(
                                KfeChannelCapacityJobStatus.PENDING,
                                KfeChannelCapacityJobStatus.IN_PROGRESS));
        if (existing.isPresent()) {
            return existing;
        }
        UUID profitWalletId = systemWalletService.requireProfitWalletId();
        KfeChannelCapacityJobEntity job = new KfeChannelCapacityJobEntity();
        job.setIntent(KfeChannelCapacityIntent.CLOSE);
        job.setChannelPoint(point);
        job.setPeerPubkey(peerPubkey);
        job.setEstimatedCostSats(Math.max(0L, estimatedCostSats));
        job.setTriggerReason(truncate(triggerReason, 255));
        job.setDecisionId(decisionId);
        job.setStatus(KfeChannelCapacityJobStatus.PENDING);
        try {
            job = jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException ex) {
            return jobRepository.findFirstByIntentAndChannelPointAndStatusIn(
                    KfeChannelCapacityIntent.CLOSE,
                    point,
                    EnumSet.of(
                            KfeChannelCapacityJobStatus.PENDING,
                            KfeChannelCapacityJobStatus.IN_PROGRESS));
        }
        audit(job, profitWalletId);
        return Optional.of(job);
    }

    @Transactional(readOnly = true)
    public List<KfeChannelCapacityJobEntity> pending(int limit) {
        return jobRepository.findByStatusOrderByCreatedAtAsc(
                KfeChannelCapacityJobStatus.PENDING,
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)));
    }

    @Transactional(readOnly = true)
    public long pendingOpenCount() {
        return jobRepository.countByIntentAndStatusIn(
                KfeChannelCapacityIntent.OPEN,
                EnumSet.of(
                        KfeChannelCapacityJobStatus.PENDING,
                        KfeChannelCapacityJobStatus.IN_PROGRESS));
    }

    @Transactional
    public void markInProgress(UUID jobId, String ref) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() == KfeChannelCapacityJobStatus.PENDING
                    || job.getStatus() == KfeChannelCapacityJobStatus.IN_PROGRESS) {
                job.markInProgress(ref);
            }
        });
    }

    @Transactional
    public void complete(UUID jobId, String ref) {
        jobRepository.findById(jobId).ifPresent(job -> job.markCompleted(ref));
    }

    @Transactional
    public void fail(UUID jobId, String error) {
        jobRepository.findById(jobId).ifPresent(job -> job.markFailed(error));
    }

    @Transactional(readOnly = true)
    public Optional<KfeChannelCapacityJobEntity> findById(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    private void audit(KfeChannelCapacityJobEntity job, UUID profitWalletId) {
        auditLogService.record(
                "KFE_CHANNEL_CAPACITY",
                null,
                profitWalletId,
                null,
                null,
                Map.of(
                        "capacityJobId", job.getId().toString(),
                        "intent", job.getIntent().name(),
                        "status", job.getStatus().name(),
                        "peerPubkey", job.getPeerPubkey() != null ? job.getPeerPubkey() : "",
                        "channelPoint", job.getChannelPoint() != null ? job.getChannelPoint() : "",
                        "triggerReason", job.getTriggerReason() != null ? job.getTriggerReason() : "",
                        "localAmountSats", job.getLocalAmountSats(),
                        "estimatedCostSats", job.getEstimatedCostSats()));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
