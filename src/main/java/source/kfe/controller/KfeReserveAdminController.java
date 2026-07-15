package source.kfe.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import source.common.dto.ApiResponse;
import source.kfe.dto.KfeReserveOverviewResponse;
import source.kfe.service.KfeReserveOverviewService;

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
