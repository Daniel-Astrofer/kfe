package source.kfe.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import source.common.dto.ApiResponse;
import source.kfe.dto.KfePsbtWorkflowResponse;
import source.kfe.dto.KfeSignedPsbtRequest;
import source.kfe.service.KfePsbtWorkflowService;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/kfe/reserves/psbts")
public class KfeReservePsbtAdminController {

    private final KfePsbtWorkflowService psbtWorkflowService;

    public KfeReservePsbtAdminController(KfePsbtWorkflowService psbtWorkflowService) {
        this.psbtWorkflowService = psbtWorkflowService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<KfePsbtWorkflowResponse>>> list(
            @RequestParam(required = false) UUID walletId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE PSBT workflows retrieved.",
                psbtWorkflowService.list(authenticatedUserId(authentication), walletId)));
    }

    @GetMapping("/{workflowId}")
    public ResponseEntity<ApiResponse<KfePsbtWorkflowResponse>> get(
            @PathVariable UUID workflowId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE PSBT workflow retrieved.",
                psbtWorkflowService.get(authenticatedUserId(authentication), workflowId)));
    }

    @PostMapping("/{workflowId}/signed")
    public ResponseEntity<ApiResponse<KfePsbtWorkflowResponse>> signed(
            @PathVariable UUID workflowId,
            @Valid @RequestBody KfeSignedPsbtRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE signed PSBT accepted.",
                psbtWorkflowService.attachSignedPsbt(authenticatedUserId(authentication), workflowId, request)));
    }

    @PostMapping("/{workflowId}/broadcast")
    public ResponseEntity<ApiResponse<KfePsbtWorkflowResponse>> broadcast(
            @PathVariable UUID workflowId,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE PSBT workflow broadcast.",
                psbtWorkflowService.broadcast(authenticatedUserId(authentication), workflowId)));
    }

    private Long authenticatedUserId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new SecurityException("Authenticated user is required.");
        }
        return Long.parseLong(authentication.getName());
    }
}
