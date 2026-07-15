package source.kfe.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import source.common.financial.FinancialAuditIntegrityPort;
import source.kfe.integration.KfeFinancialAuditIntegrityAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@RestController
@RequestMapping("/internal/kfe/audit-integrity")
public class KfeInternalAuditIntegrityController {

    private final KfeFinancialAuditIntegrityAdapter auditIntegrityAdapter;
    private final String internalSecret;

    public KfeInternalAuditIntegrityController(
            KfeFinancialAuditIntegrityAdapter auditIntegrityAdapter,
            @Value("${kfe.internal.shared-secret:}") String internalSecret) {
        this.auditIntegrityAdapter = auditIntegrityAdapter;
        this.internalSecret = internalSecret;
    }

    @GetMapping("/root")
    public FinancialAuditIntegrityPort.AuditRoot root(
            @RequestHeader(name = "X-KFE-Internal-Secret", required = false) String credential) {
        verifyCredential(credential);
        return auditIntegrityAdapter.root();
    }

    private void verifyCredential(String credential) {
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "KFE internal shared secret is not configured");
        }
        if (credential == null || credential.isBlank() || !constantTimeEquals(internalSecret, credential)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid KFE internal credential");
        }
    }

    private boolean constantTimeEquals(String expected, String provided) {
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }
}
