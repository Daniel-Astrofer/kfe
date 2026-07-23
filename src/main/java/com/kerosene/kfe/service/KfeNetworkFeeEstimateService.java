package com.kerosene.kfe.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.dto.KfeFeeTierResponse;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;

import java.time.Instant;
import java.util.List;

@Service
public class KfeNetworkFeeEstimateService {

    static final String BITCOIN_CORE_SOURCE = "BITCOIN_CORE";
    static final String FALLBACK_SOURCE = "CONFIGURED_FALLBACK";
    static final String NOT_APPLICABLE_SOURCE = "NOT_APPLICABLE";
    static final String CLIENT_LIMIT_SOURCE = "CLIENT_LIMIT";

    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider;
    private final int estimatedVbytes;
    private final double safetyMargin;
    private final int fastTargetBlocks;
    private final int standardTargetBlocks;
    private final int slowTargetBlocks;
    private final long fallbackFastRate;
    private final long fallbackStandardRate;
    private final long fallbackSlowRate;
    private final long expectedBlockSeconds;
    private final long quoteTtlSeconds;
    private final String bitcoinNetwork;

    public KfeNetworkFeeEstimateService(
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider,
            @Value("${kfe.fee-estimate.estimated-vbytes:250}") int estimatedVbytes,
            @Value("${kfe.fee-estimate.safety-margin:1.5}") double safetyMargin,
            @Value("${kfe.fee-estimate.fast-target-blocks:2}") int fastTargetBlocks,
            @Value("${kfe.fee-estimate.standard-target-blocks:3}") int standardTargetBlocks,
            @Value("${kfe.fee-estimate.slow-target-blocks:6}") int slowTargetBlocks,
            @Value("${kfe.fee-estimate.fallback-fast-sat-vbyte:25}") long fallbackFastRate,
            @Value("${kfe.fee-estimate.fallback-standard-sat-vbyte:12}") long fallbackStandardRate,
            @Value("${kfe.fee-estimate.fallback-slow-sat-vbyte:6}") long fallbackSlowRate,
            @Value("${kfe.fee-estimate.expected-block-seconds:600}") long expectedBlockSeconds,
            @Value("${kfe.fee-estimate.quote-ttl-seconds:120}") long quoteTtlSeconds,
            @Value("${bitcoin.network:mainnet}") String bitcoinNetwork) {
        this.bitcoinCoreProvider = bitcoinCoreProvider;
        this.estimatedVbytes = positive(estimatedVbytes, "estimatedVbytes");
        if (safetyMargin < 1.0d || !Double.isFinite(safetyMargin)) {
            throw new IllegalArgumentException("safetyMargin must be >= 1.0");
        }
        this.safetyMargin = safetyMargin;
        this.fastTargetBlocks = positive(fastTargetBlocks, "fastTargetBlocks");
        this.standardTargetBlocks = positive(standardTargetBlocks, "standardTargetBlocks");
        this.slowTargetBlocks = positive(slowTargetBlocks, "slowTargetBlocks");
        this.fallbackFastRate = positive(fallbackFastRate, "fallbackFastRate");
        this.fallbackStandardRate = positive(fallbackStandardRate, "fallbackStandardRate");
        this.fallbackSlowRate = positive(fallbackSlowRate, "fallbackSlowRate");
        this.expectedBlockSeconds = positive(expectedBlockSeconds, "expectedBlockSeconds");
        this.quoteTtlSeconds = positive(quoteTtlSeconds, "quoteTtlSeconds");
        this.bitcoinNetwork = bitcoinNetwork != null ? bitcoinNetwork.trim() : "mainnet";
    }

    public Estimate estimate(
            KfeRail rail,
            KfeDirection direction,
            long requestedNetworkFeeSats) {
        Instant expiresAt = Instant.now().plusSeconds(quoteTtlSeconds);
        if (rail == KfeRail.INTERNAL || direction == KfeDirection.INTERNAL) {
            return new Estimate(0L, 0L, 0, 0L, NOT_APPLICABLE_SOURCE, estimatedVbytes, expiresAt, List.of());
        }
        if (rail != KfeRail.ONCHAIN || direction != KfeDirection.OUTBOUND) {
            return new Estimate(
                    requestedNetworkFeeSats,
                    0L,
                    0,
                    0L,
                    requestedNetworkFeeSats > 0L ? CLIENT_LIMIT_SOURCE : NOT_APPLICABLE_SOURCE,
                    estimatedVbytes,
                    expiresAt,
                    List.of());
        }

        Rate fast = rate(fastTargetBlocks, fallbackFastRate);
        Rate standard = rate(standardTargetBlocks, fallbackStandardRate);
        Rate slow = rate(slowTargetBlocks, fallbackSlowRate);

        long slowRate = slow.satPerVbyte();
        long standardRate = Math.max(standard.satPerVbyte(), slowRate);
        long fastRate = Math.max(fast.satPerVbyte(), standardRate);

        KfeFeeTierResponse fastTier = tier("FAST", fastRate, fastTargetBlocks, fast.source());
        KfeFeeTierResponse standardTier = tier(
                "STANDARD", standardRate, standardTargetBlocks, standard.source());
        KfeFeeTierResponse slowTier = tier("SLOW", slowRate, slowTargetBlocks, slow.source());

        return new Estimate(
                standardTier.networkFeeSats(),
                standardTier.feeRateSatPerVbyte(),
                standardTier.targetBlocks(),
                standardTier.estimatedSeconds(),
                standardTier.source(),
                estimatedVbytes,
                expiresAt,
                List.of(fastTier, standardTier, slowTier));
    }

    /**
     * Minimum network fee to reserve so {@code walletcreatefundedpsbt} is unlikely to exceed the
     * PSBT fee cap. Prefer the client's selected rate when present.
     */
    public long reservedFeeFloorSats(Long feeRateSatPerVbyte, Integer targetBlocks) {
        long rate;
        if (feeRateSatPerVbyte != null && feeRateSatPerVbyte > 0L) {
            rate = feeRateSatPerVbyte;
        } else {
            int blocks = targetBlocks != null && targetBlocks > 0 ? targetBlocks : standardTargetBlocks;
            long fallback = blocks <= fastTargetBlocks
                    ? fallbackFastRate
                    : blocks >= slowTargetBlocks ? fallbackSlowRate : fallbackStandardRate;
            rate = rate(blocks, fallback).satPerVbyte();
        }
        return feeSatsForRate(rate);
    }

    private Rate rate(int targetBlocks, long fallbackRate) {
        BitcoinCoreRpcClient bitcoinCore = bitcoinCoreProvider.getIfAvailable();
        if (bitcoinCore == null) {
            return new Rate(fallbackRate, FALLBACK_SOURCE);
        }
        try {
            return new Rate(bitcoinCore.estimateSmartFeeRateSatPerVbyte(targetBlocks), BITCOIN_CORE_SOURCE);
        } catch (RuntimeException exception) {
            return new Rate(fallbackRate, FALLBACK_SOURCE);
        }
    }

    private KfeFeeTierResponse tier(String priority, long rate, int targetBlocks, String source) {
        long networkFeeSats = feeSatsForRate(rate);
        // Testnet/regtest block times are irregular — use a more conservative block interval
        // so the UI does not promise mainnet-like ~10 min blocks.
        long blockSeconds = isNonMainnet(bitcoinNetwork)
                ? Math.max(expectedBlockSeconds, 1_200L)
                : expectedBlockSeconds;
        long estimatedSeconds = Math.multiplyExact((long) targetBlocks, blockSeconds);
        return new KfeFeeTierResponse(
                priority,
                rate,
                networkFeeSats,
                targetBlocks,
                estimatedSeconds,
                source);
    }

    private long feeSatsForRate(long rateSatPerVbyte) {
        long safeRate = Math.max(1L, rateSatPerVbyte);
        long base = Math.multiplyExact(safeRate, estimatedVbytes);
        if (safetyMargin <= 1.0d) {
            return base;
        }
        // ceil(base * margin) without floating overflow for large values
        double scaled = base * safetyMargin;
        if (scaled >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return Math.max(base, (long) Math.ceil(scaled));
    }

    private static boolean isNonMainnet(String network) {
        if (network == null || network.isBlank()) {
            return false;
        }
        String n = network.trim().toLowerCase();
        return n.contains("test") || n.contains("regtest") || n.contains("signet");
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private static long positive(long value, String name) {
        if (value <= 0L) {
            throw new IllegalArgumentException(name + " must be positive.");
        }
        return value;
    }

    private record Rate(long satPerVbyte, String source) {
    }

    public record Estimate(
            long selectedNetworkFeeSats,
            long selectedFeeRateSatPerVbyte,
            int selectedTargetBlocks,
            long selectedEstimatedSeconds,
            String selectedSource,
            int estimatedVbytes,
            Instant expiresAt,
            List<KfeFeeTierResponse> tiers) {
    }
}
