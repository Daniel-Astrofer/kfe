package com.kerosene.kfe.liquidity.adapters.out.persistence;

import com.kerosene.kfe.audit.adapters.out.persistence.KfeAuditLogService;
import com.kerosene.kfe.ledger.adapters.out.persistence.balance.KfeBalanceService;
import com.kerosene.kfe.wallet.adapters.out.persistence.KfeSystemWalletService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobEntity;
import com.kerosene.kfe.adapters.out.persistence.model.liquidity.KfeChannelRebalanceJobStatus;
import com.kerosene.kfe.adapters.out.persistence.repository.liquidity.KfeChannelRebalanceJobRepository;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * Durable queue for rebalance work after binary decision pass.
 * Provider execution (Loop/submarine/circular) plugs in via {@link #markInProgress}/{@link #complete}.
 */
@Service
public class KfeChannelRebalanceQueueService {

    private static final Logger log = LoggerFactory.getLogger(KfeChannelRebalanceQueueService.class);

    private final KfeChannelRebalanceJobRepository jobRepository;
    private final KfeSystemWalletService systemWalletService;
    private final KfeBalanceService balanceService;
    private final KfeAuditLogService auditLogService;

    public record JobClaim(UUID jobId, UUID claimToken) { }

    public KfeChannelRebalanceQueueService(
            KfeChannelRebalanceJobRepository jobRepository,
            KfeSystemWalletService systemWalletService,
            KfeBalanceService balanceService,
            KfeAuditLogService auditLogService) {
        this.jobRepository = jobRepository;
        this.systemWalletService = systemWalletService;
        this.balanceService = balanceService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public Optional<KfeChannelRebalanceJobEntity> enqueueIfAbsent(
            UUID decisionId,
            String channelPoint,
            String peerPubkey,
            long estimatedCostSats,
            long expectedGainSats) {
        if (channelPoint == null || channelPoint.isBlank()) {
            throw new IllegalArgumentException("channelPoint is required.");
        }
        Optional<KfeChannelRebalanceJobEntity> existing = jobRepository.findFirstByChannelPointAndStatusIn(
                channelPoint.trim(),
                EnumSet.of(KfeChannelRebalanceJobStatus.PENDING, KfeChannelRebalanceJobStatus.IN_PROGRESS));
        if (existing.isPresent()) {
            return existing;
        }
        // Costs must be attributable to SYSTEM_PROFIT policy (wallet must exist).
        UUID profitWalletId = systemWalletService.requireProfitWalletId();
        if (estimatedCostSats > 0L) {
            // Soft capacity check on profit available (does not reserve until provider starts).
            var profit = balanceService.requireForUpdate(profitWalletId, KfeSystemWalletService.ASSET_BTC);
            if (profit.getAvailableSats() < estimatedCostSats) {
                log.warn(
                        "[KFE Channel Rebal] profit wallet insufficient for estimated cost channel={} cost={} available={}",
                        channelPoint,
                        estimatedCostSats,
                        profit.getAvailableSats());
                return Optional.empty();
            }
        }

        KfeChannelRebalanceJobEntity job = new KfeChannelRebalanceJobEntity();
        job.setDecisionId(decisionId);
        job.setChannelPoint(channelPoint.trim());
        job.setPeerPubkey(peerPubkey);
        job.setEstimatedCostSats(Math.max(0L, estimatedCostSats));
        job.setExpectedGainSats(Math.max(0L, expectedGainSats));
        job.setStatus(KfeChannelRebalanceJobStatus.PENDING);
        try {
            job = jobRepository.saveAndFlush(job);
        } catch (DataIntegrityViolationException ex) {
            return jobRepository.findFirstByChannelPointAndStatusIn(
                    channelPoint.trim(),
                    EnumSet.of(KfeChannelRebalanceJobStatus.PENDING, KfeChannelRebalanceJobStatus.IN_PROGRESS));
        }
        auditLogService.record(
                "KFE_CHANNEL_DECISION",
                null,
                profitWalletId,
                null,
                null,
                java.util.Map.of(
                        "rebalanceJobId", job.getId().toString(),
                        "channelPoint", job.getChannelPoint(),
                        "status", job.getStatus().name(),
                        "estimatedCostSats", job.getEstimatedCostSats(),
                        "expectedGainSats", job.getExpectedGainSats()));
        return Optional.of(job);
    }

    @Transactional(readOnly = true)
    public List<KfeChannelRebalanceJobEntity> pending(int limit) {
        return jobRepository.findByStatusOrderByCreatedAtAsc(
                KfeChannelRebalanceJobStatus.PENDING,
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)));
    }

    @Transactional(readOnly = true)
    public Optional<KfeChannelRebalanceJobEntity> findById(UUID jobId) {
        return jobRepository.findById(jobId);
    }

    @Transactional
    public Optional<JobClaim> claim(UUID jobId, String workerId, long leaseSeconds) {
        UUID token = UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int updated = jobRepository.claim(jobId, now, normalize(workerId), token,
                now.plusSeconds(Math.max(1L, leaseSeconds)));
        return updated == 1 ? Optional.of(new JobClaim(jobId, token)) : Optional.empty();
    }

    @Transactional
    public boolean heartbeat(JobClaim claim, long leaseSeconds) {
        if (claim == null) return false;
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        return jobRepository.heartbeat(claim.jobId(), claim.claimToken(), now,
                now.plusSeconds(Math.max(1L, leaseSeconds))) == 1;
    }

    @Transactional
    public boolean completeClaim(JobClaim claim, String providerReference) {
        return claim != null && jobRepository.completeClaimed(claim.jobId(), claim.claimToken(),
                providerReference, LocalDateTime.now(ZoneOffset.UTC)) == 1;
    }

    @Transactional
    public boolean failClaim(JobClaim claim, String ignoredError) {
        return claim != null && jobRepository.failClaimed(claim.jobId(), claim.claimToken(),
                "Channel rebalance execution failed; reconciliation required.",
                LocalDateTime.now(ZoneOffset.UTC)) == 1;
    }

    @Transactional
    public void markInProgress(UUID jobId, String providerReference) {
        jobRepository.findById(jobId).ifPresent(job -> {
            if (job.getStatus() != KfeChannelRebalanceJobStatus.PENDING
                    && job.getStatus() != KfeChannelRebalanceJobStatus.IN_PROGRESS) {
                return;
            }
            job.markInProgress(providerReference);
            jobRepository.save(job);
        });
    }

    @Transactional
    public void complete(UUID jobId, String providerReference) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.markCompleted(providerReference);
            jobRepository.save(job);
        });
    }

    @Transactional
    public void fail(UUID jobId, String error) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.markFailed(error);
            jobRepository.save(job);
        });
    }

    private static String normalize(String workerId) {
        if (workerId == null || workerId.isBlank()) return "kfe-channel-rebalance-worker";
        String value = workerId.trim().toLowerCase(java.util.Locale.ROOT);
        return value.substring(0, Math.min(128, value.length()));
    }
}
