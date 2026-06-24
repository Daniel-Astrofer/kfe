package source.kfe.integration;

import org.springframework.stereotype.Component;
import source.common.financial.FinancialAuditIntegrityPort;
import source.kfe.dto.KfeAuditRootResponse;
import source.kfe.service.KfeAuditAdminService;

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
