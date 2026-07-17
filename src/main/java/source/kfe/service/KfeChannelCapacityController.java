package source.kfe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import source.kfe.application.channel.ChannelDecisionResult;
import source.kfe.application.channel.KfeChannelDecisionService;
import source.kfe.dto.KfeChannelDecisionResponse;
import source.kfe.dto.KfeCloseChannelRequest;
import source.kfe.dto.KfeOpenChannelRequest;
import source.kfe.rail.LightningChannelGateway;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Dead-man capacity loop: usage stress → durable OPEN/CLOSE intents.
 * <p>
 * Does <strong>not</strong> open channels on the user request path. Runs async,
 * re-uses binary AND gates at enqueue and execution time, costs from SYSTEM_PROFIT.
 */
@Service
public class KfeChannelCapacityController {

    private static final Logger log = LoggerFactory.getLogger(KfeChannelCapacityController.class);

    private final LightningChannelGateway channelGateway;
    private final KfeChannelDecisionService decisionService;
    private final KfeChannelLifecycleService lifecycleService;
    private final KfeChannelCapacityQueueService capacityQueue;
    private final KfeCapacitySignalStore signalStore;
    private final org.springframework.beans.factory.ObjectProvider<KfeLightningOpsMetrics> opsMetrics;

    private final boolean enabled;
    private final boolean autoOpen;
    private final boolean autoCloseDead;
    private final long signalWindowMs;
    private final long liquidityRejectThreshold;
    private final long openAmountSats;
    private final long estimatedOpenFeeSats;
    private final long expectedOpenGainSats;
    private final long assumedFeeRateSatVb;
    private final int inactiveScansBeforeClose;
    private final long maxPendingOpens;
    private final List<String> preferredPeers;

    /** channelPoint → consecutive inactive scan count */
    private final ConcurrentHashMap<String, Integer> inactiveStreak = new ConcurrentHashMap<>();

    public KfeChannelCapacityController(
            LightningChannelGateway channelGateway,
            KfeChannelDecisionService decisionService,
            KfeChannelLifecycleService lifecycleService,
            KfeChannelCapacityQueueService capacityQueue,
            KfeCapacitySignalStore signalStore,
            org.springframework.beans.factory.ObjectProvider<KfeLightningOpsMetrics> opsMetrics,
            @Value("${kfe.channel.capacity.enabled:true}") boolean enabled,
            @Value("${kfe.channel.capacity.auto-open:true}") boolean autoOpen,
            @Value("${kfe.channel.capacity.auto-close-dead:true}") boolean autoCloseDead,
            @Value("${kfe.channel.capacity.signal-window-ms:900000}") long signalWindowMs,
            @Value("${kfe.channel.capacity.liquidity-reject-threshold:5}") long liquidityRejectThreshold,
            @Value("${kfe.channel.capacity.open-amount-sats:0}") long openAmountSats,
            @Value("${kfe.channel.capacity.estimated-open-fee-sats:5000}") long estimatedOpenFeeSats,
            @Value("${kfe.channel.capacity.expected-open-gain-sats:50000}") long expectedOpenGainSats,
            @Value("${kfe.channel.capacity.assumed-fee-rate-sat-vb:10}") long assumedFeeRateSatVb,
            @Value("${kfe.channel.capacity.inactive-scans-before-close:3}") int inactiveScansBeforeClose,
            @Value("${kfe.channel.capacity.max-pending-opens:2}") long maxPendingOpens,
            @Value("${kfe.channel.capacity.preferred-peers:}") String preferredPeersCsv,
            @Value("${kfe.channel.min-open-capital-sats:10000000}") long minOpenCapitalSats) {
        this.channelGateway = channelGateway;
        this.decisionService = decisionService;
        this.lifecycleService = lifecycleService;
        this.capacityQueue = capacityQueue;
        this.signalStore = signalStore;
        this.opsMetrics = opsMetrics;
        this.enabled = enabled;
        this.autoOpen = autoOpen;
        this.autoCloseDead = autoCloseDead;
        this.signalWindowMs = Math.max(60_000L, signalWindowMs);
        this.liquidityRejectThreshold = Math.max(1L, liquidityRejectThreshold);
        this.openAmountSats = openAmountSats > 0L ? openAmountSats : Math.max(1L, minOpenCapitalSats);
        this.estimatedOpenFeeSats = Math.max(0L, estimatedOpenFeeSats);
        this.expectedOpenGainSats = Math.max(0L, expectedOpenGainSats);
        this.assumedFeeRateSatVb = Math.max(1L, assumedFeeRateSatVb);
        this.inactiveScansBeforeClose = Math.max(1, inactiveScansBeforeClose);
        this.maxPendingOpens = Math.max(1L, maxPendingOpens);
        this.preferredPeers = parsePeers(preferredPeersCsv);
    }

    @Scheduled(
            fixedDelayString = "${kfe.channel.capacity.fixed-delay-ms:180000}",
            initialDelayString = "${kfe.channel.capacity.initial-delay-ms:90000}")
    public void scan() {
        if (!enabled) {
            return;
        }
        if (!channelGateway.isLive()) {
            log.debug("[KFE Capacity] LND not live — skip scan");
            return;
        }
        try {
            evaluateCloses();
            evaluateOpens();
        } catch (RuntimeException ex) {
            log.warn("[KFE Capacity] scan failed: {}", ex.getMessage());
        }
    }

    void evaluateOpens() {
        if (!autoOpen) {
            return;
        }
        KfeCapacitySignalStore.CapacitySignals signals = signalStore.snapshot(signalWindowMs);
        if (signals.liquidityRejects() < liquidityRejectThreshold) {
            return;
        }
        if (preferredPeers.isEmpty()) {
            log.info(
                    "[KFE Capacity] liquidity stress rejects={} but no preferred peers configured — cannot auto-open",
                    signals.liquidityRejects());
            recordCapacity("open_skipped", "no_peers");
            return;
        }
        if (capacityQueue.pendingOpenCount() >= maxPendingOpens) {
            log.debug("[KFE Capacity] max pending opens reached ({})", maxPendingOpens);
            return;
        }
        // Max profit policy: never spend structural cost without expected gain.
        if (expectedOpenGainSats <= estimatedOpenFeeSats) {
            log.info(
                    "[KFE Capacity] skip OPEN: expectedGain={} <= estimatedCost={} (profit policy)",
                    expectedOpenGainSats,
                    estimatedOpenFeeSats);
            recordCapacity("open_skipped", "no_profit");
            return;
        }

        for (String peer : preferredPeers) {
            if (alreadyConnectedWithOutbound(peer)) {
                continue;
            }
            ChannelDecisionResult decision = decisionService.evaluateOpen(
                    peer,
                    openAmountSats,
                    assumedFeeRateSatVb,
                    true,
                    "capacity-auto-open:" + peer);
            if (!decision.passed()) {
                log.info(
                        "[KFE Capacity] OPEN gate failed peer={} reason={}",
                        peer.substring(0, Math.min(16, peer.length())),
                        decision.toAuditMap().get("failedFlags"));
                recordCapacity("open_gate_fail", "and");
                continue;
            }
            // Persist full lifecycle decision for forensics, then enqueue.
            KfeChannelDecisionResponse evaluated = lifecycleService.evaluateOpen(
                    new KfeOpenChannelRequest(
                            peer,
                            openAmountSats,
                            assumedFeeRateSatVb,
                            true,
                            true,
                            false));
            if (!evaluated.passed()) {
                continue;
            }
            var job = capacityQueue.enqueueOpenIfAbsent(
                    peer,
                    openAmountSats,
                    estimatedOpenFeeSats,
                    expectedOpenGainSats,
                    "LIQUIDITY_REJECTS:" + signals.liquidityRejects(),
                    evaluated.id());
            if (job.isPresent()) {
                log.info(
                        "[KFE Capacity] OPEN enqueued job={} peer={} amount={} trigger={}",
                        job.get().getId(),
                        peer.substring(0, Math.min(16, peer.length())),
                        openAmountSats,
                        signals.liquidityRejects());
                recordCapacity("open_enqueued", "liquidity_stress");
                return; // one open intent per scan
            }
        }
    }

    void evaluateCloses() {
        if (!autoCloseDead) {
            return;
        }
        for (LightningChannelGateway.ChannelSnapshot channel : channelGateway.listChannels()) {
            if (channel == null || channel.channelPoint() == null) {
                continue;
            }
            String point = channel.channelPoint();
            boolean inactive = !channel.active() && channel.pendingHtlcs() == 0;
            if (!inactive) {
                inactiveStreak.remove(point);
                continue;
            }
            int streak = inactiveStreak.merge(point, 1, Integer::sum);
            if (streak < inactiveScansBeforeClose) {
                continue;
            }
            KfeChannelDecisionResponse evaluated = lifecycleService.evaluateClose(
                    new KfeCloseChannelRequest(point, false, true, assumedFeeRateSatVb));
            if (!evaluated.passed()) {
                log.debug(
                        "[KFE Capacity] CLOSE gate failed channel={} reason={}",
                        point,
                        evaluated.decisionReason());
                continue;
            }
            var job = capacityQueue.enqueueCloseIfAbsent(
                    point,
                    channel.remotePubkey(),
                    estimatedOpenFeeSats,
                    "INACTIVE_STREAK:" + streak,
                    evaluated.id());
            if (job.isPresent()) {
                log.info(
                        "[KFE Capacity] CLOSE enqueued job={} channel={} streak={}",
                        job.get().getId(),
                        point,
                        streak);
                recordCapacity("close_enqueued", "inactive");
                inactiveStreak.remove(point);
            }
        }
    }

    private void recordCapacity(String result, String reason) {
        KfeLightningOpsMetrics m = opsMetrics.getIfAvailable();
        if (m != null) {
            m.recordCapacity(result, reason);
        }
    }

    private boolean alreadyConnectedWithOutbound(String peerPubkey) {
        String normalized = peerPubkey.trim().toLowerCase(Locale.ROOT);
        return channelGateway.listChannels().stream()
                .anyMatch(ch -> ch.remotePubkey() != null
                        && ch.remotePubkey().equalsIgnoreCase(normalized)
                        && ch.active()
                        && ch.localBalanceSats() > 0L);
    }

    private static List<String> parsePeers(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableList());
    }

}
