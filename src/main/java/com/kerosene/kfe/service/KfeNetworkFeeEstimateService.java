package com.kerosene.kfe.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import com.kerosene.kfe.dto.KfeFeeQuoteResponse;
import com.kerosene.kfe.dto.KfeFeeTierResponse;
import com.kerosene.kfe.model.KfeDirection;
import com.kerosene.kfe.model.KfeRail;
import com.kerosene.kfe.rail.BitcoinCoreRpcClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class KfeNetworkFeeEstimateService {

    static final String BITCOIN_CORE_SOURCE = "BITCOIN_CORE";
    static final String FALLBACK_SOURCE = "CONFIGURED_FALLBACK";
    static final String NOT_APPLICABLE_SOURCE = "NOT_APPLICABLE";
    static final String CLIENT_LIMIT_SOURCE = "CLIENT_LIMIT";
    static final int FEE_ESTIMATE_VERSION = 1;

    private final ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider;
    private final KfePricingService pricingService;
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
    private final byte[] signingSecretBytes;
    private final Map<String, KfeFeeQuoteResponse> quoteStore = new ConcurrentHashMap<>();

    public KfeNetworkFeeEstimateService(
            ObjectProvider<BitcoinCoreRpcClient> bitcoinCoreProvider,
            KfePricingService pricingService,
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
            @Value("${bitcoin.network:mainnet}") String bitcoinNetwork,
            @Value("${kfe.fee-quote.signing-secret:}") String signingSecret) {
        this.bitcoinCoreProvider = bitcoinCoreProvider;
        this.pricingService = pricingService;
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
        this.signingSecretBytes = resolveSigningSecret(signingSecret);
    }

    private static byte[] resolveSigningSecret(String signingSecret) {
        if (signingSecret == null || signingSecret.isBlank()) {
            throw new IllegalArgumentException("kfe.fee-quote.signing-secret must be configured.");
        }
        return signingSecret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Generates a persistent, signed quote binding the estimated fees to a specific
     * transaction intent. The quote expires after the configured quote TTL.
     */
    public KfeFeeQuoteResponse quote(
            KfeRail rail,
            KfeDirection direction,
            long amountSats,
            long requestedNetworkFeeSats,
            String userId,
            String walletId,
            String destinationHash) {
        Estimate estimate = estimate(rail, direction, requestedNetworkFeeSats);
        KfePricingService.Quote pricingQuote = pricingService.quote(rail, direction, amountSats, estimate.selectedNetworkFeeSats());

        KfeFeeQuoteResponse quote = new KfeFeeQuoteResponse();
        quote.setQuoteId(UUID.randomUUID().toString());
        quote.setUserId(userId);
        quote.setWalletId(walletId);
        quote.setDestinationHash(destinationHash);
        quote.setRail(rail.name());
        quote.setAmount(BigDecimal.valueOf(amountSats));
        quote.setNetworkFeeSat(estimate.selectedNetworkFeeSats());
        quote.setServiceFeeSat(pricingQuote.keroseneFeeSats());
        quote.setTotalDebitSat(pricingQuote.totalDebitSats());
        quote.setPricingPolicyVersion(pricingQuote.pricingPolicyVersion());
        quote.setFeeEstimateVersion(FEE_ESTIMATE_VERSION);
        quote.setExpiresAt(Instant.now().plusSeconds(quoteTtlSeconds));
        quote.setSignature(signQuote(quote));

        quoteStore.put(quote.getQuoteId(), quote);
        return quote;
    }

    /**
     * Validates a previously generated quote. Returns the quote if it is valid,
     * throws an exception otherwise.
     */
    public KfeFeeQuoteResponse validateQuote(
            String quoteId,
            long amountSats,
            String destinationHash,
            String rail) {
        if (quoteId == null || quoteId.isBlank()) {
            throw new IllegalArgumentException("quoteId is required for quote validation.");
        }

        KfeFeeQuoteResponse quote = quoteStore.get(quoteId);
        if (quote == null) {
            throw new IllegalArgumentException("Quote not found: " + quoteId);
        }

        if (quote.getExpiresAt() != null && Instant.now().isAfter(quote.getExpiresAt())) {
            quoteStore.remove(quoteId);
            throw new IllegalArgumentException("Quote expired: " + quoteId);
        }

        if (quote.getAmount() == null || quote.getAmount().longValue() != amountSats) {
            throw new IllegalArgumentException("Quote amount mismatch for " + quoteId);
        }

        if (!safeEquals(quote.getDestinationHash(), destinationHash)) {
            throw new IllegalArgumentException("Quote destination mismatch for " + quoteId);
        }

        if (!safeEquals(quote.getRail(), rail)) {
            throw new IllegalArgumentException("Quote rail mismatch for " + quoteId);
        }

        String expectedSignature = signQuote(quote);
        if (!safeEquals(expectedSignature, quote.getSignature())) {
            throw new IllegalArgumentException("Quote signature invalid for " + quoteId);
        }

        return quote;
    }

    private String signQuote(KfeFeeQuoteResponse quote) {
        String payload = String.join("|",
                quote.getQuoteId() != null ? quote.getQuoteId() : "",
                quote.getUserId() != null ? quote.getUserId() : "",
                quote.getWalletId() != null ? quote.getWalletId() : "",
                quote.getDestinationHash() != null ? quote.getDestinationHash() : "",
                quote.getRail() != null ? quote.getRail() : "",
                quote.getAmount() != null ? quote.getAmount().toPlainString() : "",
                quote.getNetworkFeeSat() != null ? String.valueOf(quote.getNetworkFeeSat()) : "",
                quote.getServiceFeeSat() != null ? String.valueOf(quote.getServiceFeeSat()) : "",
                quote.getTotalDebitSat() != null ? String.valueOf(quote.getTotalDebitSat()) : "",
                String.valueOf(quote.getPricingPolicyVersion()),
                String.valueOf(quote.getFeeEstimateVersion()),
                quote.getExpiresAt() != null ? quote.getExpiresAt().toString() : "");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecretBytes, "HmacSHA256"));
            byte[] hmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign fee quote.", e);
        }
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null) {
            return b == null;
        }
        return a.equals(b);
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
