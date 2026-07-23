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
import com.kerosene.kfe.rail.LightningChannelGateway;
import com.kerosene.kfe.repository.KfeChannelOperationDecisionRepository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Channel lifecycle: binary decision → optional LND execution → forensic row.
 * Structural costs are attributed under SYSTEM_PROFIT policy (profit wallet must exist).
 */
@Service
public class KfeChannelLifecycleService {

    private final KfeChannelDecisionService decisionService;
    private final LightningChannelGateway channelGateway;
    private final KfeChannelOperationDecisionRepository decisionRepository;
    private final KfeChannelRebalanceQueueService rebalanceQueueService;
    private final KfeHashService hashService;
    private final KfeSystemWalletService systemWalletService;
    private final KfeAuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final boolean anchorsRequired;
    private final int defaultTimeLockDelta;

    public KfeChannelLifecycleService(
            KfeChannelDecisionService decisionService,
            LightningChannelGateway channelGateway,
            KfeChannelOperationDecisionRepository decisionRepository,
            KfeChannelRebalanceQueueService rebalanceQueueService,
            KfeHashService hashService,
            KfeSystemWalletService systemWalletService,
            KfeAuditLogService auditLogService,
            ObjectMapper objectMapper,
            @Value("${kfe.channel.anchors-required:true}") boolean anchorsRequired,
            @Value("${kfe.channel.default-time-lock-delta:80}") int defaultTimeLockDelta) {
        this.decisionService = decisionService;
        this.channelGateway = channelGateway;
        this.decisionRepository = decisionRepository;
        this.rebalanceQueueService = rebalanceQueueService;
        this.hashService = hashService;
        this.systemWalletService = systemWalletService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
        this.anchorsRequired = anchorsRequired;
        this.defaultTimeLockDelta = Math.max(18, defaultTimeLockDelta);
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
        KfeChannelDecisionResponse evaluated = evaluateOpen(request);
        if (!evaluated.passed()) {
            return evaluated;
        }
        if (!channelGateway.isLive()) {
            return markReason(evaluated.id(), "GATEWAY_NOT_LIVE");
        }
        systemWalletService.requireProfitWalletId();
        LightningChannelGateway.OpenChannelResult result = channelGateway.openChannel(
                new LightningChannelGateway.OpenChannelCommand(
                        request.peerPubkey(),
                        request.localAmountSats(),
                        Boolean.TRUE.equals(request.privateChannel()),
                        Boolean.TRUE.equals(request.spendUnconfirmed())));
        return markExecuted(evaluated.id(), result.channelPoint(), result.fundingTxid());
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
