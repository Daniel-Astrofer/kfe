package source.kfe.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import source.common.financial.FinancialRailHealthPort;
import source.kfe.integration.KfeFinancialRailHealthAdapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/internal/kfe/rail-health")
public class KfeInternalRailHealthController {

    private final KfeFinancialRailHealthAdapter railHealthAdapter;
    private final String internalSecret;

    public KfeInternalRailHealthController(
            KfeFinancialRailHealthAdapter railHealthAdapter,
            @Value("${kfe.internal.shared-secret:}") String internalSecret) {
        this.railHealthAdapter = railHealthAdapter;
        this.internalSecret = internalSecret;
    }

    @GetMapping("/custody-provider")
    public FinancialRailHealthPort.ProviderStatus custodyProvider(
            @RequestHeader(name = "X-KFE-Internal-Secret", required = false) String credential) {
        verifyCredential(credential);
        return railHealthAdapter.custodyProvider();
    }

    @GetMapping("/external-providers")
    public Map<String, FinancialRailHealthPort.ProviderStatus> activeRailProviders(
            @RequestHeader(name = "X-KFE-Internal-Secret", required = false) String credential) {
        verifyCredential(credential);
        return railHealthAdapter.activeRailProviders();
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
