package com.kerosene.kfe.integration;

import org.springframework.stereotype.Component;
import com.kerosene.common.financial.FinancialAuditIntegrityPort;
import com.kerosene.kfe.dto.KfeAuditRootResponse;
import com.kerosene.kfe.service.KfeAuditAdminService;

@Component
public class KfeFinancialAuditIntegrityAdapter implements FinancialAuditIntegrityPort {

    private final KfeAuditAdminService auditAdminService;

    public KfeFinancialAuditIntegrityAdapter(KfeAuditAdminService auditAdminService) {
        this.auditAdminService = auditAdminService;
    }

    @Override
    public AuditRoot root() {
        KfeAuditRootResponse root = auditAdminService.root();
        return new AuditRoot(
                root.merkleRoot(),
                root.eventCount(),
                root.fromSequence(),
                root.toSequence(),
                root.generatedAt());
    }
}
