package com.kerosene.kfe.adapters.in.http;

import com.kerosene.common.financial.operations.FinancialRailHealthPort;
import com.kerosene.kfe.adapters.out.integration.rail.KfeFinancialRailHealthAdapter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Exposes authenticated internal health views for custody and configured financial rail providers. */
@RestController
@RequestMapping("/internal/kfe/rail-health")
public class KfeInternalRailHealthController {

    /** Adapter that queries provider health without exposing provider implementation details to callers. */
    private final KfeFinancialRailHealthAdapter railHealthAdapter;

    public KfeInternalRailHealthController(KfeFinancialRailHealthAdapter railHealthAdapter) {
        this.railHealthAdapter = railHealthAdapter;
    }

    /** Returns custody-provider health.
     * @return current custody provider health
     */
    @GetMapping("/custody-provider")
    public FinancialRailHealthPort.ProviderHealth custodyProvider() {
        return railHealthAdapter.custodyProviderHealth();
    }

    /** Returns health snapshots for active external rail providers.
     * @return list of active rail provider health snapshots
     */
    @GetMapping("/external-providers")
    public List<FinancialRailHealthPort.ProviderHealth> activeRailProviders() {
        return railHealthAdapter.activeRailProviderHealth();
    }
}
