package source.kfe.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import source.common.dto.ApiResponse;
import source.kfe.dto.KfeDashboardResponse;
import source.kfe.service.KfeDashboardService;

@RestController
@RequestMapping("/kfe")
public class KfeDashboardController {

    private final KfeDashboardService dashboardService;

    public KfeDashboardController(KfeDashboardService dashboardService) {
        this.dashboardService = dashboardService;
    }

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<KfeDashboardResponse>> dashboard(Authentication authentication) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE dashboard retrieved.",
                dashboardService.dashboard(KfeAuthenticationSupport.authenticatedUserId(authentication))));
    }
}
