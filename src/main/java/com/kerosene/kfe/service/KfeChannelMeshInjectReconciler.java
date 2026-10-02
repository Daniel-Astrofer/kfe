package com.kerosene.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.model.KfeChannelOperationDecisionEntity;
import com.kerosene.kfe.rail.ChannelsMeshInjectGateway;
import com.kerosene.kfe.repository.KfeChannelOperationDecisionRepository;
import com.kerosene.kfe.dto.KfeChannelDecisionResponse;
import com.kerosene.kfe.maintenance.KfeMaintenanceGuard;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Reconciles CHANNELS mesh inject crash windows:
 * <ul>
 *   <li>{@code OPENED_COMMIT_PENDING} → idempotent Intent commit retry</li>
 *   <li>orphaned {@code RESERVED} (no open) past TTL → release Intent</li>
 * </ul>
 */
@Service
public class KfeChannelMeshInjectReconciler {

    private static final Logger log = LoggerFactory.getLogger(KfeChannelMeshInjectReconciler.class);

    private KfeMaintenanceGuard maintenanceGuard = KfeMaintenanceGuard.unavailable();

    @Autowired
    public void setMaintenanceGuard(KfeMaintenanceGuard guard) {
        this.maintenanceGuard = java.util.Objects.requireNonNull(guard);
    }

    private final KfeChannelOperationDecisionRepository decisionRepository;
    private final ChannelsMeshInjectGateway channelsMeshInject;
    private final KfeChannelLifecycleService lifecycleService;
    private final boolean enabled;
    private final int batchSize;
    private final long orphanReserveTtlMinutes;

    public KfeChannelMeshInjectReconciler(
            KfeChannelOperationDecisionRepository decisionRepository,
            ChannelsMeshInjectGateway channelsMeshInject,
            KfeChannelLifecycleService lifecycleService,
            @Value("${kfe.channel.mesh-inject-reconciler.enabled:true}") boolean enabled,
            @Value("${kfe.channel.mesh-inject-reconciler.batch-size:20}") int batchSize,
            @Value("${kfe.channel.mesh-inject-reconciler.orphan-reserve-ttl-minutes:10}")
                    long orphanReserveTtlMinutes) {
        this.decisionRepository = decisionRepository;
        this.channelsMeshInject = channelsMeshInject;
        this.lifecycleService = lifecycleService;
        this.enabled = enabled;
        this.batchSize = Math.max(1, batchSize);
        this.orphanReserveTtlMinutes = Math.max(1L, orphanReserveTtlMinutes);
    }

    @Scheduled(
            fixedDelayString = "${kfe.channel.mesh-inject-reconciler.fixed-delay-ms:60000}",
            initialDelayString = "${kfe.channel.mesh-inject-reconciler.initial-delay-ms:45000}")
    public void reconcile() {
        if (!enabled) {
            return;
        }
        try {
            // Separate admissions: a retry batch cannot authorize a later orphan sweep.
            retryPendingCommits(batchSize);
            releaseOrphanedReserves(batchSize);
        } catch (KfeMaintenanceGuard.MaintenanceException paused) {
            log.debug("[KFE Channel Inject] maintenance paused reconciliation: {}", paused.getMessage());
        }
    }

    @Transactional
    public int retryPendingCommits(int limit) {
        return maintenanceGuard.executeMutation("channel.mesh-retry-commits", () ->
                retryPendingCommitsAdmitted(limit), ReconciliationResult::certain).count();
    }

    private ReconciliationResult retryPendingCommitsAdmitted(int limit) {
        List<KfeChannelOperationDecisionEntity> pending =
                decisionRepository.findByMeshInjectPhaseAndExecutedFalseOrderByCreatedAtAsc(
                        KfeChannelLifecycleService.PHASE_OPENED_COMMIT_PENDING,
                        Pageable.ofSize(Math.max(1, limit)));
        int done = 0;
        boolean certain = true;
        for (KfeChannelOperationDecisionEntity row : pending) {
            try {
                KfeChannelDecisionResponse result = lifecycleService.retryCommit(row.getId());
                certain &= result != null && result.executed();
                done++;
            } catch (KfeMaintenanceGuard.MaintenanceException paused) {
                throw paused;
            } catch (RuntimeException ex) {
                certain = false;
                log.warn(
                        "[KFE Channel Inject] commit retry failed decision={}: {}",
                        row.getId(),
                        ex.getMessage());
            }
        }
        return new ReconciliationResult(done, certain);
    }

    @Transactional
    public int releaseOrphanedReserves(int limit) {
        return maintenanceGuard.executeMutation("channel.mesh-release-orphans", () ->
                releaseOrphanedReservesAdmitted(limit), ReconciliationResult::certain).count();
    }

    private ReconciliationResult releaseOrphanedReservesAdmitted(int limit) {
        requireActiveOrphanRecovery();
        LocalDateTime cutoff =
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(orphanReserveTtlMinutes);
        List<KfeChannelOperationDecisionEntity> orphans =
                decisionRepository.findOrphanedReserves(cutoff, Pageable.ofSize(Math.max(1, limit)));
        int released = 0;
        boolean certain = true;
        for (KfeChannelOperationDecisionEntity row : orphans) {
            // An unrelated admitted parent is not provenance for a legacy TTL orphan.
            requireActiveOrphanRecovery();
            String intentId = row.getMeshIntentId();
            if (intentId == null || intentId.isBlank()) {
                certain = false;
                log.warn("[KFE Channel Inject] orphan requires manual recovery decision={}: missing intent",
                        row.getId());
                continue;
            }
            long amount = row.getAmountSats() != null ? row.getAmountSats() : 0L;
            ChannelsMeshInjectGateway.InjectResult result =
                    channelsMeshInject.releaseOpen(intentId, amount, row.getPeerPubkey());
            if (result.authorized()) {
                row.setMeshInjectPhase(KfeChannelLifecycleService.PHASE_RELEASED);
                row.setDecisionReason("MESH_ORPHAN_RESERVE_RELEASED");
                decisionRepository.save(row);
                released++;
            } else {
                certain = false;
                log.warn(
                        "[KFE Channel Inject] orphan release failed decision={} reason={}",
                        row.getId(),
                        result.reasonCode());
            }
        }
        return new ReconciliationResult(released, certain);
    }

    private void requireActiveOrphanRecovery() {
        // This observation only narrows admission; status alone never grants admission.
        // Synchronous open-failure compensation belongs to lifecycle's admitted workflow.
        if (maintenanceGuard.status().mode() != KfeMaintenanceGuard.Mode.ACTIVE) {
            throw new KfeMaintenanceGuard.MaintenanceException(503,
                    "TTL orphan recovery requires ACTIVE maintenance mode.");
        }
    }

    private record ReconciliationResult(int count, boolean certain) { }
}
