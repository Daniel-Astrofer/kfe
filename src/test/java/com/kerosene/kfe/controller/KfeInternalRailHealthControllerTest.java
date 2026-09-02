package com.kerosene.kfe.controller;

import org.junit.jupiter.api.Test;
import com.kerosene.common.financial.FinancialRailHealthPort;
import com.kerosene.kfe.integration.KfeFinancialRailHealthAdapter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeInternalRailHealthControllerTest {

    private final KfeFinancialRailHealthAdapter adapter = mock(KfeFinancialRailHealthAdapter.class);
    private final KfeInternalRailHealthController controller = new KfeInternalRailHealthController(adapter);

    @Test
    void returnsCustodyProviderWhenCredentialMatches() {
        when(adapter.custodyProvider()).thenReturn(new FinancialRailHealthPort.ProviderStatus(
                "BITCOIN_CORE",
                true,
                "Adapter"));

        FinancialRailHealthPort.ProviderStatus status = controller.custodyProvider();

        assertEquals("BITCOIN_CORE", status.providerName());
        assertEquals("Adapter", status.implementation());
    }

    @Test
    void returnsExternalProvidersWhenCredentialMatches() {
        when(adapter.activeRailProviders()).thenReturn(Map.of(
                "onchain",
                new FinancialRailHealthPort.ProviderStatus("BITCOIN_CORE", true, "Onchain")));

        Map<String, FinancialRailHealthPort.ProviderStatus> providers = controller.activeRailProviders();

        assertEquals("BITCOIN_CORE", providers.get("onchain").providerName());
    }

}
