package source.kfe.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import source.common.dto.ApiResponse;
import source.kfe.service.KfeOnrampDirectoryService;

import java.util.Map;

@RestController
@RequestMapping("/kfe/transactions")
public class KfeOnrampController {

    private final KfeOnrampDirectoryService onrampDirectoryService;

    public KfeOnrampController(KfeOnrampDirectoryService onrampDirectoryService) {
        this.onrampDirectoryService = onrampDirectoryService;
    }

    @GetMapping("/onramp-urls")
    public ResponseEntity<ApiResponse<Map<String, String>>> urls() {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE onramp URLs retrieved.",
                onrampDirectoryService.urls()));
    }
}
