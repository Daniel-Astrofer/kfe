package com.kerosene.kfe.dto;

public record KfeAuditLatestResponse(
        KfeAuditEventResponse latestEvent,
        KfeAuditRootResponse root) {
}
