package com.kerosene.kfe.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.kfe.dto.KfeReserveOverviewResponse;
import com.kerosene.kfe.service.KfeReserveOverviewService;

@RestController
@RequestMapping("/api/admin/kfe/reserves")
public class KfeReserveAdminController {

    private final KfeReserveOverviewService reserveOverviewService;

    public KfeReserveAdminController(KfeReserveOverviewService reserveOverviewService) {
        this.reserveOverviewService = reserveOverviewService;
    }

    @GetMapping("/overview")
    public ResponseEntity<ApiResponse<KfeReserveOverviewResponse>> overview() {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE reserve overview retrieved.",
                reserveOverviewService.overview()));
    }
}
