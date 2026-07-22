package source.kfe.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import source.common.dto.ApiResponse;
import source.kfe.dto.KfeAuditEventResponse;
import source.kfe.dto.KfeAuditLatestResponse;
import source.kfe.dto.KfeAuditRootResponse;
import source.kfe.service.KfeAuditAdminService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/kfe/audit")
@PreAuthorize("hasRole('ADMIN')")
public class KfeAuditAdminController {

    private final KfeAuditAdminService auditAdminService;

    public KfeAuditAdminController(KfeAuditAdminService auditAdminService) {
        this.auditAdminService = auditAdminService;
    }

    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<KfeAuditLatestResponse>> latest() {
        return ResponseEntity.ok(ApiResponse.success("KFE audit latest root retrieved.", auditAdminService.latest()));
    }

    @GetMapping("/events")
    public ResponseEntity<ApiResponse<List<KfeAuditEventResponse>>> events(
            @RequestParam(defaultValue = "50") int limit) {
        return ResponseEntity.ok(ApiResponse.success("KFE audit events retrieved.", auditAdminService.events(limit)));
    }

    @GetMapping("/transactions/{transactionId}")
    public ResponseEntity<ApiResponse<List<KfeAuditEventResponse>>> transactionEvents(
            @PathVariable UUID transactionId) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE transaction audit events retrieved.",
                auditAdminService.transactionEvents(transactionId)));
    }

    @PostMapping("/root")
    public ResponseEntity<ApiResponse<KfeAuditRootResponse>> root() {
        return ResponseEntity.ok(ApiResponse.success("KFE audit root computed.", auditAdminService.root()));
    }
}
