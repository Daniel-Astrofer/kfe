package com.kerosene.kfe.adapters.in.http;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import com.kerosene.common.financial.operations.FinancialAuditIntegrityPort;
import com.kerosene.kfe.adapters.out.integration.audit.KfeFinancialAuditIntegrityAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Authenticated internal endpoint exposing the current financial audit chain root. */
@RestController
@RequestMapping("/internal/kfe/audit-integrity")
public class KfeInternalAuditIntegrityController {

    /** Adapter that reads the current root from the shared financial audit subsystem. */
    private final KfeFinancialAuditIntegrityAdapter auditIntegrityAdapter;
    /** Shared credential required on every internal request; blank configuration disables the endpoint. */
    private final String internalSecret;

    /**
     * Creates the internal audit endpoint with its integration adapter and shared credential.
     *
     * @param auditIntegrityAdapter adapter that retrieves the current audit root
     * @param internalSecret configured secret expected in the internal request header
     */
    public KfeInternalAuditIntegrityController(
            KfeFinancialAuditIntegrityAdapter auditIntegrityAdapter,
            @Value("${kfe.internal.shared-secret:}") String internalSecret) {
        this.auditIntegrityAdapter = auditIntegrityAdapter;
        this.internalSecret = internalSecret;
    }

    /**
     * Returns the current audit-chain root after authenticating the caller with the internal secret.
     *
     * @param credential optional value of {@code X-KFE-Internal-Secret}
     * @return current audit root and its integrity metadata
     * @throws ResponseStatusException with 503 when no secret is configured or 401 for invalid credentials
     */
    @GetMapping("/root")
    public FinancialAuditIntegrityPort.AuditRoot root(
            @RequestHeader(name = "X-KFE-Internal-Secret", required = false) String credential) {
        verifyCredential(credential);
        return auditIntegrityAdapter.currentRoot();
    }

    /** Rejects requests when the endpoint is disabled or the supplied secret does not match. */
    private void verifyCredential(String credential) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "KFE internal shared secret is not configured");
        }
        if (credential == null || credential.isBlank() || !constantTimeEquals(internalSecret, credential)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid KFE internal credential");
        }
    }

    /** Compares credentials without early exit based on the first differing byte. */
    private boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
