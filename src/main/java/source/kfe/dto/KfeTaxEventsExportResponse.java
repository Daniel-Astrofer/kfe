package source.kfe.dto;

import java.util.List;

public record KfeTaxEventsExportResponse(
        String format,
        String filename,
        String educationalNotice,
        String content,
        List<KfeTaxEventResponse> events) {
}
