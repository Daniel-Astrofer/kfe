package com.kerosene.kfe.adapters.in.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.common.dto.ApiResponse;
import com.kerosene.common.financial.operations.FinancialWalletProvisioningRequest;
import com.kerosene.kfe.adapters.out.integration.wallet.KfeFinancialWalletProvisioningAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Receives authenticated service-to-service requests to ensure a user's primary KFE wallet exists. */
@RestController
@RequestMapping("/internal/kfe/wallet-provisioning")
public class KfeInternalWalletProvisioningController {

    /** Adapter that creates or repairs the requested user's primary financial wallet. */
    private final KfeFinancialWalletProvisioningAdapter walletProvisioningAdapter;
    /** Shared internal credential required before wallet provisioning can be requested. */
    private final String internalSecret;

    /**
     * Creates the provisioning endpoint with its downstream adapter and shared secret.
     *
     * @param walletProvisioningAdapter financial wallet provisioning adapter
     * @param internalSecret expected service-to-service credential
     */
    public KfeInternalWalletProvisioningController(
            KfeFinancialWalletProvisioningAdapter walletProvisioningAdapter,
            @Value("${kfe.internal.shared-secret:}") String internalSecret) {
        this.walletProvisioningAdapter = walletProvisioningAdapter;
        this.internalSecret = internalSecret;
    }

    /**
     * Ensures a primary wallet is ready for the requested user after authenticating the caller.
     *
     * @param token optional internal credential header
     * @param request user identifier and optional initial wallet address
     * @return standard success envelope once provisioning is complete
     */
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

    /** Rejects missing server configuration or invalid service credentials. */
    private void verifyServiceToken(String token) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "KFE internal shared secret is not configured");
        }
        if (token == null || token.isBlank() || !constantTimeEquals(internalSecret, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid KFE internal credential");
        }
    }

    /** Compares secret bytes without leaking the first differing position through early return. */
    private boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
