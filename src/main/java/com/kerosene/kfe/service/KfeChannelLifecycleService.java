package com.kerosene.kfe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kerosene.kfe.application.channel.ChannelDecisionResult;
import com.kerosene.kfe.application.channel.KfeChannelDecisionService;
import com.kerosene.kfe.dto.KfeChannelDecisionResponse;
import com.kerosene.kfe.dto.KfeChannelSnapshotResponse;
import com.kerosene.kfe.dto.KfeCloseChannelRequest;
import com.kerosene.kfe.dto.KfeOpenChannelRequest;
import com.kerosene.kfe.dto.KfePpmAdjustRequest;
import com.kerosene.kfe.dto.KfeRebalanceChannelRequest;
import com.kerosene.kfe.model.KfeChannelOperationDecisionEntity;
import com.kerosene.kfe.model.KfeChannelOperationType;
import com.kerosene.kfe.rail.ChannelsMeshInjectGateway;
import com.kerosene.kfe.rail.LightningChannelGateway;
import com.kerosene.kfe.repository.KfeChannelOperationDecisionRepository;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Channel lifecycle: binary decision → optional LND execution → forensic row.
 * Structural costs are attributed under SYSTEM_PROFIT policy (profit wallet must exist).
 *
 * <p>When {@code kfe.channel.require-channels-mesh-inject=true}, OPEN couples mesh CHANNELS
 * soft-reserve → LND address bind (fund) → LND {@code openChannel} → Intent commit
 * (release on open failure). Durable phase + stable Intent id enable crash resume and
 * commit-retry.
 */
@Service
public class KfeChannelLifecycleService {

    public static final String PHASE_RESERVED = "RESERVED";
    public static final String PHASE_FUNDED = "FUNDED";
    public static final String PHASE_OPENED_COMMIT_PENDING = "OPENED_COMMIT_PENDING";
    public static final String PHASE_COMMITTED = "COMMITTED";
    public static final String PHASE_RELEASED = "RELEASED";

    private final KfeChannelDecisionService decisionService;
    private final LightningChannelGateway channelGateway;
    private final ChannelsMeshInjectGateway channelsMeshInject;
    private final KfeChannelOperationDecisionRepository decisionRepository;
    private final KfeChannelRebalanceQueueService rebalanceQueueService;
    private final KfeHashService hashService;
    private final KfeSystemWalletService systemWalletService;
    private final KfeAuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final boolean anchorsRequired;
    private final int defaultTimeLockDelta;
    private final boolean requireChannelsMeshInject;

    public KfeChannelLifecycleService(
            KfeChannelDecisionService decisionService,
            LightningChannelGateway channelGateway,
            ChannelsMeshInjectGateway channelsMeshInject,
            KfeChannelOperationDecisionRepository decisionRepository,
            KfeChannelRebalanceQueueService rebalanceQueueService,
            KfeHashService hashService,
            KfeSystemWalletService systemWalletService,
            KfeAuditLogService auditLogService,
            ObjectMapper objectMapper,
            @Value("${kfe.channel.anchors-required:true}") boolean anchorsRequired,
            @Value("${kfe.channel.default-time-lock-delta:80}") int defaultTimeLockDelta,
            @Value("${kfe.channel.require-channels-mesh-inject:false}") boolean requireChannelsMeshInject) {
        this.decisionService = decisionService;
        this.channelGateway = channelGateway;
        this.channelsMeshInject = channelsMeshInject;
        this.decisionRepository = decisionRepository;
        this.rebalanceQueueService = rebalanceQueueService;
        this.hashService = hashService;
        this.systemWalletService = systemWalletService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.anchorsRequired = anchorsRequired;
        this.defaultTimeLockDelta = Math.max(18, defaultTimeLockDelta);
        this.requireChannelsMeshInject = requireChannelsMeshInject;
    }

    @Transactional(readOnly = true)
    public List<KfeChannelSnapshotResponse> listChannels() {
        return channelGateway.listChannels().stream().map(this::toSnapshot).toList();
    }

    @Transactional
    public KfeChannelDecisionResponse evaluateOpen(KfeOpenChannelRequest request) {
        boolean anchors = resolveAnchorsEnabled(request.anchorsEnabled());
        long feeRate = request.estimatedFeeRateSatVb() != null ? request.estimatedFeeRateSatVb() : 0L;
        String proposal = proposalHash("OPEN", request.peerPubkey(), request.localAmountSats());
        ChannelDecisionResult decision = decisionService.evaluateOpen(
                request.peerPubkey(),
                request.localAmountSats(),
                feeRate,
                anchors,
                proposal);
        return persistDecision(
                KfeChannelOperationType.OPEN,
                decision,
                request.peerPubkey(),
                null,
                request.localAmountSats(),
                false,
                null);
    }

    @Transactional
    public KfeChannelDecisionResponse openChannel(KfeOpenChannelRequest request) {
        KfeChannelDecisionResponse evaluated = resumeOrEvaluateOpen(request);
        if (!evaluated.passed()) {
            return evaluated;
        }
        if (!channelGateway.isLive()) {
            return markReason(evaluated.id(), "GATEWAY_NOT_LIVE");
        }

        if (requireChannelsMeshInject) {
            KfeChannelOperationDecisionEntity existing =
                    decisionRepository.findById(evaluated.id()).orElse(null);
            if (existing != null
                    && PHASE_OPENED_COMMIT_PENDING.equals(existing.getMeshInjectPhase())) {
                // Channel already opened on LND — only retry Intent commit (do not
                // treat pending open as a double-open refuse).
                return retryCommit(evaluated.id());
            }
        }

        if (hasOpenOrPendingChannelToPeer(request.peerPubkey())) {
            return markReason(evaluated.id(), "CHANNEL_ALREADY_OPEN");
        }
        systemWalletService.requireProfitWalletId();

        if (!requireChannelsMeshInject) {
            LightningChannelGateway.OpenChannelResult result = channelGateway.openChannel(
                    new LightningChannelGateway.OpenChannelCommand(
                            request.peerPubkey(),
                            request.localAmountSats(),
                            Boolean.TRUE.equals(request.privateChannel()),
                            Boolean.TRUE.equals(request.spendUnconfirmed())));
            return markExecuted(evaluated.id(), result.channelPoint(), result.fundingTxid());
        }

        return openChannelWithMeshInject(evaluated.id(), request);
    }

    private KfeChannelDecisionResponse openChannelWithMeshInject(
            UUID decisionId, KfeOpenChannelRequest request) {
        KfeChannelOperationDecisionEntity entity = decisionRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Channel decision not found."));

        String injectIntentId = entity.getMeshIntentId();
        if (injectIntentId == null || injectIntentId.isBlank()) {
            injectIntentId = "channels-inject-open-" + decisionId;
            entity.setMeshIntentId(injectIntentId);
            decisionRepository.save(entity);
        }

        String phase = entity.getMeshInjectPhase();
        if (phase == null
                || phase.isBlank()
                || PHASE_RELEASED.equals(phase)) {
            ChannelsMeshInjectGateway.DebitResult debit =
                    channelsMeshInject.reserveOpen(
                            injectIntentId, request.localAmountSats(), request.peerPubkey());
            if (!debit.authorized()) {
                return markReason(
                        decisionId,
                        debit.reasonCode() == null
                                ? "CHANNELS_INJECT_RESERVE_FAILED"
                                : debit.reasonCode());
            }
            injectIntentId = debit.intentId() != null ? debit.intentId() : injectIntentId;
            entity.setMeshIntentId(injectIntentId);
            entity.setMeshInjectPhase(PHASE_RESERVED);
            entity.setDecisionReason("MESH_RESERVED");
            decisionRepository.save(entity);
            phase = PHASE_RESERVED;
        }

        if (PHASE_RESERVED.equals(phase)) {
            String fundingAddress = entity.getLndFundingAddress();
            if (fundingAddress == null || fundingAddress.isBlank()) {
                try {
                    fundingAddress =
                            channelGateway.newOnchainAddress("channels-inject-" + decisionId);
                } catch (RuntimeException ex) {
                    return markReason(
                            decisionId,
                            "CHANNELS_INJECT_LND_ADDRESS_FAILED:"
                                    + ex.getClass().getSimpleName());
                }
                entity.setLndFundingAddress(fundingAddress);
                decisionRepository.save(entity);
            }

            ChannelsMeshInjectGateway.FundResult funded =
                    channelsMeshInject.fundOpen(
                            injectIntentId, request.localAmountSats(), fundingAddress);
            if (!funded.authorized()) {
                compensateRelease(entity, request.localAmountSats(), request.peerPubkey());
                return markReason(
                        decisionId,
                        funded.reasonCode() == null
                                ? "CHANNELS_INJECT_FUND_FAILED"
                                : funded.reasonCode());
            }

            long onchain = channelGateway.confirmedOnchainBalanceSats();
            if (onchain >= 0L && onchain < request.localAmountSats()) {
                compensateRelease(entity, request.localAmountSats(), request.peerPubkey());
                return markReason(decisionId, "CHANNELS_INJECT_LND_WALLET_UNDERFUNDED");
            }

            entity.setMeshFundTxid(funded.fundingTxid());
            entity.setMeshInjectPhase(PHASE_FUNDED);
            entity.setDecisionReason("MESH_FUNDED");
            decisionRepository.save(entity);
            phase = PHASE_FUNDED;
        }

        if (PHASE_FUNDED.equals(phase)) {
            LightningChannelGateway.OpenChannelResult result;
            try {
                result = channelGateway.openChannel(
                        new LightningChannelGateway.OpenChannelCommand(
                                request.peerPubkey(),
                                request.localAmountSats(),
                                Boolean.TRUE.equals(request.privateChannel()),
                                Boolean.TRUE.equals(request.spendUnconfirmed())));
            } catch (RuntimeException ex) {
                compensateRelease(entity, request.localAmountSats(), request.peerPubkey());
                String suffix = PHASE_RELEASED.equals(entity.getMeshInjectPhase())
                        ? ":COMPENSATED"
                        : ":COMPENSATE_FAILED";
                return markReason(decisionId, "OPEN_FAILED_AFTER_DEBIT" + suffix);
            }

            entity.setChannelPoint(result.channelPoint());
            entity.setProviderReference(
                    "OPENED_COMMIT_PENDING:"
                            + injectIntentId
                            + "|"
                            + (result.fundingTxid() == null ? "" : result.fundingTxid()));
            entity.setMeshInjectPhase(PHASE_OPENED_COMMIT_PENDING);
            entity.setDecisionReason("OPENED_COMMIT_PENDING");
            entity.setExecuted(false);
            decisionRepository.save(entity);
            phase = PHASE_OPENED_COMMIT_PENDING;
        }

        if (PHASE_OPENED_COMMIT_PENDING.equals(phase)) {
            return retryCommit(decisionId);
        }

        return toResponse(entity);
    }

    /**
     * Idempotent Intent commit for decisions stuck in {@link #PHASE_OPENED_COMMIT_PENDING}.
     */
    @Transactional
    public KfeChannelDecisionResponse retryCommit(UUID decisionId) {
        KfeChannelOperationDecisionEntity entity = decisionRepository.findById(decisionId)
                .orElseThrow(() -> new IllegalArgumentException("Channel decision not found."));
        if (entity.isExecuted() && PHASE_COMMITTED.equals(entity.getMeshInjectPhase())) {
            return toResponse(entity);
        }
        String intentId = entity.getMeshIntentId();
        if (intentId == null || intentId.isBlank()) {
            return markReason(decisionId, "OPENED_COMMIT_FAILED:MISSING_INTENT");
        }
        ChannelsMeshInjectGateway.InjectResult committed = channelsMeshInject.commitOpen(intentId);
        if (!committed.authorized()) {
            entity.setDecisionReason(
                    "OPENED_COMMIT_FAILED:"
                            + intentId
                            + ":"
                            + (committed.reasonCode() == null ? "UNKNOWN" : committed.reasonCode()));
            entity.setMeshInjectPhase(PHASE_OPENED_COMMIT_PENDING);
            entity.setExecuted(false);
            return toResponse(decisionRepository.save(entity));
        }
        entity.setMeshInjectPhase(PHASE_COMMITTED);
        entity.setExecuted(true);
        entity.setDecisionReason("EXECUTED");
        String ref = entity.getProviderReference();
        if (ref == null || ref.startsWith("OPENED_COMMIT_PENDING:")) {
            entity.setProviderReference(
                    "MESH:"
                            + intentId
                            + (entity.getChannelPoint() != null
                                    ? "|" + entity.getChannelPoint()
                                    : ""));
        }
        return toResponse(decisionRepository.save(entity));
    }

    private void compensateRelease(
            KfeChannelOperationDecisionEntity entity, long amountSats, String peerPubkey) {
        String intentId = entity.getMeshIntentId();
        if (intentId == null || intentId.isBlank()) {
            return;
        }
        ChannelsMeshInjectGateway.InjectResult released =
                channelsMeshInject.releaseOpen(intentId, amountSats, peerPubkey);
        if (released.authorized()) {
            entity.setMeshInjectPhase(PHASE_RELEASED);
            decisionRepository.save(entity);
        }
    }

    private KfeChannelDecisionResponse resumeOrEvaluateOpen(KfeOpenChannelRequest request) {
        Optional<KfeChannelOperationDecisionEntity> resumable =
                decisionRepository.findLatestResumableOpen(
                        request.peerPubkey(),
                        request.localAmountSats(),
                        List.of(PHASE_RESERVED, PHASE_FUNDED, PHASE_OPENED_COMMIT_PENDING));
        if (resumable.isPresent()) {
            return toResponse(resumable.get());
        }
        return evaluateOpen(request);
    }

    private boolean hasOpenOrPendingChannelToPeer(String peerPubkey) {
        if (peerPubkey == null || peerPubkey.isBlank()) {
            return false;
        }
        String normalized = peerPubkey.trim().toLowerCase(Locale.ROOT);
        boolean open = channelGateway.listChannels().stream()
                .anyMatch(
                        c -> c.remotePubkey() != null
                                && normalized.equals(c.remotePubkey().trim().toLowerCase(Locale.ROOT)));
        if (open) {
            return true;
        }
        return channelGateway.listPendingChannels().stream()
                .anyMatch(
                        c -> c.remotePubkey() != null
                                && normalized.equals(
                                        c.remotePubkey().trim().toLowerCase(Locale.ROOT)));
    }

    @Transactional
    public KfeChannelDecisionResponse evaluateRebalance(KfeRebalanceChannelRequest request) {
        LightningChannelGateway.ChannelSnapshot channel = findChannel(request.channelPoint());
        ChannelDecisionResult decision = decisionService.evaluateRebalance(
                channel,
                request.estimatedCostSats() != null ? request.estimatedCostSats() : 0L,
                request.expectedGainSats() != null ? request.expectedGainSats() : 0L);
        return persistDecision(
                KfeChannelOperationType.REBALANCE,
                decision,
                channel != null ? channel.remotePubkey() : null,
                request.channelPoint(),
                request.estimatedCostSats(),
                false,
                null);
    }

    @Transactional
    public KfeChannelDecisionResponse rebalance(KfeRebalanceChannelRequest request) {
        KfeChannelDecisionResponse evaluated = evaluateRebalance(request);
        if (!evaluated.passed()) {
            return evaluated;
        }
        systemWalletService.requireProfitWalletId();
        LightningChannelGateway.ChannelSnapshot channel = findChannel(request.channelPoint());
        var job = rebalanceQueueService.enqueueIfAbsent(
                evaluated.id(),
                request.channelPoint(),
                channel != null ? channel.remotePubkey() : null,
                request.estimatedCostSats() != null ? request.estimatedCostSats() : 0L,
                request.expectedGainSats() != null ? request.expectedGainSats() : 0L);
        if (job.isEmpty()) {
            return markReason(evaluated.id(), "REBALANCE_ENQUEUE_REJECTED");
        }
        return markExecuted(
                evaluated.id(),
                request.channelPoint(),
                "REBALANCE_QUEUED:" + job.get().getId());
    }

    @Transactional(readOnly = true)
    public java.util.List<com.kerosene.kfe.model.KfeChannelRebalanceJobEntity> pendingRebalances(int limit) {
        return rebalanceQueueService.pending(limit);
    }

    @Transactional
    public KfeChannelDecisionResponse evaluateClose(KfeCloseChannelRequest request) {
        LightningChannelGateway.ChannelSnapshot channel = findChannel(request.channelPoint());
        long feeRate = request.estimatedFeeRateSatVb() != null ? request.estimatedFeeRateSatVb() : 1L;
        ChannelDecisionResult decision = decisionService.evaluateClose(
                channel,
                Boolean.TRUE.equals(request.peerOfflineBeyondThreshold()),
                !Boolean.TRUE.equals(request.force()),
                anchorsRequired,
                feeRate);
        return persistDecision(
                KfeChannelOperationType.CLOSE,
                decision,
                channel != null ? channel.remotePubkey() : null,
                request.channelPoint(),
                null,
                false,
                null);
    }

    @Transactional
    public KfeChannelDecisionResponse closeChannel(KfeCloseChannelRequest request) {
        KfeChannelDecisionResponse evaluated = evaluateClose(request);
        if (!evaluated.passed()) {
            return evaluated;
        }
        if (!channelGateway.isLive()) {
            return markReason(evaluated.id(), "GATEWAY_NOT_LIVE");
        }
        LightningChannelGateway.CloseChannelResult result = channelGateway.closeChannel(
                new LightningChannelGateway.CloseChannelCommand(
                        request.channelPoint(),
                        Boolean.TRUE.equals(request.force())));
        return markExecuted(evaluated.id(), request.channelPoint(), result.closingTxid());
    }

    @Transactional
    public KfeChannelDecisionResponse evaluatePpm(KfePpmAdjustRequest request) {
        long current = request.currentPpm() != null ? request.currentPpm() : 0L;
        boolean drain = Boolean.TRUE.equals(request.acceleratedDrain());
        ChannelDecisionResult decision = decisionService.evaluatePpm(current, drain);
        return persistDecision(
                KfeChannelOperationType.PPM_ADJUST,
                decision,
                null,
                request.channelPoint(),
                decisionService.recommendedPpm(current, drain),
                false,
                null);
    }

    @Transactional
    public KfeChannelDecisionResponse adjustPpm(KfePpmAdjustRequest request) {
        KfeChannelDecisionResponse evaluated = evaluatePpm(request);
        if (!evaluated.passed()) {
            return evaluated;
        }
        if (!channelGateway.isLive()) {
            return markReason(evaluated.id(), "GATEWAY_NOT_LIVE");
        }
        long current = request.currentPpm() != null ? request.currentPpm() : 0L;
        boolean drain = Boolean.TRUE.equals(request.acceleratedDrain());
        long targetPpm = decisionService.recommendedPpm(current, drain);
        LightningChannelGateway.UpdatePolicyResult result = channelGateway.updateChannelPolicy(
                new LightningChannelGateway.UpdatePolicyCommand(
                        request.channelPoint(),
                        request.baseFeeMsat() != null ? request.baseFeeMsat() : 1000L,
                        targetPpm,
                        defaultTimeLockDelta));
        if (!result.ok()) {
            return markReason(evaluated.id(), "POLICY_UPDATE_FAILED");
        }
        return markExecuted(evaluated.id(), request.channelPoint(), "PPM:" + targetPpm);
    }

    private boolean resolveAnchorsEnabled(Boolean requestValue) {
        if (requestValue != null) {
            return requestValue;
        }
        // Default true when anchors are required by policy (LND modern defaults).
        return anchorsRequired;
    }

    private LightningChannelGateway.ChannelSnapshot findChannel(String channelPoint) {
        if (channelPoint == null || channelPoint.isBlank()) {
            return null;
        }
        return channelGateway.listChannels().stream()
                .filter(channel -> channelPoint.equals(channel.channelPoint()))
                .findFirst()
                .orElse(null);
    }

    private KfeChannelDecisionResponse persistDecision(
            KfeChannelOperationType type,
            ChannelDecisionResult decision,
            String peer,
            String channelPoint,
            Long amountSats,
            boolean executed,
            String providerReference) {
        KfeChannelOperationDecisionEntity entity = new KfeChannelOperationDecisionEntity();
        entity.setOperation(type);
        entity.setPassed(decision.passed());
        entity.setPeerPubkey(peer);
        entity.setChannelPoint(channelPoint);
        entity.setAmountSats(amountSats);
        entity.setFlagsJson(toJson(decision.toAuditMap()));
        entity.setDecisionReason(decision.passed() ? "AND_PASS" : "AND_FAIL");
        entity.setExecuted(executed);
        entity.setProviderReference(providerReference);
        entity = decisionRepository.save(entity);

        auditLogService.record(
                "KFE_CHANNEL_DECISION",
                null,
                null,
                null,
                null,
                Map.of(
                        "channelDecisionId", entity.getId().toString(),
                        "channelOperation", type.name(),
                        "passed", decision.passed() ? 1 : 0,
                        "flags", decision.toAuditMap().get("flags")));

        return toResponse(entity);
    }

    private KfeChannelDecisionResponse markExecuted(UUID id, String channelPoint, String providerReference) {
        KfeChannelOperationDecisionEntity entity = decisionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Channel decision not found."));
        entity.setExecuted(true);
        entity.setChannelPoint(channelPoint != null ? channelPoint : entity.getChannelPoint());
        entity.setProviderReference(providerReference);
        entity.setDecisionReason("EXECUTED");
        return toResponse(decisionRepository.save(entity));
    }

    private KfeChannelDecisionResponse markReason(UUID id, String reason) {
        KfeChannelOperationDecisionEntity entity = decisionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Channel decision not found."));
        entity.setDecisionReason(reason);
        entity.setExecuted(false);
        return toResponse(decisionRepository.save(entity));
    }

    private String proposalHash(String op, String peer, long amount) {
        return hashService.sha256(String.join(
                "|",
                "KFE_CHANNEL",
                op,
                peer != null ? peer : "",
                String.valueOf(amount)));
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{}";
        }
    }

    private KfeChannelSnapshotResponse toSnapshot(LightningChannelGateway.ChannelSnapshot channel) {
        return new KfeChannelSnapshotResponse(
                channel.channelPoint(),
                channel.remotePubkey(),
                channel.active(),
                channel.capacitySats(),
                channel.localBalanceSats(),
                channel.remoteBalanceSats(),
                channel.pendingHtlcs(),
                channel.initiator(),
                channel.commitFeeSats(),
                channel.localRatio());
    }

    private KfeChannelDecisionResponse toResponse(KfeChannelOperationDecisionEntity entity) {
        return new KfeChannelDecisionResponse(
                entity.getId(),
                entity.getOperation(),
                entity.isPassed(),
                entity.isExecuted(),
                entity.getPeerPubkey(),
                entity.getChannelPoint(),
                entity.getAmountSats(),
                entity.getDecisionReason(),
                entity.getProviderReference(),
                entity.getFlagsJson(),
                entity.getCreatedAt());
    }
}
