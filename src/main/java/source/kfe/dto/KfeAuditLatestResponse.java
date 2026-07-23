package source.kfe.dto;

public record KfeAuditLatestResponse(
        KfeAuditEventResponse latestEvent,
        KfeAuditRootResponse root) {
}
