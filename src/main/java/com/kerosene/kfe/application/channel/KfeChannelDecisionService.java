package com.kerosene.kfe.application.channel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.model.KfeChannelOperationType;
import com.kerosene.kfe.rail.LightningChannelGateway;
import com.kerosene.kfe.service.KfeLightningJammingGuard;
import com.kerosene.kfe.service.KfeQuorumGateway;
import com.kerosene.kfe.service.KfeSystemWalletService;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Strict binary AND decisions for channel structural actions (doc §3).
 * Does not execute LND mutations — only evaluates flags.
 */
@Service
public class KfeChannelDecisionService {

    private final KfeQuorumGateway quorumGateway;
    private final KfeSystemWalletService systemWalletService;
    private final KfeLightningJammingGuard jammingGuard;
    private final long minOpenCapitalSats;
    private final long maxOnchainFeeRateSatVb;
    private final double rebalanceDrainRatio;
    private final long drainDeterrentPpm;
    private final long breakEvenPpm;
    private final Set<String> peerDenylist;
    private final boolean requireMpcForStructural;

    public KfeChannelDecisionService(
            KfeQuorumGateway quorumGateway,
            KfeSystemWalletService systemWalletService,
            KfeLightningJammingGuard jammingGuard,
            @Value("${kfe.channel.min-open-capital-sats:10000000}") long minOpenCapitalSats,
            @Value("${kfe.channel.max-onchain-fee-rate-sat-vb:50}") long maxOnchainFeeRateSatVb,
            @Value("${kfe.channel.rebalance-drain-ratio:0.20}") double rebalanceDrainRatio,
            @Value("${kfe.channel.drain-deterrent-ppm:5000}") long drainDeterrentPpm,
            @Value("${kfe.channel.break-even-ppm:1000}") long breakEvenPpm,
            @Value("${kfe.lightning.peer-denylist:}") String peerDenylistCsv,
            @Value("${kfe.channel.require-mpc:true}") boolean requireMpcForStructural) {
        this.quorumGateway = quorumGateway;
        this.systemWalletService = systemWalletService;
        this.jammingGuard = jammingGuard;
        this.minOpenCapitalSats = Math.max(0L, minOpenCapitalSats);
        this.maxOnchainFeeRateSatVb = Math.max(1L, maxOnchainFeeRateSatVb);
        this.rebalanceDrainRatio = Math.min(1.0d, Math.max(0.0d, rebalanceDrainRatio));
        this.drainDeterrentPpm = Math.max(0L, drainDeterrentPpm);
        this.breakEvenPpm = Math.max(0L, breakEvenPpm);
        this.peerDenylist = parseDenylist(peerDenylistCsv);
        this.requireMpcForStructural = requireMpcForStructural;
    }

    public ChannelDecisionResult evaluateOpen(
            String peerPubkey,
            long localAmountSats,
            long estimatedFeeRateSatVb,
            boolean anchorsEnabled,
            String proposalHash) {
        List<ChannelFlagEvaluation> flags = new ArrayList<>();
        flags.add(localAmountSats >= minOpenCapitalSats
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_CAPITAL_MINIMO, "CAPITAL_OK:" + localAmountSats)
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_CAPITAL_MINIMO,
                        "CAPITAL_BELOW_MIN:" + localAmountSats + "<" + minOpenCapitalSats));
        flags.add(estimatedFeeRateSatVb > 0 && estimatedFeeRateSatVb <= maxOnchainFeeRateSatVb
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_TAXA_ONCHAIN_BAIXA, "FEE_RATE_OK:" + estimatedFeeRateSatVb)
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_TAXA_ONCHAIN_BAIXA,
                        "FEE_RATE_HIGH_OR_UNKNOWN:" + estimatedFeeRateSatVb));
        flags.add(anchorsEnabled
                ? ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_SAIDA_ANCORA, "ANCHORS_ENABLED")
                : ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_SAIDA_ANCORA, "ANCHORS_REQUIRED"));
        flags.add(evaluateMpc(proposalHash));
        flags.add(evaluatePeerNotDenylisted(peerPubkey));
        return new ChannelDecisionResult(KfeChannelOperationType.OPEN, flags);
    }

    public ChannelDecisionResult evaluateRebalance(
            LightningChannelGateway.ChannelSnapshot channel,
            long estimatedRebalanceCostSats,
            long expectedFutureGainSats) {
        List<ChannelFlagEvaluation> flags = new ArrayList<>();
        double localRatio = channel != null ? channel.localRatio() : 1.0d;
        flags.add(localRatio < rebalanceDrainRatio
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_LIMIAR_DRENAGEM,
                        "DRAINED:" + formatRatio(localRatio))
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_LIMIAR_DRENAGEM,
                        "NOT_DRAINED:" + formatRatio(localRatio)));
        long net = expectedFutureGainSats - estimatedRebalanceCostSats;
        flags.add(net > 0L
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_LUCRO_MATEMATICO, "NET_POSITIVE:" + net)
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_LUCRO_MATEMATICO, "NET_NON_POSITIVE:" + net));
        flags.add(evaluateProfitFund());
        return new ChannelDecisionResult(KfeChannelOperationType.REBALANCE, flags);
    }

    public ChannelDecisionResult evaluateClose(
            LightningChannelGateway.ChannelSnapshot channel,
            boolean peerOfflineBeyondThreshold,
            boolean cooperativePossible,
            boolean anchorsOk,
            long currentFeeRateSatVb) {
        List<ChannelFlagEvaluation> flags = new ArrayList<>();
        boolean dead = peerOfflineBeyondThreshold
                || (channel != null && !channel.active() && channel.pendingHtlcs() == 0);
        flags.add(dead
                ? ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_CANAL_MORTO, "CHANNEL_DEAD_OR_OFFLINE")
                : ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_CANAL_MORTO, "CHANNEL_STILL_VIABLE"));
        boolean safeClose = cooperativePossible
                || (anchorsOk && currentFeeRateSatVb > 0 && currentFeeRateSatVb <= maxOnchainFeeRateSatVb * 3);
        flags.add(safeClose
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_SEGURANCA_DE_FECHAMENTO, "CLOSE_SAFE")
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_SEGURANCA_DE_FECHAMENTO, "CLOSE_UNSAFE_FORCE_FEE"));
        return new ChannelDecisionResult(KfeChannelOperationType.CLOSE, flags);
    }

    public ChannelDecisionResult evaluatePpm(long currentPpm, boolean acceleratedDrain) {
        List<ChannelFlagEvaluation> flags = new ArrayList<>();
        flags.add(currentPpm <= breakEvenPpm
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_PPM_BELOW_BREAKEVEN, "PPM_BELOW_BE:" + currentPpm)
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_PPM_BELOW_BREAKEVEN, "PPM_ABOVE_BE:" + currentPpm));
        flags.add(acceleratedDrain
                ? ChannelFlagEvaluation.pass(
                        ChannelDecisionFlag.V_PPM_DRAIN_ALERT, "DRAIN_ALERT")
                : ChannelFlagEvaluation.fail(
                        ChannelDecisionFlag.V_PPM_DRAIN_ALERT, "NO_DRAIN_ALERT"));
        // PPM adjust is OR of the two product rules in doc — we still return both flags;
        // lifecycle treats pass if either actionable condition holds.
        boolean actionable = currentPpm <= breakEvenPpm || acceleratedDrain;
        if (!actionable) {
            return new ChannelDecisionResult(KfeChannelOperationType.PPM_ADJUST, flags);
        }
        // Force AND product to 1 when any action rule matches by padding a synthetic pass set:
        // recompute as explicit pass list when actionable.
        List<ChannelFlagEvaluation> actionableFlags = new ArrayList<>();
        if (currentPpm <= breakEvenPpm) {
            actionableFlags.add(ChannelFlagEvaluation.pass(
                    ChannelDecisionFlag.V_PPM_BELOW_BREAKEVEN, "PPM_BELOW_BE:" + currentPpm));
        } else {
            actionableFlags.add(ChannelFlagEvaluation.pass(
                    ChannelDecisionFlag.V_PPM_BELOW_BREAKEVEN, "NOT_REQUIRED"));
        }
        if (acceleratedDrain) {
            actionableFlags.add(ChannelFlagEvaluation.pass(
                    ChannelDecisionFlag.V_PPM_DRAIN_ALERT, "DRAIN_ALERT:" + drainDeterrentPpm));
        } else {
            actionableFlags.add(ChannelFlagEvaluation.pass(
                    ChannelDecisionFlag.V_PPM_DRAIN_ALERT, "NOT_REQUIRED"));
        }
        return new ChannelDecisionResult(KfeChannelOperationType.PPM_ADJUST, actionableFlags);
    }

    public long recommendedPpm(long currentPpm, boolean acceleratedDrain) {
        if (acceleratedDrain) {
            return Math.max(currentPpm, drainDeterrentPpm);
        }
        if (currentPpm <= breakEvenPpm) {
            return Math.min(drainDeterrentPpm, Math.max(breakEvenPpm + 1, currentPpm + breakEvenPpm / 4));
        }
        return currentPpm;
    }

    private ChannelFlagEvaluation evaluateMpc(String proposalHash) {
        if (!requireMpcForStructural) {
            return ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_AUTORIZACAO_MPC, "MPC_NOT_REQUIRED");
        }
        if (proposalHash == null || proposalHash.isBlank()) {
            return ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_AUTORIZACAO_MPC, "MISSING_PROPOSAL_HASH");
        }
        try {
            var quorum = quorumGateway.requireHealthyUnanimousConsensus(proposalHash);
            if (quorum.acceptedNodes() > 0 && quorum.acceptedNodes() == quorum.totalHealthyNodes()) {
                return ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_AUTORIZACAO_MPC, "QUORUM_UNANIMOUS");
            }
            return ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_AUTORIZACAO_MPC, "QUORUM_NOT_UNANIMOUS");
        } catch (RuntimeException ex) {
            return ChannelFlagEvaluation.fail(
                    ChannelDecisionFlag.V_AUTORIZACAO_MPC,
                    "QUORUM_REJECTED:" + safe(ex));
        }
    }

    private ChannelFlagEvaluation evaluatePeerNotDenylisted(String peerPubkey) {
        if (peerPubkey == null || peerPubkey.isBlank()) {
            return ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_DENYLIST_PEER, "PEER_MISSING");
        }
        String normalized = peerPubkey.trim().toLowerCase(Locale.ROOT);
        if (peerDenylist.contains(normalized)) {
            return ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_DENYLIST_PEER, "PEER_DENYLISTED");
        }
        // Soft check: jamming guard may also block denylisted peers with pending HTLCs.
        var jam = jammingGuard.evaluate();
        if (!jam.allowed() && jam.reason() != null && jam.reason().contains("DENYLIST")) {
            return ChannelFlagEvaluation.fail(ChannelDecisionFlag.V_DENYLIST_PEER, jam.reason());
        }
        return ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_DENYLIST_PEER, "PEER_OK");
    }

    private ChannelFlagEvaluation evaluateProfitFund() {
        try {
            systemWalletService.requireProfitWalletId();
            return ChannelFlagEvaluation.pass(ChannelDecisionFlag.V_FUNDO_CORRETO, "PROFIT_WALLET_READY");
        } catch (RuntimeException ex) {
            return ChannelFlagEvaluation.fail(
                    ChannelDecisionFlag.V_FUNDO_CORRETO, "PROFIT_WALLET_MISSING:" + safe(ex));
        }
    }

    private static String formatRatio(double ratio) {
        return String.format(Locale.ROOT, "%.4f", ratio);
    }

    private static String safe(Throwable ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 100 ? message.substring(0, 100) : message;
    }

    private static Set<String> parseDenylist(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(v -> !v.isEmpty())
                .map(v -> v.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
