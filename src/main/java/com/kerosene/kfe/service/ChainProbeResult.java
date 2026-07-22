package source.kfe.service;

/**
 * Result of probing on-chain balance for a wallet. Carries quality metadata so writers
 * can refuse low-quality absolute updates (partial scans, mempool-blind totals, zero spikes).
 */
public record ChainProbeResult(
        long sats,
        ProbeQuality quality,
        boolean authoritative,
        int outpointCount,
        String source) {

    public ChainProbeResult {
        if (sats < 0L) {
            throw new IllegalArgumentException("probe sats must be >= 0");
        }
        if (quality == null) {
            quality = ProbeQuality.UNKNOWN;
        }
        if (outpointCount < 0) {
            outpointCount = 0;
        }
        if (source == null || source.isBlank()) {
            source = "unknown";
        }
    }

    public static ChainProbeResult liveMempoolAware(long sats, int outpointCount, String source) {
        return new ChainProbeResult(sats, ProbeQuality.LIVE_MEMPOOL_AWARE, true, outpointCount, source);
    }

    public static ChainProbeResult confirmedUtxoSet(long sats, int outpointCount, String source) {
        return new ChainProbeResult(sats, ProbeQuality.CONFIRMED_UTXO_SET, false, outpointCount, source);
    }

    public static ChainProbeResult optimisticDelta(long sats, String source) {
        return new ChainProbeResult(sats, ProbeQuality.OPTIMISTIC_DELTA, false, 0, source);
    }

    public static ChainProbeResult unknown(String source) {
        return new ChainProbeResult(0L, ProbeQuality.UNKNOWN, false, 0, source);
    }
}
