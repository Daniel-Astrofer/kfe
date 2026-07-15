package source.kfe.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import source.common.dto.ApiResponse;
import source.kfe.dto.KfePaymentRequestResponse;
import source.kfe.service.KfePaymentRequestService;

@RestController
@RequestMapping("/api/public/kfe/payment-requests")
public class KfePublicPaymentRequestController {

    private final KfePaymentRequestService paymentRequestService;

    public KfePublicPaymentRequestController(KfePaymentRequestService paymentRequestService) {
        this.paymentRequestService = paymentRequestService;
    }

    @GetMapping("/{publicId}")
    public ResponseEntity<ApiResponse<KfePaymentRequestResponse>> getPublic(@PathVariable String publicId) {
        return ResponseEntity.ok(ApiResponse.success(
                "KFE public payment request retrieved.",
                paymentRequestService.publicGet(publicId)));
    }
}
