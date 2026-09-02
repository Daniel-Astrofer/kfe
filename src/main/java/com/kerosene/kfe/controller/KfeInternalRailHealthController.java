package com.kerosene.kfe.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.financial.FinancialRailHealthPort;
import com.kerosene.kfe.integration.KfeFinancialRailHealthAdapter;

import java.util.Map;

@RestController
@RequestMapping("/internal/kfe/rail-health")
public class KfeInternalRailHealthController {

    private final KfeFinancialRailHealthAdapter railHealthAdapter;

    public KfeInternalRailHealthController(KfeFinancialRailHealthAdapter railHealthAdapter) {
        this.railHealthAdapter = railHealthAdapter;
    }

    @GetMapping("/custody-provider")
    public FinancialRailHealthPort.ProviderStatus custodyProvider() {
        return railHealthAdapter.custodyProvider();
    }

    @GetMapping("/external-providers")
    public Map<String, FinancialRailHealthPort.ProviderStatus> activeRailProviders() {
        return railHealthAdapter.activeRailProviders();
    }
}
