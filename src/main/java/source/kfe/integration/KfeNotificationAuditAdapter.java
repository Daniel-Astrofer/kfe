package source.kfe.integration;

import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import source.common.financial.FinancialNotificationAuditPort;
import source.kfe.service.KfeAuditLogService;

import java.util.Map;

@Component
@Primary
public class KfeNotificationAuditAdapter implements FinancialNotificationAuditPort {

    private final KfeAuditLogService auditLogService;

    public KfeNotificationAuditAdapter(KfeAuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    @Override
    public void recordDeviceTokenEvent(String eventType, Map<String, ?> redactedPayload) {
        auditLogService.record(
                eventType,
                null,
                null,
                null,
                null,
                redactedPayload);
    }
}
