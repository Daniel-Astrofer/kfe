package com.kerosene.kfe.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Micrometer counters and gauges for KFE transaction lifecycle observability.
 *
 * <p>Safe when MeterRegistry is absent (tests / minimal profiles).
 */
@Component
public class KfeFinancialMetrics {

    private final MeterRegistry registry;

    private final AtomicLong lockedSats = new AtomicLong(0L);
    private final AtomicLong notificationPending = new AtomicLong(0L);
    private final AtomicLong balanceDivergenceSats = new AtomicLong(0L);
    private final AtomicLong lightningInflightSats = new AtomicLong(0L);
    private final AtomicLong idempotencyInProgress = new AtomicLong(0L);
    private final AtomicLong adapterAuthEnabled = new AtomicLong(1L);
    private final AtomicLong configuredNetwork = new AtomicLong(0L);
    private final AtomicLong runningNetwork = new AtomicLong(0L);
    private final AtomicLong stuckSinceEpochSeconds = new AtomicLong(0L);

    public KfeFinancialMetrics(ObjectProvider<MeterRegistry> registry) {
        this.registry = registry.getIfAvailable();
        if (this.registry != null) {
            registerGauges();
        }
    }

    private void registerGauges() {
        Gauge.builder("kfe.locked.sats", lockedSats, AtomicLong::get)
                .description("Total locked satoshis across all transactions")
                .register(registry);
        Gauge.builder("kfe.notification.pending", notificationPending, AtomicLong::get)
                .description("Pending notification outbox entries")
                .register(registry);
        Gauge.builder("kfe.balance.divergence_sats", balanceDivergenceSats, AtomicLong::get)
                .description("Absolute ledger vs chain balance divergence in satoshis")
                .register(registry);
        Gauge.builder("kfe.lightning.inflight_sats", lightningInflightSats, AtomicLong::get)
                .description("In-flight Lightning payment total in satoshis")
                .register(registry);
        Gauge.builder("kfe.idempotency.in_progress", idempotencyInProgress, AtomicLong::get)
                .description("In-flight idempotency claims")
                .register(registry);
        Gauge.builder("kfe.adapter.auth_enabled", adapterAuthEnabled, AtomicLong::get)
                .description("1 if adapter auth is enabled, 0 otherwise")
                .register(registry);
        Gauge.builder("kfe.configured_network", configuredNetwork, AtomicLong::get)
                .description("Configured Bitcoin network (0=mainnet, 1=testnet, 2=regtest)")
                .register(registry);
        Gauge.builder("kfe.running_network", runningNetwork, AtomicLong::get)
                .description("Running Bitcoin network (0=mainnet, 1=testnet, 2=regtest)")
                .register(registry);
        Gauge.builder("kfe.transactions.stuck_since_seconds", stuckSinceEpochSeconds, AtomicLong::get)
                .description("Unix epoch seconds since oldest stuck transaction entered current status")
                .register(registry);
    }

    // --- Transaction lifecycle counters ---

    public void recordTransaction(String rail, String direction, String status) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.transactions.total")
                .description("Transaction lifecycle counter per rail, direction, and status")
                .tags(Tags.of(
                        "rail", rail != null ? rail : "UNKNOWN",
                        "direction", direction != null ? direction : "UNKNOWN",
                        "status", status != null ? status : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    public void recordReconciliationRequired(String rail) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.reconciliation.required")
                .description("Events requiring manual financial reconciliation")
                .tags(Tags.of("rail", rail != null ? rail : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    public void recordConflicted(String rail) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.conflicted")
                .description("Conflicted transaction events")
                .tags(Tags.of("rail", rail != null ? rail : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    public void recordReorg(String rail) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.reorg")
                .description("Blockchain reorg events detected")
                .tags(Tags.of("rail", rail != null ? rail : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    public void recordNetworkNotFound(String rail) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.network.not_found")
                .description("Transaction not found by Bitcoin Core")
                .tags(Tags.of("rail", rail != null ? rail : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    // --- Idempotency metrics ---

    public void recordIdempotencyConflict() {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.idempotency.conflict")
                .description("Idempotency key conflicts detected")
                .register(registry)
                .increment();
    }

    // --- Notification outbox metrics ---

    public void recordNotificationDeadLetter(String eventType) {
        if (registry == null) {
            return;
        }
        Counter.builder("kfe.notification.dead_letter")
                .description("Dead-letter notification outbox entries")
                .tags(Tags.of("eventType", eventType != null ? eventType : "UNKNOWN"))
                .register(registry)
                .increment();
    }

    // --- Gauge setters ---

    public void setLockedSats(long sats) {
        lockedSats.set(Math.max(0L, sats));
    }

    public void setNotificationPending(long count) {
        notificationPending.set(Math.max(0L, count));
    }

    public void setBalanceDivergenceSats(long sats) {
        balanceDivergenceSats.set(sats);
    }

    public void setLightningInflightSats(long sats) {
        lightningInflightSats.set(Math.max(0L, sats));
    }

    public void setIdempotencyInProgress(long count) {
        idempotencyInProgress.set(Math.max(0L, count));
    }

    public void setAdapterAuthEnabled(boolean enabled) {
        adapterAuthEnabled.set(enabled ? 1L : 0L);
    }

    public void setConfiguredNetwork(int networkId) {
        configuredNetwork.set(networkId);
    }

    public void setRunningNetwork(int networkId) {
        runningNetwork.set(networkId);
    }

    public void setStuckSinceEpochSeconds(long epochSeconds) {
        stuckSinceEpochSeconds.set(Math.max(0L, epochSeconds));
    }
}
