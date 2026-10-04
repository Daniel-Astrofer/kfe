package com.kerosene.kfe.adapters.out.integration.rail;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import com.kerosene.common.financial.operations.FinancialRailHealthPort;
import com.kerosene.kfe.adapters.out.rail.custody.CustodyGateway;
import com.kerosene.kfe.adapters.out.rail.provider.ExternalRailProviderRegistry;

import java.time.Instant;
import java.util.List;

/** Translates KFE custody and external-rail registry status into the shared financial health contract. */
@Component
public class KfeFinancialRailHealthAdapter implements FinancialRailHealthPort {

    /** Optional custody gateway provider; absence is represented as no custody status. */
    private final ObjectProvider<CustodyGateway> custodyGateway;
    /** Optional external rail registry that reports configured Lightning and other providers. */
    private final ObjectProvider<ExternalRailProviderRegistry> externalRailProviderRegistry;

    /** @param custodyGateway lazy provider for the active custody integration
     *  @param externalRailProviderRegistry lazy provider for active external rail health data
     */
    public KfeFinancialRailHealthAdapter(
            ObjectProvider<CustodyGateway> custodyGateway,
            ObjectProvider<ExternalRailProviderRegistry> externalRailProviderRegistry) {
        this.custodyGateway = custodyGateway;
        this.externalRailProviderRegistry = externalRailProviderRegistry;
    }

    /** @return custody provider health snapshot, or null if absent */
    @Override
    public ProviderHealth custodyProviderHealth() {
        CustodyGateway gateway = custodyGateway.getIfAvailable();
        if (gateway == null) {
            return null;
        }
        boolean live = gateway.isLive();
        return new ProviderHealth(
                gateway.providerName(),
                gateway.getClass().getSimpleName(),
                live ? HealthState.AVAILABLE : HealthState.UNAVAILABLE,
                live, live, live, live,
                "MAINNET",
                0L,
                live ? Instant.now() : null,
                live ? null : "GATEWAY_DOWN");
    }

    /** @return active financial rail provider health snapshots */
    @Override
    public List<ProviderHealth> activeRailProviderHealth() {
        ExternalRailProviderRegistry registry = externalRailProviderRegistry.getIfAvailable();
        if (registry == null) {
            return List.of();
        }
        return registry.activeProviders().values().stream()
                .map(status -> new ProviderHealth(
                        status.providerName(),
                        status.implementation(),
                        status.live() ? HealthState.AVAILABLE : HealthState.UNAVAILABLE,
                        status.live(), status.live(), status.live(), status.live(),
                        "MAINNET",
                        0L,
                        status.live() ? Instant.now() : null,
                        status.live() ? null : "RAIL_UNAVAILABLE"))
                .toList();
    }
}
