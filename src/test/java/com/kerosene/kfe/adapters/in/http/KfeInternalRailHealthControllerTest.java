package com.kerosene.kfe.adapters.in.http;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.common.financial.operations.FinancialRailHealthPort;
import com.kerosene.kfe.adapters.out.integration.rail.KfeFinancialRailHealthAdapter;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KfeInternalRailHealthControllerTest {

    private final KfeFinancialRailHealthAdapter adapter = mock(KfeFinancialRailHealthAdapter.class);
    private final KfeInternalRailHealthController controller = new KfeInternalRailHealthController(adapter, "credential");

    @Test
    void returnsCustodyProviderWhenCredentialMatches() {
        when(adapter.custodyProviderHealth()).thenReturn(new FinancialRailHealthPort.ProviderHealth(
                "BITCOIN_CORE",
                "Adapter",
                FinancialRailHealthPort.HealthState.AVAILABLE,
                true, true, true, true,
                "MAINNET",
                0L,
                Instant.now(),
                null));

        FinancialRailHealthPort.ProviderHealth status = controller.custodyProvider("credential");

        assertEquals("BITCOIN_CORE", status.providerName());
        assertEquals("Adapter", status.implementation());
    }

    @Test
    void returnsExternalProvidersWhenCredentialMatches() {
        when(adapter.activeRailProviderHealth()).thenReturn(List.of(
                new FinancialRailHealthPort.ProviderHealth(
                        "BITCOIN_CORE", "Onchain", FinancialRailHealthPort.HealthState.AVAILABLE,
                        true, true, true, true, "MAINNET", 0L, Instant.now(), null)));

        List<FinancialRailHealthPort.ProviderHealth> providers = controller.activeRailProviders("credential");

        assertEquals("BITCOIN_CORE", providers.get(0).providerName());
    }

    @Test
    void rejectsInvalidCredential() {
        assertThrows(ResponseStatusException.class, () -> controller.custodyProvider("wrong"));
    }
}
