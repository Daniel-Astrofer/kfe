package com.kerosene.kfe.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.financial.FinancialAuditIntegrityPort;
import com.kerosene.kfe.integration.KfeFinancialAuditIntegrityAdapter;

@RestController
@RequestMapping("/internal/kfe/audit-integrity")
public class KfeInternalAuditIntegrityController {

    private final KfeFinancialAuditIntegrityAdapter auditIntegrityAdapter;

    public KfeInternalAuditIntegrityController(KfeFinancialAuditIntegrityAdapter auditIntegrityAdapter) {
        this.auditIntegrityAdapter = auditIntegrityAdapter;
    }

    @GetMapping("/root")
    public FinancialAuditIntegrityPort.AuditRoot root() {
        return auditIntegrityAdapter.currentRoot();
    }
}
