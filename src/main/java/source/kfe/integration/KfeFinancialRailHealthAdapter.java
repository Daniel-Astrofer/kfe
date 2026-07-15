package source.kfe.integration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import source.common.financial.FinancialRailHealthPort;
import source.kfe.rail.CustodyGateway;
import source.kfe.rail.ExternalRailProviderRegistry;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class KfeFinancialRailHealthAdapter implements FinancialRailHealthPort {

    private final ObjectProvider<CustodyGateway> custodyGateway;
    private final ObjectProvider<ExternalRailProviderRegistry> externalRailProviderRegistry;

    public KfeFinancialRailHealthAdapter(
            ObjectProvider<CustodyGateway> custodyGateway,
            ObjectProvider<ExternalRailProviderRegistry> externalRailProviderRegistry) {
        this.custodyGateway = custodyGateway;
        this.externalRailProviderRegistry = externalRailProviderRegistry;
    }

    @Override
    public ProviderStatus custodyProvider() {
        CustodyGateway gateway = custodyGateway.getIfAvailable();
        if (gateway == null) {
            return null;
        }
        return new ProviderStatus(gateway.providerName(), gateway.isLive(), gateway.getClass().getSimpleName());
    }

    @Override
    public Map<String, ProviderStatus> activeRailProviders() {
        ExternalRailProviderRegistry registry = externalRailProviderRegistry.getIfAvailable();
        if (registry == null) {
            return Map.of();
        }
        Map<String, ProviderStatus> providers = new LinkedHashMap<>();
        registry.activeProviders().forEach((key, status) -> providers.put(
                key,
                new ProviderStatus(status.providerName(), status.live(), status.implementation())));
        return Map.copyOf(providers);
    }
}
