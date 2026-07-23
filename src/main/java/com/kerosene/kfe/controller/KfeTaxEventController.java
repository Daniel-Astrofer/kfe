package com.kerosene.kfe.controller;

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
import com.kerosene.kfe.dto.KfeClassifyTaxEventRequest;
import com.kerosene.kfe.dto.KfeTaxEventResponse;
import com.kerosene.kfe.dto.KfeTaxEventsExportResponse;
import com.kerosene.kfe.service.KfeTaxEventService;

import java.util.List;

@RestController
@RequestMapping("/kfe/tax-events")
public class KfeTaxEventController {

    private final KfeTaxEventService taxEventService;

    public KfeTaxEventController(KfeTaxEventService taxEventService) {
        this.taxEventService = taxEventService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<KfeTaxEventResponse>>> list(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE tax events retrieved.",
                taxEventService.list(KfeAuthenticationSupport.authenticatedUserId(authentication))));
    }

    @GetMapping("/export")
    public ResponseEntity<ApiResponse<KfeTaxEventsExportResponse>> export(
            @RequestParam(defaultValue = "json") String format,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE tax events export generated.",
                taxEventService.export(KfeAuthenticationSupport.authenticatedUserId(authentication), format)));
    }

    @PostMapping("/{eventId}/classify")
    public ResponseEntity<ApiResponse<KfeTaxEventResponse>> classify(
            @PathVariable String eventId,
            @Valid @RequestBody KfeClassifyTaxEventRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE tax event classified.",
                taxEventService.classify(KfeAuthenticationSupport.authenticatedUserId(authentication), eventId, request)));
    }
}
