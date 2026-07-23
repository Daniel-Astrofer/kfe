package source.kfe.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import source.common.dto.ApiResponse;
import source.common.financial.FinancialWalletProvisioningRequest;
import source.kfe.integration.KfeFinancialWalletProvisioningAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/internal/kfe/wallet-provisioning")
public class KfeInternalWalletProvisioningController {

    private final KfeFinancialWalletProvisioningAdapter walletProvisioningAdapter;
    private final String internalSecret;

    public KfeInternalWalletProvisioningController(
            KfeFinancialWalletProvisioningAdapter walletProvisioningAdapter,
            @Value("${kfe.internal.shared-secret:}") String internalSecret) {
        this.walletProvisioningAdapter = walletProvisioningAdapter;
        this.internalSecret = internalSecret;
    }

    @PostMapping("/primary")
    public ResponseEntity<ApiResponse<Void>> ensurePrimaryWalletReady(
            @RequestHeader(name = "X-KFE-Internal-Secret", required = false) String token,
            @RequestBody FinancialWalletProvisioningRequest request) {
        verifyServiceToken(token);
        if (request == null || request.userId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }
        walletProvisioningAdapter.ensurePrimaryWalletReady(request.userId(), request.initialAddress());
        return ResponseEntity.ok(ApiResponse.success("Primary KFE wallet is ready.", null));
    }

    private void verifyServiceToken(String token) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "KFE internal shared secret is not configured");
        }
        if (token == null || token.isBlank() || !constantTimeEquals(internalSecret, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid KFE internal credential");
        }
    }

    private boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
