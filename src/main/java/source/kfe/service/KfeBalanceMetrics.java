package source.kfe.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import source.kfe.model.KfeWalletKind;

/**
 * Micrometer counters/gauges for balance consistency (PR3).
 * Safe when MeterRegistry is absent (tests / minimal profiles).
 */
@Component
public class KfeBalanceMetrics {

    private final MeterRegistry registry;

    public KfeBalanceMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
    }

    public void recordProbe(ProbeQuality quality, String result, KfeWalletKind kind) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.cold.probe")
                .description("Cold/on-chain observed probe outcomes")
                .tags(Tags.of(
                        "quality", quality != null ? quality.name() : "UNKNOWN",
                        "result", result != null ? result : "unknown",
                        "kind", kind != null ? kind.name() : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    public void recordMempoolFiltered(int outpointCount) {
        if (registry == null || outpointCount <= 0) {
            return;
        }
        Counter.builder("kfe.cold.mempool_filtered_outpoints")
                .description("Outpoints dropped by mempool spend filter")
                .register(registry)
                .increment(outpointCount);
    }

    public void recordDrift(KfeWalletKind kind, long absDriftSats) {
        if (registry == null || absDriftSats <= 0L) {
            return;
        }
        Counter.builder("kfe.balance.drift_events")
                .description("Custodial dual-ledger drift detections above threshold")
                .tags(Tags.of("kind", kind != null ? kind.name() : "UNKNOWN"))
                .register(registry)
                .increment();
        // Distribution-style counter of absolute drift magnitude (sats).
        Counter.builder("kfe.balance.drift_sats_total")
                .tags(Tags.of("kind", kind != null ? kind.name() : "UNKNOWN"))
                .register(registry)
                .increment(absDriftSats);
    }

    public void recordWsPublish(String bucket) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.ws.balance_publish")
                .tags(Tags.of("bucket", bucket != null ? bucket : "PRIMARY"))
                .register(registry)
                .increment();
    }

    public void recordLockedStuck() {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.balance.locked_stuck")
                .description("Wallets with locked funds and long-running EXECUTING/RECON txs")
                .register(registry)
                .increment();
    }

    /** Dual-path credit skipped (movement already exists / race lost). */
    public void recordDualCreditSkip(String path) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.credit.dual_skip")
                .description("Available credit skipped because another path already settled the tx")
                .tags(Tags.of("path", path != null ? path : "unknown"))
                .register(registry)
                .increment();
    }

    public void recordFeeIdempotentSkip() {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.fee.skip_idempotent")
                .description("Kerosene fee credit skipped (already settled)")
                .register(registry)
                .increment();
    }

    public void recordOptimisticDeferred(String reason) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.zmq.optimistic_deferred")
                .description("ZMQ/optimistic observed write deferred by quality policy")
                .tags(Tags.of("reason", reason != null ? reason : "unknown"))
                .register(registry)
                .increment();
    }

    public void recordProbeMonotonicDefer(String reason) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.cold.probe_monotonic_defer")
                .description("Observed write deferred to protect fresher/higher-quality probe")
                .tags(Tags.of("reason", reason != null ? reason : "unknown"))
                .register(registry)
                .increment();
    }
}
