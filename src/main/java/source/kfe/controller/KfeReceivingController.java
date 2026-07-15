package source.kfe.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import source.common.dto.ApiResponse;
import source.kfe.application.financial.FinancialApi;
import source.kfe.dto.KfeReceivingCapabilitiesResponse;

@RestController
@RequestMapping("/kfe")
public class KfeReceivingController {

    private final FinancialApi financialApi;

    public KfeReceivingController(FinancialApi financialApi) {
        this.financialApi = financialApi;
    }

    @GetMapping("/users/{receiverIdentifier}/receiving-capabilities")
    public ResponseEntity<ApiResponse<KfeReceivingCapabilitiesResponse>> capabilities(
            @PathVariable String receiverIdentifier) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE receiving capabilities retrieved.",
                financialApi.receivingCapabilities(receiverIdentifier)));
    }
}
